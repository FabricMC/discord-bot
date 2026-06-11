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

import java.lang.ref.WeakReference;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

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
import org.jetbrains.annotations.Nullable;

import net.fabricmc.discord.bot.util.DiscordUtil;
import net.fabricmc.discord.bot.util.DiscordUtil.ParsedMessageLink;
import net.fabricmc.discord.io.Channel;
import net.fabricmc.discord.io.DiscordException;
import net.fabricmc.discord.io.GlobalEventHolder;
import net.fabricmc.discord.io.GlobalEventHolder.ChannelCreateHandler;
import net.fabricmc.discord.io.GlobalEventHolder.ChannelDeleteHandler;
import net.fabricmc.discord.io.GlobalEventHolder.ChannelPermissionChangeHandler;
import net.fabricmc.discord.io.GlobalEventHolder.MessageCreateHandler;
import net.fabricmc.discord.io.GlobalEventHolder.MessageDeleteHandler;
import net.fabricmc.discord.io.GlobalEventHolder.MessageEditHandler;
import net.fabricmc.discord.io.Message;
import net.fabricmc.discord.io.Permission;
import net.fabricmc.discord.io.Server;

public final class MessageIndex implements ChannelCreateHandler, ChannelDeleteHandler, ChannelPermissionChangeHandler,
MessageCreateHandler, MessageDeleteHandler, MessageEditHandler {
	private static final int INIT_LIMIT = 1000;
	private static final int MESSAGE_LIMIT = 10000;

	private static final Logger LOGGER = LogManager.getLogger(MessageIndex.class);

	private final DiscordBot bot;
	private final List<MessageCreateHandler> createHandlers = new CopyOnWriteArrayList<>();
	private final List<MessageDeleteHandler> deleteHandlers = new CopyOnWriteArrayList<>();
	// channel id -> cache
	private final Long2ObjectMap<ChannelMessageCache> channelCaches = new Long2ObjectOpenHashMap<>();
	// message id -> message
	private final Long2ObjectMap<CachedMessage> globalIndex = new Long2ObjectOpenHashMap<>();
	private volatile float initProgressPct;

	public MessageIndex(DiscordBot bot) {
		this.bot = bot;

		bot.getActiveHandler().registerReadyHandler(this::init);
		//bot.getActiveHandler().registerGoneHandler(this::reset); don't re-init everything on disconnect..
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

	public @Nullable CachedMessage get(Channel channel, long id) {
		ChannelMessageCache cache = getCache(channel, false);
		if (cache == null) return null;

		synchronized (cache) {
			return cache.get(id);
		}
	}

	public Collection<CachedMessage> getAll(Channel channel, boolean includeDeleted) {
		List<CachedMessage> ret = new ArrayList<>();

		accept(channel, msg -> {
			ret.add(msg);

			return true;
		}, includeDeleted);

		return ret;
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

	public Collection<CachedMessage> getAllByAuthor(long authorId, Channel channel, boolean includeDeleted) {
		List<CachedMessage> ret = new ArrayList<>();

		accept(channel, msg -> {
			if (msg.getAuthorDiscordId() == authorId) {
				ret.add(msg);
			}

			return true;
		}, includeDeleted);

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

	public Collection<CachedMessage> getAllByAuthors(LongSet authorDiscordIds, Channel channel, boolean includeDeleted) {
		if (authorDiscordIds.isEmpty()) return Collections.emptyList();

		List<CachedMessage> ret = new ArrayList<>();

		accept(channel, msg -> {
			if (authorDiscordIds.contains(msg.getAuthorDiscordId())) {
				ret.add(msg);
			}

			return true;
		}, includeDeleted);

		return ret;
	}

	public @Nullable CachedMessage get(String desc, @Nullable Server server) throws DiscordException {
		ParsedMessageLink linkRes = DiscordUtil.parseMessageLink(desc);

		if (linkRes != null) {
			if (!linkRes.channel().isMe() && server != null && server.getId() == linkRes.channel().channelId()) {
				Channel channel = server.getTextChannel(linkRes.channel().channelId());
				if (channel == null) return null;

				long msgId = linkRes.messageId();
				CachedMessage msg = get(channel, msgId);

				if (msg == null) {
					Message rawMsg = channel.getMessage(msgId);
					if (rawMsg != null) msg = new CachedMessage(rawMsg);
				}

				return msg;
			} else {
				return get(linkRes.messageId());
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
					if (!visitor.visit(message)) break;
				}
			}
		}
	}

	public void accept(Channel channel, Visitor visitor, boolean includeDeleted) {
		Objects.requireNonNull(channel, "null channel");

		ChannelMessageCache cache = getCache(channel, false);
		if (cache == null) return;

		synchronized (cache) {
			cache.accept(visitor, includeDeleted);
		}
	}

	private ChannelMessageCache getCache(Channel channel, boolean create) {
		Objects.requireNonNull(channel, "null channel");

		long id = channel.getId();

		synchronized (channelCaches) {
			ChannelMessageCache ret = channelCaches.get(id);

			if (ret == null && create) {
				ret = new ChannelMessageCache(channel);
				channelCaches.put(id, ret);
			}

			return ret;
		}
	}

	private void removeCache(Channel channel) {
		Objects.requireNonNull(channel, "null channel");

		long id = channel.getId();
		ChannelMessageCache cache;

		synchronized (channelCaches) {
			cache = channelCaches.remove(id);
		}

		if (cache != null) {
			cache.clear();
		}
	}

	public Collection<Channel> getCachedChannels() {
		synchronized (channelCaches) {
			List<Channel> ret = new ArrayList<>(channelCaches.size());

			for (ChannelMessageCache cache : channelCaches.values()) {
				Channel channel = cache.getChannel();
				if (channel != null) ret.add(channel);
			}

			return ret;
		}
	}

	public int getSize(Channel channel) {
		ChannelMessageCache cache = getCache(channel, false);
		if (cache == null) return 0;

		synchronized (cache) {
			return cache.size();
		}
	}

	void registerEarlyHandlers(GlobalEventHolder holder) {
		holder.registerChannelCreate(this);
		holder.registerChannelDelete(this);
		holder.registerChannelPermissionChange(this);
		holder.registerMessageCreate(this);
		holder.registerMessageDelete(this);
		holder.registerMessageEdit(this);
	}

	private void init(Server server, long lastActiveTime) {
		initProgressPct = 0;

		bot.getExecutor().execute(() -> {
			try {
				long startTime = System.nanoTime();

				LongList invalidChannels = new LongArrayList();
				Set<Channel> extraChannels = new HashSet<>(getCachedChannels());
				List<? extends Channel> channels = DiscordUtil.getTextChannels(server);
				int finished = 0;
				long lastTime = startTime;

				for (Channel channel : channels) {
					if (isValidChannel(channel)) {
						initChannel(channel);
						extraChannels.remove(channel);
					} else {
						invalidChannels.add(channel.getId());
					}

					finished++;
					initProgressPct = 100f * finished / channels.size();
					long time = System.nanoTime();

					if (time - lastTime >= 30_000_000_000L) { // 30s elapsed
						LOGGER.info("Message index init status {} / {} ({}%), {}s elapsed",
								finished, channels.size(),
								String.format("%.2f", initProgressPct),
								Math.round((time - startTime) * 1e-9));
						lastTime = time;
					}
				}

				if (!extraChannels.isEmpty())  {
					LOGGER.info("Removing now absent channels: {}", extraChannels);

					for (Channel channel : extraChannels) {
						removeCache(channel);
					}
				}

				if (!invalidChannels.isEmpty()) LOGGER.info("Skipping inaccessible channels {}", invalidChannels);

				long endTime = System.nanoTime();

				LOGGER.info("Message index initialized for {} channels in {} ms",
						channels.size(),
						String.format("%.2f", (endTime - startTime) * 1e-6));
			} catch (Throwable t) {
				LOGGER.warn("Error initializing message index", t);
			}
		});
	}

	public float getInitProgressPct() {
		return initProgressPct;
	}

	private void reset(Server server) {
		synchronized (globalIndex) {
			globalIndex.clear();
		}

		synchronized (channelCaches) {
			channelCaches.clear();
		}
	}

	private boolean isValidChannel(Channel channel) {
		if (!channel.getType().text) return false;

		return channel.canYouSee() && channel.haveYouPermission(Permission.READ_MESSAGE_HISTORY);
	}

	private void initChannel(Channel channel) {
		ChannelMessageCache cache = getCache(channel, true);

		synchronized (cache) {
			cache.updateChannel(channel);

			List<? extends Message> messages;
			CachedMessage lastMessage;

			if (!cache.initialized || (lastMessage = cache.getLast()) == null) { // first init
				messages = channel.getMessages(Math.min(INIT_LIMIT, MESSAGE_LIMIT), true);
			} else { // re-init (after disconnect etc)
				messages = channel.getMessagesBetween(lastMessage.getId(), -1, MESSAGE_LIMIT);
			}

			for (Message message : messages) {
				cache.add(new CachedMessage(message));
			}

			cache.initialized = true;
		}
	}

	@Override
	public void onChannelCreate(Channel channel) {
		if (channel.getServer() == null || channel.getServer().getId() != bot.getServerId()) return;

		if (isValidChannel(channel)) {
			initChannel(channel);
		}
	}

	@Override
	public void onChannelDelete(Channel channel) {
		if (channel.getServer() == null || channel.getServer().getId() != bot.getServerId()) return;
		if (!channel.getType().text) return;

		removeCache(channel);
	}

	@Override
	public void onChannelPermissionChange(Channel channel) {
		if (channel.getServer() == null || channel.getServer().getId() != bot.getServerId()) return;
		if (!channel.getType().text) return;

		if (isValidChannel(channel)) {
			initChannel(channel);
		} else {
			removeCache(channel);
		}
	}

	@Override
	public void onMessageCreate(Message message) {
		LOGGER.debug("Received message {} on channel {}", message.getId(), message.getChannel().getId());
		if (message.isFromWebhook()) return;

		Server server = message.getChannel().getServer();
		if (server == null) return;

		Channel channel = message.getChannel();
		ChannelMessageCache cache = getCache(channel, false);

		if (cache == null) {
			LOGGER.warn("Received message {} on unknown channel {} ({} {})", message.getId(), channel.getId(), channel.getType().name(), channel.getName());
			cache = getCache(channel, true);
		}

		CachedMessage msg = new CachedMessage(message);

		synchronized (cache) {
			cache.add(msg);
		}

		for (MessageCreateHandler handler : createHandlers) {
			handler.onMessageCreated(msg, server);
		}
	}

	@Override
	public void onMessageEdit(Message message) {
		ChannelMessageCache cache = getCache(message.getChannel(), false);
		if (cache == null) return;

		Instant time = Instant.now();

		synchronized (cache) {
			cache.update(message.getId(), message.getContent(), time);
		}
	}

	@Override
	public void onMessageDelete(long messageId, Channel channel) {
		Server server = channel.getServer();
		if (server == null) return;

		ChannelMessageCache cache = getCache(channel, false);
		if (cache == null) return;

		CachedMessage msg;

		synchronized (cache) {
			msg = cache.get(messageId);
		}

		if (msg == null) {
			/*Message message = event.getMessage().orElse(null);
			if (message == null) return;

			msg = new CachedMessage(message);*/
			return;
		}

		msg.setDeleted();

		for (MessageDeleteHandler handler : deleteHandlers) {
			handler.onMessageDeleted(msg, server);
		}
	}

	private final class ChannelMessageCache {
		private WeakReference<Channel> channelRef;
		final CachedMessage[] messages = new CachedMessage[MESSAGE_LIMIT];
		private int writeIdx;
		final Long2IntMap index = new Long2IntOpenHashMap();
		boolean initialized;

		public ChannelMessageCache(Channel channel) {
			channelRef = new WeakReference<>(channel);
			index.defaultReturnValue(-1);
		}

		Channel getChannel() {
			return channelRef.get();
		}

		void updateChannel(Channel channel) {
			Channel prev = channelRef.get();

			if (prev != channel) {
				channelRef = new WeakReference<Channel>(channel);
			}
		}

		CachedMessage get(long id) {
			int pos = index.get(id);
			if (pos < 0) return null;

			return messages[pos];
		}

		CachedMessage getLast() {
			return messages[dec(writeIdx)];
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
