/*
 * Copyright (c) 2026 FabricMC
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

package net.fabricmc.discord.ioimpl.jda;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

import net.dv8tion.jda.api.entities.Message.MentionType;
import net.dv8tion.jda.api.requests.RestAction;
import net.dv8tion.jda.api.utils.FileUpload;
import net.dv8tion.jda.api.utils.messages.AbstractMessageBuilder;
import net.dv8tion.jda.api.utils.messages.MessageCreateBuilder;
import net.dv8tion.jda.api.utils.messages.MessageCreateData;

import net.fabricmc.discord.io.DiscordImplUtil;
import net.fabricmc.discord.io.Emoji;
import net.fabricmc.discord.io.Message;
import net.fabricmc.discord.io.MessageAttachment;
import net.fabricmc.discord.io.MessageEmbed;
import net.fabricmc.discord.io.Role;
import net.fabricmc.discord.io.User;

public class MessageImpl implements Message {
	private final net.dv8tion.jda.api.entities.Message wrapped;
	private final ChannelImpl channel;
	private final UserImpl author;

	MessageImpl(net.dv8tion.jda.api.entities.Message wrapped, ChannelImpl channel, UserImpl author) {
		this.wrapped = wrapped;
		this.channel = channel;
		this.author = author;
	}

	@Override
	public ChannelImpl getChannel() {
		return channel;
	}

	@Override
	public long getId() {
		return wrapped.getIdLong();
	}

	@Override
	public Type getType() {
		return Type.get(wrapped.getType().getId());
	}

	@Override
	public UserImpl getAuthor() {
		return author;
	}

	@Override
	public boolean isFromWebhook() {
		return wrapped.isWebhookMessage();
	}

	@Override
	public String getContent() {
		return wrapped.getContentRaw();
	}

	@Override
	public Instant getLastEditTime() {
		OffsetDateTime res = wrapped.getTimeEdited();

		return res != null ? res.toInstant() : null;
	}

	@Override
	public Message getReferencedMessage() {
		net.dv8tion.jda.api.entities.Message res = wrapped.getReferencedMessage();

		return res != null ? wrap(res, ChannelImpl.wrap(res.getChannel(), this.channel)) : null;
	}

	@Override
	public List<MessageAttachmentImpl> getAttachments() {
		return DiscordImplUtil.wrap(wrapped.getAttachments(), r -> MessageAttachmentImpl.wrap(r));
	}

	@Override
	public List<MessageEmbedImpl> getEmbeds() {
		return DiscordImplUtil.wrap(wrapped.getEmbeds(), r -> MessageEmbedImpl.wrap(r));
	}

	@Override
	public void addReaction(Emoji emote) {
		wrapped.addReaction(EmojiImpl.unwrap(emote)).complete();
	}

	@Override
	public void removeAllReactions() {
		wrapped.clearReactions().complete();
	}

	@Override
	public List<UserImpl> getMentionedUsers() {
		return DiscordImplUtil.wrap(wrapped.getMentions().getUsers(), r -> UserImpl.wrap(r, channel.getDiscord()));
	}

	@Override
	public List<RoleImpl> getMentionedRoles() {
		return DiscordImplUtil.wrap(wrapped.getMentions().getRoles(), r -> RoleImpl.wrap(r, channel.getDiscord(), null));
	}

	@Override
	public boolean hasEveryoneMentions() {
		return wrapped.getMentions().mentionsEveryone();
	}

	@Override
	public void addReactions(List<Emoji> emotes) {
		List<RestAction<Void>> actions = new ArrayList<>(emotes.size());

		for (Emoji emote : emotes) {
			actions.add(wrapped.addReaction(EmojiImpl.unwrap(emote)));
		}

		RestAction.allOf(actions).complete();
	}

	@Override
	public void removeReaction(Emoji emote, User user) {
		wrapped.removeReaction(EmojiImpl.unwrap(emote), ((UserImpl) user).unwrap()).complete();
	}

	@Override
	public void crosspost() {
		wrapped.crosspost().complete();
	}

	@Override
	public void delete(String reason) {
		wrapped.delete().complete();
	}

	@Override
	public Message edit(String content) {
		return MessageImpl.wrap(wrapped.editMessage(content).complete(), channel);
	}

	@Override
	public Message edit(MessageEmbed embed) {
		return MessageImpl.wrap(wrapped.editMessageEmbeds(MessageEmbedImpl.toBuilder(embed).build()).complete(), channel);
	}

	static MessageImpl wrap(net.dv8tion.jda.api.entities.Message message, ChannelImpl channel) {
		if (message == null) return null;

		// TODO: handle webhook user
		return new MessageImpl(message, channel, UserImpl.wrap(message.getAuthor(), channel.getDiscord()));
	}

	net.dv8tion.jda.api.entities.Message unwrap() {
		return wrapped;
	}

	static MessageCreateData toCreateData(Message message) {
		MessageCreateBuilder ret = new MessageCreateBuilder()
				.setContent(message.getContent());

		for (MessageAttachment attachment : message.getAttachments()) {
			FileUpload upload;

			if (attachment.hasBytesReady()) {
				upload = FileUpload.fromData(attachment.getBytes(), attachment.getFileName());
			} else {
				upload = FileUpload.fromData(attachment.getInputStream(), attachment.getFileName());
			}

			upload.setDescription(attachment.getDescription());
			ret.addFiles(upload);
		}

		for (MessageEmbed embed : message.getEmbeds()) {
			ret.addEmbeds(MessageEmbedImpl.toBuilder(embed).build());
		}

		if (message.getAllowedMentions() != null) {
			applyAllowedMentions(message.getAllowedMentions(), ret);
		}

		return ret.build();
	}

	private static void applyAllowedMentions(AllowedMentions mentions, AbstractMessageBuilder<?, ?> builder) {
		List<net.dv8tion.jda.api.entities.Message.MentionType> mentionTypes = new ArrayList<>();

		if (mentions.allUsers()) {
			mentionTypes.add(MentionType.USER);
		} else if (!mentions.users().isEmpty()) {
			long[] ids = new long[mentions.users().size()];
			int idx = 0;

			for (User user : mentions.users()) {
				ids[idx++] = user.getId();
			}

			builder.mentionUsers(ids);
		}

		if (mentions.allRoles()) {
			mentionTypes.add(MentionType.ROLE);
		} else if (!mentions.roles().isEmpty()) {
			long[] ids = new long[mentions.roles().size()];
			int idx = 0;

			for (Role role : mentions.roles()) {
				ids[idx++] = role.getId();
			}

			builder.mentionRoles(ids);
		}

		if (mentions.everyone()) {
			mentionTypes.add(MentionType.EVERYONE);
			mentionTypes.add(MentionType.HERE);
		}

		if (mentions.repliedUser()) {
			builder.mentionRepliedUser(true);
		}

		builder.setAllowedMentions(mentionTypes);
	}
}
