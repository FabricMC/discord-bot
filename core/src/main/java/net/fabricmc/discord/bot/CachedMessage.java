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

import org.javacord.api.entity.DiscordEntity;
import org.javacord.api.entity.channel.TextChannel;
import org.javacord.api.entity.message.Message;
import org.javacord.api.entity.message.MessageAttachment;
import org.javacord.api.entity.message.MessageAuthor;
import org.javacord.api.entity.message.MessageType;
import org.javacord.api.entity.server.Server;
import org.javacord.api.exception.DiscordException;
import org.javacord.api.exception.NotFoundException;
import org.jetbrains.annotations.Nullable;

import net.fabricmc.discord.bot.util.DiscordUtil;

public final class CachedMessage {
	CachedMessage(Message message) {
		this.id = message.getId();
		this.type = message.getType();
		this.channelId = message.getChannel().getId();

		MessageAuthor author = message.getAuthor();
		this.authorId = author.isWebhook() ? -1 : author.getId();

		this.content = message.getContent();
		this.attachments = serializeAttachments(message.getAttachments());
		this.userMentions = serializeMentions(message.getMentionedUsers());
		this.roleMentions = serializeMentions(message.getMentionedRoles());
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

	private static CachedMessageAttachment[] serializeAttachments(List<MessageAttachment> list) {
		int size = list.size();
		if (size == 0) return emptyAttachments;

		CachedMessageAttachment[] ret = new CachedMessageAttachment[size];

		for (int i = 0; i < size; i++) {
			ret[i] = new CachedMessageAttachment(list.get(i));
		}

		return ret;
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

	public MessageType getType() {
		return type;
	}

	public Instant getCreationTime() {
		return DiscordEntity.getCreationTimestamp(id);
	}

	public long getChannelId() {
		return channelId;
	}

	public @Nullable TextChannel getChannel(Server server) {
		return DiscordUtil.getTextChannel(server, channelId);
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

		try {
			DiscordUtil.join(Message.delete(server.getApi(), channelId, id, reason));
			deleted = true;
			return true;
		} catch (NotFoundException e) {
			return false;
		}
	}

	public @Nullable Message toMessage(Server server) throws DiscordException {
		TextChannel channel = getChannel(server);
		if (channel == null) return null;

		try {
			return DiscordUtil.join(channel.getMessageById(id));
		} catch (NotFoundException e) {
			return null;
		}
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
	private final MessageType type;
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