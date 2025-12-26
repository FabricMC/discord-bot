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
import java.util.List;
import java.util.function.ToLongFunction;

import org.jetbrains.annotations.Nullable;

import net.fabricmc.discord.bot.util.DiscordUtil;
import net.fabricmc.discord.io.Channel;
import net.fabricmc.discord.io.DiscordException;
import net.fabricmc.discord.io.Message;
import net.fabricmc.discord.io.MessageAttachment;
import net.fabricmc.discord.io.Role;
import net.fabricmc.discord.io.Server;
import net.fabricmc.discord.io.User;

public final class CachedMessage {
	CachedMessage(Message message) {
		this.id = message.getId();
		this.type = message.getType();
		this.channelId = message.getChannel().getId();

		User author = message.getAuthor();
		this.authorId = message.isFromWebhook() ? -1 : author.getId();

		this.content = message.getContent();
		this.attachments = serializeAttachments(message.getAttachments());
		this.userMentions = serializeMentions(message.getMentionedUsers(), User::getId);
		this.roleMentions = serializeMentions(message.getMentionedRoles(), Role::getId);
		this.prev = null;
		this.editTime = null;
	}

	CachedMessage(CachedMessage prev, String newContent, Instant editTime) {
		this.id = prev.id;
		this.type = prev.type;
		this.channelId = prev.channelId;
		this.authorId = prev.authorId;
		this.content = newContent.equals(prev.content) ? prev.content : newContent;
		this.attachments = prev.attachments;
		this.userMentions = prev.userMentions;
		this.roleMentions = prev.roleMentions;
		this.prev = prev;
		this.editTime = editTime;
	}

	private static CachedMessageAttachment[] serializeAttachments(List<? extends MessageAttachment> list) {
		int size = list.size();
		if (size == 0) return emptyAttachments;

		CachedMessageAttachment[] ret = new CachedMessageAttachment[size];

		for (int i = 0; i < size; i++) {
			ret[i] = new CachedMessageAttachment(list.get(i));
		}

		return ret;
	}

	private static <T> long[] serializeMentions(List<T> list, ToLongFunction<T> serializer) {
		int size = list.size();
		if (size == 0) return emptyMentions;

		long[] ret = new long[size];

		for (int i = 0; i < size; i++) {
			ret[i] = serializer.applyAsLong(list.get(i));
		}

		return ret;
	}

	public long getId() {
		return id;
	}

	public Message.Type getType() {
		return type;
	}

	public Instant getCreationTime() {
		return DiscordUtil.getCreationTime(id);
	}

	public long getChannelId() {
		return channelId;
	}

	public @Nullable Channel getChannel(Server server) {
		return server.getTextChannel(channelId);
	}

	public long getAuthorDiscordId() {
		return authorId;
	}

	public String getContent() {
		return content;
	}

	public CachedMessageAttachment[] getAttachments() {
		return attachments;
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

	public boolean delete(Server server, String reason) throws DiscordException {
		if (isDeleted()) return true;

		Channel channel = getChannel(server);
		if (channel == null) return false;

		channel.deleteMessage(id, reason); // TODO: catch exc and return false
		deleted = true;

		return true;
	}

	public @Nullable Message toMessage(Server server) throws DiscordException {
		Channel channel = getChannel(server);
		if (channel == null) return null;

		return channel.getMessage(id); // TODO: catch exc and return null
	}

	/**
	 * @return <0 / 0 / >0 for this being older / same / newer
	 */
	public int compareCreationTime(CachedMessage o) {
		return Long.compareUnsigned(id >>> 22, o.id >>> 22);
	}

	private static final CachedMessageAttachment[] emptyAttachments = new CachedMessageAttachment[0];
	private static final long[] emptyMentions = new long[0];

	private final long id;
	private final Message.Type type;
	private final long channelId;
	private final long authorId;
	private final String content;
	private final CachedMessageAttachment[] attachments;
	private final long[] userMentions;
	private final long[] roleMentions;
	private volatile boolean deleted;

	final CachedMessage prev;
	final Instant editTime;
}