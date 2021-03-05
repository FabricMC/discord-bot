/*
 * Copyright (c) 2021 FabricMC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.fabricmc.discord.bot;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.javacord.api.entity.channel.Channel;
import org.javacord.api.entity.channel.ServerChannel;
import org.javacord.api.entity.channel.ServerTextChannel;
import org.javacord.api.entity.message.Message;
import org.javacord.api.entity.message.MessageAuthor;
import org.javacord.api.entity.server.Server;
import org.javacord.api.event.channel.server.ServerChannelChangeOverwrittenPermissionsEvent;
import org.javacord.api.event.channel.server.ServerChannelCreateEvent;
import org.javacord.api.event.channel.server.ServerChannelDeleteEvent;
import org.javacord.api.event.message.MessageCreateEvent;
import org.javacord.api.event.message.MessageDeleteEvent;
import org.javacord.api.listener.ChainableGloballyAttachableListenerManager;
import org.javacord.api.listener.channel.server.ServerChannelChangeOverwrittenPermissionsListener;
import org.javacord.api.listener.channel.server.ServerChannelCreateListener;
import org.javacord.api.listener.channel.server.ServerChannelDeleteListener;
import org.javacord.api.listener.message.MessageCreateListener;
import org.javacord.api.listener.message.MessageDeleteListener;

public final class MessageIndex implements MessageCreateListener, MessageDeleteListener,
ServerChannelCreateListener, ServerChannelDeleteListener, ServerChannelChangeOverwrittenPermissionsListener {
	private static final int INIT_LIMIT = 1000;
	private static final int MESSAGE_LIMIT = 10000;

	private static final CachedMessage DELETED_MESSAGE = new CachedMessage();

	private final DiscordBot bot;
	private final Map<ServerTextChannel, ChannelMessageCache> channelCaches = new ConcurrentHashMap<>();
	private final Map<Long, CachedMessage> globalIndex = new ConcurrentHashMap<>();

	public MessageIndex(DiscordBot bot) {
		this.bot = bot;

		bot.getActiveHandler().registerReadyHandler(this::init);
		bot.getActiveHandler().registerGoneHandler(this::reset);
	}

	public CachedMessage get(long id) {
		return globalIndex.get(id);
	}

	public CachedMessage get(ServerTextChannel channel, long id) {
		ChannelMessageCache cache = channelCaches.get(channel);
		if (cache == null) return null;

		synchronized (cache) {
			return cache.get(id);
		}
	}

	public Collection<CachedMessage> getAllByAuthor(long authorId) {
		List<CachedMessage> ret = new ArrayList<>();

		for (CachedMessage message : globalIndex.values()) {
			if (message.authorId == authorId) {
				ret.add(message);
			}
		}

		return ret;
	}

	public long[] getAllIdsByAuthor(ServerTextChannel channel, long authorId) {
		class IdGatherVisitor implements Visitor {
			@Override
			public boolean visit(CachedMessage message) {
				if (message.authorId == authorId) {
					if (idx >= res.length) res = Arrays.copyOf(res, res.length * 2);
					res[idx++] = message.id;
				}

				return true;
			}

			long[] res = new long[50];
			int idx;
		}

		IdGatherVisitor visitor = new IdGatherVisitor();
		accept(channel, visitor);

		return Arrays.copyOf(visitor.res, visitor.idx);
	}

	public void accept(ServerTextChannel channel, Visitor visitor) {
		ChannelMessageCache cache = channelCaches.get(channel);
		if (cache == null) return;

		synchronized (cache) {
			cache.accept(visitor);
		}
	}

	void registerEarlyHandlers(ChainableGloballyAttachableListenerManager src) {
		src.addServerChannelCreateListener(this);
		src.addServerChannelDeleteListener(this);
		src.addServerChannelChangeOverwrittenPermissionsListener(this);
		src.addMessageCreateListener(this);
		src.addMessageDeleteListener(this);
	}

	private void init(Server server, long lastActiveTime) {
		for (ServerTextChannel channel : server.getTextChannels()) {
			if (isValidChannel(channel)) {
				initChannel(channel);
			}
		}
	}

	private void reset(Server server) {
		globalIndex.clear();
		channelCaches.clear();
	}

	private boolean isValidChannel(Channel channel) {
		if (!(channel instanceof ServerTextChannel)) return false;

		ServerTextChannel textChannel = (ServerTextChannel) channel;

		return textChannel.canYouSee() && textChannel.canYouReadMessageHistory();
	}

	private void initChannel(ServerTextChannel channel) {
		ChannelMessageCache cache = new ChannelMessageCache();

		synchronized (cache) {
			if (channelCaches.putIfAbsent(channel, cache) != null) return;

			for (Message message : channel.getMessages(Math.min(INIT_LIMIT, MESSAGE_LIMIT)).join()) {
				cache.add(new CachedMessage(message));
			}
		}
	}

	@Override
	public void onServerChannelCreate(ServerChannelCreateEvent event) {
		if (event.getServer().getId() != bot.getServerId()) return;

		ServerChannel channel = event.getChannel();

		if (isValidChannel(channel)) {
			initChannel((ServerTextChannel) channel);
		}
	}

	@Override
	public void onServerChannelDelete(ServerChannelDeleteEvent event) {
		if (event.getServer().getId() != bot.getServerId()) return;

		ChannelMessageCache prev = channelCaches.remove(event.getChannel());

		if (prev != null) {
			prev.clear();
		}
	}

	@Override
	public void onServerChannelChangeOverwrittenPermissions(ServerChannelChangeOverwrittenPermissionsEvent event) {
		if (event.getServer().getId() != bot.getServerId()) return;

		ServerChannel channel = event.getChannel();

		if (isValidChannel(channel)) {
			initChannel((ServerTextChannel) channel);
		} else {
			ChannelMessageCache prev = channelCaches.remove(channel);

			if (prev != null) {
				prev.clear();
			}
		}
	}

	@Override
	public void onMessageCreate(MessageCreateEvent event) {
		ChannelMessageCache cache = channelCaches.get(event.getChannel());
		if (cache == null) return;

		synchronized (cache) {
			cache.add(new CachedMessage(event.getMessage()));
		}
	}

	@Override
	public void onMessageDelete(MessageDeleteEvent event) {
		ChannelMessageCache cache = channelCaches.get(event.getChannel());
		if (cache == null) return;

		synchronized (cache) {
			cache.remove(event.getMessageId());
		}
	}

	private final class ChannelMessageCache {
		final CachedMessage[] messages = new CachedMessage[MESSAGE_LIMIT];
		private int writeIdx;
		final Map<Long, Integer> index = new HashMap<>();

		CachedMessage get(long id) {
			Integer pos = index.get(id);
			if (pos == null) return null;

			return messages[pos];
		}

		boolean add(CachedMessage message) {
			Long key = message.id;
			if (index.putIfAbsent(key, writeIdx) != null) return false;

			CachedMessage prev = messages[writeIdx];

			if (prev != null) {
				Long prevKey = prev.id;
				index.remove(prevKey);
				globalIndex.remove(prevKey);
			}

			globalIndex.put(key, message);
			messages[writeIdx] = message;
			writeIdx = inc(writeIdx);

			return true;
		}

		boolean remove(long id) {
			Integer pos = index.remove(id);
			if (pos == null) return false;

			globalIndex.remove(id);
			messages[pos] = DELETED_MESSAGE;

			return true;
		}

		void clear() {
			for (Long key : index.keySet()) {
				globalIndex.remove(key);
			}

			index.clear();
			writeIdx = 0;
			Arrays.fill(messages, null);
		}

		void accept(Visitor visitor) {
			int idx = writeIdx;

			do {
				idx = dec(idx);
				CachedMessage message = messages[idx];
				if (message == null) break;

				if (message != DELETED_MESSAGE) {
					if (!visitor.visit(message)) break;
				}
			} while (idx != writeIdx);
		}
	}

	private static int inc(int idx) {
		return (idx + 1) % MESSAGE_LIMIT;
	}

	private static int dec(int idx) {
		return (idx + MESSAGE_LIMIT - 1) % MESSAGE_LIMIT;
	}

	public static final class CachedMessage {
		CachedMessage() {
			this.id = -1;
			this.channelId = -1;
			this.authorId = -1;
		}

		CachedMessage(Message message) {
			this.id = message.getId();
			this.channelId = message.getChannel().getId();

			MessageAuthor author = message.getAuthor();
			this.authorId = author.isWebhook() ? -1 : author.getId();
		}

		public long getId() {
			return id;
		}

		public long getChannelId() {
			return channelId;
		}

		public long getAuthorId() {
			return authorId;
		}

		final long id;
		final long channelId;
		final long authorId;
	}

	public interface Visitor {
		boolean visit(CachedMessage message);
	}
}
