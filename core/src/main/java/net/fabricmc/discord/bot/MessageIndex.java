/*
 * Copyright (c) 2021, 2022 FabricMC
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
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import it.unimi.dsi.fastutil.longs.Long2IntMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongList;
import it.unimi.dsi.fastutil.longs.LongSet;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.javacord.api.entity.channel.Channel;
import org.javacord.api.entity.channel.ServerChannel;
import org.javacord.api.entity.channel.TextChannel;
import org.javacord.api.entity.message.Message;
import org.javacord.api.entity.server.Server;
import org.javacord.api.event.channel.server.ServerChannelChangeOverwrittenPermissionsEvent;
import org.javacord.api.event.channel.server.ServerChannelCreateEvent;
import org.javacord.api.event.channel.server.ServerChannelDeleteEvent;
import org.javacord.api.event.channel.thread.ThreadCreateEvent;
import org.javacord.api.event.channel.thread.ThreadDeleteEvent;
import org.javacord.api.event.message.MessageCreateEvent;
import org.javacord.api.event.message.MessageDeleteEvent;
import org.javacord.api.event.message.MessageEditEvent;
import org.javacord.api.exception.DiscordException;
import org.javacord.api.exception.NotFoundException;
import org.javacord.api.listener.ChainableGloballyAttachableListenerManager;
import org.javacord.api.listener.channel.server.ServerChannelChangeOverwrittenPermissionsListener;
import org.javacord.api.listener.channel.server.ServerChannelCreateListener;
import org.javacord.api.listener.channel.server.ServerChannelDeleteListener;
import org.javacord.api.listener.channel.server.thread.ServerThreadChannelCreateListener;
import org.javacord.api.listener.channel.server.thread.ServerThreadChannelDeleteListener;
import org.javacord.api.listener.message.MessageCreateListener;
import org.javacord.api.listener.message.MessageDeleteListener;
import org.javacord.api.listener.message.MessageEditListener;
import org.jetbrains.annotations.Nullable;

import net.fabricmc.discord.bot.util.DiscordUtil;

public final class MessageIndex implements MessageCreateListener, MessageDeleteListener,
ServerChannelCreateListener, ServerChannelDeleteListener,
ServerThreadChannelCreateListener, ServerThreadChannelDeleteListener,
ServerChannelChangeOverwrittenPermissionsListener, MessageEditListener {
	private static final int INIT_LIMIT = 1000;
	private static final int MESSAGE_LIMIT = 10000;

	private static final String[] DISCORD_DOMAINS = { "discord.com", "discordapp.com" };
	private static final Pattern MESSAGE_LINK_PATTERN = Pattern.compile(String.format("https://(?:%s)/channels/(@me|\\d+)/(\\d+)/(\\d+)",
			Arrays.stream(DISCORD_DOMAINS).map(Pattern::quote).collect(Collectors.joining("|"))));

	private static final Logger LOGGER = LogManager.getLogger(MessageIndex.class);

	private final DiscordBot bot;
	private final List<MessageCreateHandler> createHandlers = new CopyOnWriteArrayList<>();
	private final List<MessageDeleteHandler> deleteHandlers = new CopyOnWriteArrayList<>();
	private final Map<TextChannel, ChannelMessageCache> channelCaches = new ConcurrentHashMap<>();
	private final Long2ObjectMap<CachedMessage> globalIndex = new Long2ObjectOpenHashMap<>();

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
		synchronized (globalIndex) {
			return globalIndex.get(id);
		}
	}

	public @Nullable CachedMessage get(Message message) {
		CachedMessage ret = globalIndex.get(message.getId());

		return ret != null ? ret : new CachedMessage(message);
	}

	public @Nullable CachedMessage get(TextChannel channel, long id) {
		ChannelMessageCache cache = channelCaches.get(channel);
		if (cache == null) return null;

		synchronized (cache) {
			return cache.get(id);
		}
	}

	public Collection<CachedMessage> getAll(TextChannel channel, boolean includeDeleted) {
		List<CachedMessage> res = new ArrayList<>();

		accept(channel, msg -> {
			res.add(msg);

			return true;
		}, includeDeleted);

		return res;
	}

	public Collection<CachedMessage> getAllByAuthor(long authorId, boolean includeDeleted) {
		List<CachedMessage> ret = new ArrayList<>();

		synchronized (globalIndex) {
			for (CachedMessage message : globalIndex.values()) {
				if (message.getAuthorDiscordId() == authorId && (includeDeleted || !message.isDeleted())) {
					ret.add(message);
				}
			}
		}

		return ret;
	}

	public Collection<CachedMessage> getAllByAuthors(LongSet authorDiscordIds, boolean includeDeleted) {
		List<CachedMessage> ret = new ArrayList<>();

		synchronized (globalIndex) {
			for (CachedMessage message : globalIndex.values()) {
				if (authorDiscordIds.contains(message.getAuthorDiscordId()) && (includeDeleted || !message.isDeleted())) {
					ret.add(message);
				}
			}
		}

		return ret;
	}

	public Collection<CachedMessage> getAllByAuthors(LongSet authorDiscordIds, TextChannel channel, boolean includeDeleted) {
		if (authorDiscordIds.isEmpty()) return Collections.emptyList();

		List<CachedMessage> res = new ArrayList<>();

		accept(channel, msg -> {
			if (authorDiscordIds.contains(msg.getAuthorDiscordId())) {
				res.add(msg);
			}

			return true;
		}, includeDeleted);

		return res;
	}

	public @Nullable CachedMessage get(String desc, @Nullable Server server) throws DiscordException {
		Matcher matcher = MESSAGE_LINK_PATTERN.matcher(desc);

		if (matcher.matches()) {
			String guild = matcher.group(1);

			if (!guild.equals("@me") && server != null && server.getId() == Long.parseUnsignedLong(guild)) {
				TextChannel channel = DiscordUtil.getTextChannel(server, Long.parseUnsignedLong(matcher.group(2)));
				if (channel == null) return null;

				long msgId = Long.parseUnsignedLong(matcher.group(3));
				CachedMessage msg = get(channel, msgId);

				if (msg == null) {
					try {
						msg = new CachedMessage(DiscordUtil.join(channel.getMessageById(msgId)));
					} catch (NotFoundException e) {
						// ignore
					}
				}

				return msg;
			} else {
				desc = matcher.group(3);
			}
		}

		try {
			return get(Long.parseUnsignedLong(desc));
		} catch (NumberFormatException e) { }

		return null;
	}

	public void accept(Visitor visitor, boolean includeDeleted) {
		synchronized (globalIndex) {
			for (CachedMessage message : globalIndex.values()) {
				if (includeDeleted || !message.isDeleted()) {
					visitor.visit(message);
				}
			}
		}
	}

	public void accept(TextChannel channel, Visitor visitor, boolean includeDeleted) {
		Objects.requireNonNull(channel, "null channel");

		ChannelMessageCache cache = channelCaches.get(channel);
		if (cache == null) return;

		synchronized (cache) {
			cache.accept(visitor, includeDeleted);
		}
	}

	public Collection<TextChannel> getCachedChannels() {
		return channelCaches.keySet();
	}

	public int getSize(TextChannel channel) {
		ChannelMessageCache cache = channelCaches.get(channel);
		if (cache == null) return 0;

		synchronized (cache) {
			return cache.size();
		}
	}

	void registerEarlyHandlers(ChainableGloballyAttachableListenerManager src) {
		src.addServerChannelCreateListener(this);
		src.addServerChannelDeleteListener(this);
		src.addServerThreadChannelCreateListener(this);
		src.addServerThreadChannelDeleteListener(this);
		src.addServerChannelChangeOverwrittenPermissionsListener(this);
		src.addMessageCreateListener(this);
		src.addMessageEditListener(this);
		src.addMessageDeleteListener(this);
	}

	private void init(Server server, long lastActiveTime) {
		LongList invalidChannels = new LongArrayList();

		for (TextChannel channel : DiscordUtil.getTextChannels(server)) {
			if (isValidChannel(channel)) {
				initChannel(channel);
			} else {
				invalidChannels.add(channel.getId());
			}
		}

		if (!invalidChannels.isEmpty()) LOGGER.info("Skipping inaccessible channels {}", invalidChannels);
	}

	private void reset(Server server) {
		synchronized (globalIndex) {
			globalIndex.clear();
		}

		channelCaches.clear();
	}

	private boolean isValidChannel(Channel channel) {
		if (!(channel instanceof TextChannel)) return false;

		TextChannel textChannel = (TextChannel) channel;

		return textChannel.canYouSee() && textChannel.canYouReadMessageHistory();
	}

	private void initChannel(TextChannel channel) {
		ChannelMessageCache cache = channelCaches.computeIfAbsent(channel, ignore -> new ChannelMessageCache());

		synchronized (cache) {
			try {
				for (Message message : DiscordUtil.join(channel.getMessages(Math.min(INIT_LIMIT, MESSAGE_LIMIT)))) {
					cache.add(new CachedMessage(message));
				}
			} catch (DiscordException e) {
				LOGGER.warn("Error initializing channel {}", channel.getId(), e);
			}
		}
	}

	@Override
	public void onServerChannelCreate(ServerChannelCreateEvent event) {
		onChannelCreate(event.getChannel());
	}

	@Override
	public void onServerChannelDelete(ServerChannelDeleteEvent event) {
		onChannelDelete(event.getChannel());
	}

	@Override
	public void onThreadCreate(ThreadCreateEvent event) {
		onChannelCreate(event.getChannel());
	}

	@Override
	public void onThreadDelete(ThreadDeleteEvent event) {
		onChannelDelete(event.getChannel());
	}

	private void onChannelCreate(ServerChannel channel) {
		if (channel.getServer().getId() != bot.getServerId()) return;

		if (isValidChannel(channel)) {
			initChannel((TextChannel) channel);
		}
	}

	private void onChannelDelete(ServerChannel channel) {
		if (channel.getServer().getId() != bot.getServerId()) return;
		if (!(channel instanceof TextChannel)) return;

		TextChannel textChannel = (TextChannel) channel;

		ChannelMessageCache prev = channelCaches.remove(textChannel);

		if (prev != null) {
			prev.clear();
		}
	}

	@Override
	public void onServerChannelChangeOverwrittenPermissions(ServerChannelChangeOverwrittenPermissionsEvent event) {
		if (event.getServer().getId() != bot.getServerId()) return;
		if (!(event.getChannel() instanceof TextChannel)) return;

		TextChannel channel = (TextChannel) event.getChannel();

		if (isValidChannel(channel)) {
			initChannel(channel);
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

		if (cache == null) {
			LOGGER.warn("Received message {} on unknown channel {}", event.getMessageId(), event.getChannel().getId());
			cache = new ChannelMessageCache();
			channelCaches.put(event.getChannel(), cache);
		}

		CachedMessage msg = new CachedMessage(event.getMessage());

		synchronized (cache) {
			cache.add(msg);
		}

		for (MessageCreateHandler handler : createHandlers) {
			handler.onMessageCreated(msg, server);
		}
	}

	@Override
	public void onMessageEdit(MessageEditEvent event) {
		ChannelMessageCache cache = channelCaches.get(event.getChannel());
		if (cache == null) return;

		Instant time = Instant.now();

		synchronized (cache) {
			cache.update(event.getMessageId(), event.getNewContent(), time);
		}
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

		for (MessageDeleteHandler handler : deleteHandlers) {
			handler.onMessageDeleted(msg, server);
		}
	}

	private final class ChannelMessageCache {
		final CachedMessage[] messages = new CachedMessage[MESSAGE_LIMIT];
		private int writeIdx;
		final Long2IntMap index = new Long2IntOpenHashMap();

		public ChannelMessageCache() {
			index.defaultReturnValue(-1);
		}

		CachedMessage get(long id) {
			int pos = index.get(id);
			if (pos < 0) return null;

			return messages[pos];
		}

		boolean add(CachedMessage message) {
			long key = message.getId();
			CachedMessage prev = messages[dec(writeIdx)];
			CachedMessage replaced;

			if (prev != null && prev.compareCreationTime(message) > 0) { // last message is more recent
				prev = messages[writeIdx];

				if (prev != null && prev.compareCreationTime(message) >= 0 // first message is more recent or same age
						|| index.containsKey(key)) {
					return false;
				}

				// find insertion index (after most recent msg that isn't newer)

				int start = prev != null ? 0 : messages.length - writeIdx; // prev == null means that the buffer never looped and writeIdx is the message count
				int end = messages.length;

				while (start < end) {
					int pos = (start + end) / 2;
					CachedMessage m = messages[(pos + writeIdx) % messages.length];

					if (m == null || m.compareCreationTime(message) <= 0) {
						start = pos + 1;
					} else {
						end = pos;
					}
				}

				int insertIdx = (end + writeIdx) % messages.length;
				assert insertIdx != writeIdx;
				replaced = messages[writeIdx];

				if (insertIdx < writeIdx) {
					System.arraycopy(messages, insertIdx, messages, insertIdx + 1, writeIdx - insertIdx);
				} else {
					System.arraycopy(messages, 0, messages, 1, writeIdx);
					messages[0] = messages[messages.length - 1];
					System.arraycopy(messages, insertIdx, messages, insertIdx + 1, messages.length - insertIdx - 1);
				}

				messages[insertIdx] = message;
				index.put(key, insertIdx);
			} else {
				if (index.putIfAbsent(key, writeIdx) >= 0) return false;
				replaced = messages[writeIdx];
				messages[writeIdx] = message;
			}

			synchronized (globalIndex) {
				if (replaced != null) {
					long replacedKey = replaced.getId();
					index.remove(replacedKey);
					globalIndex.remove(replacedKey);
				}

				globalIndex.put(key, message);
			}

			writeIdx = inc(writeIdx);

			return true;
		}

		boolean update(long id, String newContent, Instant editTime) {
			Integer pos = index.get(id);
			if (pos < 0) return false;

			CachedMessage prev = messages[pos];

			if (prev.getContent().equals(newContent)) {
				return false;
			}

			CachedMessage updated = new CachedMessage(prev, newContent, editTime);

			synchronized (globalIndex) {
				globalIndex.put(id, updated);
			}

			messages[pos] = updated;

			return true;
		}

		void clear() {
			synchronized (globalIndex) {
				for (LongIterator it = index.keySet().iterator(); it.hasNext(); ) {
					long key = it.nextLong();
					globalIndex.remove(key);
				}
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

		int size() {
			if (messages[messages.length - 1] == null) {
				return writeIdx;
			} else {
				return messages.length;
			}
		}
	}

	private static int inc(int idx) {
		return (idx + 1) % MESSAGE_LIMIT;
	}

	private static int dec(int idx) {
		return (idx + MESSAGE_LIMIT - 1) % MESSAGE_LIMIT;
	}

	public interface Visitor {
		boolean visit(CachedMessage message);
	}

	public interface MessageCreateHandler {
		void onMessageCreated(CachedMessage message, Server server);
	}

	public interface MessageDeleteHandler {
		void onMessageDeleted(CachedMessage message, Server server);
	}
}
