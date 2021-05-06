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

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.javacord.api.entity.DiscordEntity;
import org.javacord.api.entity.channel.Channel;
import org.javacord.api.entity.channel.ServerChannel;
import org.javacord.api.entity.channel.ServerTextChannel;
import org.javacord.api.entity.channel.TextChannel;
import org.javacord.api.entity.message.Message;
import org.javacord.api.entity.message.MessageAuthor;
import org.javacord.api.entity.server.Server;
import org.javacord.api.event.channel.server.ServerChannelChangeOverwrittenPermissionsEvent;
import org.javacord.api.event.channel.server.ServerChannelCreateEvent;
import org.javacord.api.event.channel.server.ServerChannelDeleteEvent;
import org.javacord.api.event.message.MessageCreateEvent;
import org.javacord.api.event.message.MessageDeleteEvent;
import org.javacord.api.exception.DiscordException;
import org.javacord.api.exception.NotFoundException;
import org.javacord.api.listener.ChainableGloballyAttachableListenerManager;
import org.javacord.api.listener.channel.server.ServerChannelChangeOverwrittenPermissionsListener;
import org.javacord.api.listener.channel.server.ServerChannelCreateListener;
import org.javacord.api.listener.channel.server.ServerChannelDeleteListener;
import org.javacord.api.listener.message.MessageCreateListener;
import org.javacord.api.listener.message.MessageDeleteListener;
import org.jetbrains.annotations.Nullable;

import net.fabricmc.discord.bot.util.DiscordUtil;

public final class MessageIndex implements MessageCreateListener, MessageDeleteListener,
ServerChannelCreateListener, ServerChannelDeleteListener, ServerChannelChangeOverwrittenPermissionsListener {
	private static final int INIT_LIMIT = 1000;
	private static final int MESSAGE_LIMIT = 10000;

	private static final Pattern MESSAGE_LINK_PATTERN = Pattern.compile("https://discord.com/channels/(@me|\\d+)/(\\d+)/(\\d+)");

	private final DiscordBot bot;
	private final List<MessageCreateHandler> createHandlers = new CopyOnWriteArrayList<>();
	private final List<MessageDeleteHandler> deleteHandlers = new CopyOnWriteArrayList<>();
	private final Map<ServerTextChannel, ChannelMessageCache> channelCaches = new ConcurrentHashMap<>();
	private final Map<Long, CachedMessage> globalIndex = new ConcurrentHashMap<>();

	public MessageIndex(DiscordBot bot) {
		this.bot = bot;

		bot.getActiveHandler().registerReadyHandler(this::init);
		bot.getActiveHandler().registerGoneHandler(this::reset);
	}

	public void registerCreateHandler(MessageCreateHandler handler) {
		createHandlers.add(handler);
	}

	public void registerDeleteHandler(MessageDeleteHandler handler) {
		deleteHandlers.add(handler);
	}

	public @Nullable CachedMessage get(long id) {
		return globalIndex.get(id);
	}

	public @Nullable CachedMessage get(ServerTextChannel channel, long id) {
		ChannelMessageCache cache = channelCaches.get(channel);
		if (cache == null) return null;

		synchronized (cache) {
			return cache.get(id);
		}
	}

	public Collection<CachedMessage> getAllByAuthor(long authorId, boolean includeDeleted) {
		List<CachedMessage> ret = new ArrayList<>();

		for (CachedMessage message : globalIndex.values()) {
			if (message.authorId == authorId && (includeDeleted || !message.isDeleted())) {
				ret.add(message);
			}
		}

		return ret;
	}

	public long[] getAllIdsByAuthor(ServerTextChannel channel, long authorId, boolean includeDeleted) {
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
		accept(channel, visitor, includeDeleted);

		return Arrays.copyOf(visitor.res, visitor.idx);
	}

	public @Nullable CachedMessage get(String desc, @Nullable Server server) {
		Matcher matcher = MESSAGE_LINK_PATTERN.matcher(desc);

		if (matcher.matches()) {
			String guild = matcher.group(1);

			if (!guild.equals("@me") && server != null && server.getId() == Long.parseUnsignedLong(guild)) {
				ServerTextChannel channel = server.getTextChannelById(Long.parseUnsignedLong(matcher.group(2))).orElse(null);

				return channel != null ? get(channel, Long.parseUnsignedLong(matcher.group(3))) : null;
			} else {
				desc = matcher.group(3);
			}
		}

		try {
			return get(Long.parseUnsignedLong(desc));
		} catch (NumberFormatException e) { }

		return null;
	}

	public void accept(ServerTextChannel channel, Visitor visitor, boolean includeDeleted) {
		ChannelMessageCache cache = channelCaches.get(channel);
		if (cache == null) return;

		synchronized (cache) {
			cache.accept(visitor, includeDeleted);
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
		Server server = event.getServer().orElse(null);
		if (server == null) return;

		ChannelMessageCache cache = channelCaches.get(event.getChannel());
		if (cache == null) return;

		CachedMessage msg = new CachedMessage(event.getMessage());

		synchronized (cache) {
			cache.add(msg);
		}

		createHandlers.forEach(h -> h.onMessageCreated(server, msg));
	}

	@Override
	public void onMessageDelete(MessageDeleteEvent event) {
		Server server = event.getServer().orElse(null);
		if (server == null) return;

		ChannelMessageCache cache = channelCaches.get(event.getChannel());
		if (cache == null) return;

		CachedMessage msg;

		synchronized (cache) {
			msg = cache.get(event.getMessageId());
		}

		if (msg == null) {
			Message message = event.getMessage().orElse(null);
			if (message == null) return;

			msg = new CachedMessage(message);
		}

		msg.setDeleted();

		CachedMessage finalMsg = msg;
		deleteHandlers.forEach(h -> h.onMessageDeleted(server, finalMsg));
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

		void clear() {
			for (Long key : index.keySet()) {
				globalIndex.remove(key);
			}

			index.clear();
			writeIdx = 0;
			Arrays.fill(messages, null);
		}

		void accept(Visitor visitor, boolean includeDeleted) {
			int idx = writeIdx;

			do {
				idx = dec(idx);
				CachedMessage message = messages[idx];
				if (message == null) break;

				if (includeDeleted || !message.isDeleted()) {
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
			this.userMentions = null;
			this.roleMentions = null;
		}

		CachedMessage(Message message) {
			this.id = message.getId();
			this.channelId = message.getChannel().getId();

			MessageAuthor author = message.getAuthor();
			this.authorId = author.isWebhook() ? -1 : author.getId();

			userMentions = serializeMentions(message.getMentionedUsers());
			roleMentions = serializeMentions(message.getMentionedRoles());
		}

		private static long[] serializeMentions(List<? extends DiscordEntity> list) {
			int size = list.size();
			if (size == 0) return emptyMentions;

			long[] ret = new long[size];

			for (int i = 0; i < size; i++) {
				ret[i] = list.get(i).getId();
			}

			return ret;
		}

		public long getId() {
			return id;
		}

		public Instant getCreationTime() {
			return DiscordEntity.getCreationTimestamp(id);
		}

		public long getChannelId() {
			return channelId;
		}

		public long getAuthorDiscordId() {
			return authorId;
		}

		public long[] getUserMentions() {
			return userMentions;
		}

		public long[] getRoleMentions() {
			return roleMentions;
		}

		public boolean isDeleted() {
			return deleted;
		}

		void setDeleted() {
			deleted = true;
		}

		public @Nullable Message toMessage(Server server) throws DiscordException {
			TextChannel channel = server.getTextChannelById(channelId).orElse(null);
			if (channel == null) return null;

			try {
				return DiscordUtil.join(channel.getMessageById(id));
			} catch (NotFoundException e) {
				return null;
			}
		}

		private static final long[] emptyMentions = new long[0];

		final long id;
		final long channelId;
		final long authorId;
		private final long[] userMentions;
		private final long[] roleMentions;
		private volatile boolean deleted;
	}

	public interface Visitor {
		boolean visit(CachedMessage message);
	}

	public interface MessageCreateHandler {
		void onMessageCreated(Server server, CachedMessage message);
	}

	public interface MessageDeleteHandler {
		void onMessageDeleted(Server server, CachedMessage message);
	}
}
