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
import java.util.List;

import org.javacord.api.entity.DiscordEntity;
import org.javacord.api.entity.channel.TextChannel;
import org.javacord.api.entity.message.Message;
import org.javacord.api.entity.message.MessageAuthor;
import org.javacord.api.entity.server.Server;
import org.javacord.api.exception.DiscordException;
import org.javacord.api.exception.NotFoundException;
import org.jetbrains.annotations.Nullable;

import net.fabricmc.discord.bot.util.DiscordUtil;

public final class CachedMessage {
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