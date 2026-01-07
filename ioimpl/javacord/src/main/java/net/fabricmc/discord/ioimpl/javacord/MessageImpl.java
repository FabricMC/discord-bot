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

package net.fabricmc.discord.ioimpl.javacord;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.javacord.api.entity.message.MessageBuilder;
import org.javacord.api.entity.message.MessageType;
import org.javacord.api.entity.message.mention.AllowedMentionsBuilder;

import net.fabricmc.discord.io.DiscordImplUtil;
import net.fabricmc.discord.io.Emoji;
import net.fabricmc.discord.io.Message;
import net.fabricmc.discord.io.MessageAttachment;
import net.fabricmc.discord.io.MessageEmbed;
import net.fabricmc.discord.io.Role;
import net.fabricmc.discord.io.User;
import net.fabricmc.discord.io.Wrapper;

public class MessageImpl implements Message {
	private static final Wrapper<org.javacord.api.entity.message.Message, MessageImpl> WRAPPER = new Wrapper<>();

	private final org.javacord.api.entity.message.Message wrapped;
	private final ChannelImpl channel;
	private final UserImpl author;

	MessageImpl(org.javacord.api.entity.message.Message wrapped, ChannelImpl channel, UserImpl author) {
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
		return wrapped.getId();
	}

	@Override
	public Type getType() {
		MessageType res = wrapped.getType();
		if (res == MessageType.NORMAL_WEBHOOK) return Type.DEFAULT;
		if (res == MessageType.UNKNOWN) return Type.OTHER;

		for (Type type : Type.VALUES) {
			if (MessageType.byType(type.id, false) == res) {
				return type;
			}
		}

		return Type.OTHER;
	}


	@Override
	public UserImpl getAuthor() {
		return author;
	}

	@Override
	public boolean isFromWebhook() {
		return wrapped.getAuthor().isWebhook();
	}

	@Override
	public String getContent() {
		return wrapped.getContent();
	}

	@Override
	public Instant getLastEditTime() {
		return wrapped.getLastEditTimestamp().orElse(null);
	}

	@Override
	public Message getReferencedMessage() {
		org.javacord.api.entity.message.Message res = wrapped.getReferencedMessage().orElse(null);

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
	public List<UserImpl> getMentionedUsers() {
		return DiscordImplUtil.wrap(wrapped.getMentionedUsers(), r -> UserImpl.wrap(r, channel.getDiscord()));
	}

	@Override
	public List<RoleImpl> getMentionedRoles() {
		return DiscordImplUtil.wrap(wrapped.getMentionedRoles(), r -> RoleImpl.wrap(r, channel.getDiscord(), null));
	}

	@Override
	public boolean hasEveryoneMentions() {
		return wrapped.mentionsEveryone();
	}

	@Override
	public void addReaction(Emoji emote) {
		CompletableFuture<Void> future;

		if (emote.isCustom()) {
			if (emote instanceof EmojiImpl e) {
				future = wrapped.addReaction(e.unwrap());
			} else {
				throw new UnsupportedOperationException();
			}
		} else {
			future = wrapped.addReaction(emote.getName());
		}

		future.join();
	}

	@Override
	public void addReactions(List<Emoji> emotes) {
		// slice emotes into consecutives blocks of either unicode or custom emojis, submit each block and get the overall result
		List<CompletableFuture<Void>> futures = new ArrayList<>();
		int start = 0;
		boolean wasCustom = false;

		for (int i = 0; i <= emotes.size(); i++) {
			Emoji emote;
			boolean custom;

			if (i < emotes.size()) {
				emote = emotes.get(i);
				custom = emote.isCustom();
			} else {
				emote = null;
				custom = !wasCustom;
			}

			if (i == 0) {
				wasCustom = custom;
			} else if (custom != wasCustom) {
				int size = i - start;

				if (wasCustom) {
					org.javacord.api.entity.emoji.Emoji[] items = new org.javacord.api.entity.emoji.Emoji[size];

					for (int j = 0; j < size; j++) {
						emote = emotes.get(j + start);

						if (emote instanceof EmojiImpl e) {
							items[j] = e.unwrap();
						} else {
							throw new UnsupportedOperationException();
						}
					}

					futures.add(wrapped.addReactions(items));
				} else {
					String[] items = new String[size];

					for (int j = 0; j < size; j++) {
						emote = emotes.get(j + start);
						items[j] = emote.getName();
					}

					futures.add(wrapped.addReactions(items));
				}

				start = i;
				wasCustom = custom;
			}
		}

		CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
	}

	@Override
	public void removeReaction(Emoji emote, User user) {
		org.javacord.api.entity.user.User rawUser = ((UserImpl) user).unwrap();
		CompletableFuture<Void> future;

		if (emote.isCustom()) {
			if (emote instanceof EmojiImpl e) {
				future = wrapped.removeReactionByEmoji(rawUser, e.unwrap());
			} else {
				throw new UnsupportedOperationException();
			}
		} else {
			future = wrapped.removeReactionByEmoji(rawUser, emote.getName());
		}

		future.join();
	}

	@Override
	public void removeAllReactions() {
		wrapped.removeAllReactions().join();
	}

	@Override
	public void crosspost() {
		wrapped.crossPost().join();
	}

	@Override
	public void delete(String reason) {
		wrapped.delete(reason).join();
	}

	@Override
	public Message edit(String content) {
		return MessageImpl.wrap(wrapped.edit(content).join(), channel);
	}

	@Override
	public MessageImpl edit(MessageEmbed embed) {
		return MessageImpl.wrap(wrapped.edit(MessageEmbedImpl.toBuilder(embed)).join(), channel);
	}

	static MessageImpl wrap(org.javacord.api.entity.message.Message message, ChannelImpl channel) {
		if (message == null) return null;

		// TODO: handle webhook user
		return WRAPPER.wrap(message, m -> new MessageImpl(m, channel, UserImpl.wrap(m.getAuthor().asUser().orElse(null), channel.getDiscord())));
	}

	org.javacord.api.entity.message.Message unwrap() {
		return wrapped;
	}

	static MessageBuilder toBuilder(Message message) {
		MessageBuilder ret = new MessageBuilder()
				.setContent(message.getContent());

		for (MessageAttachment attachment : message.getAttachments()) {
			if (attachment.hasBytesReady()) {
				ret.addAttachment(attachment.getBytes(), attachment.getFileName(), attachment.getDescription());
			} else {
				ret.addAttachment(attachment.getInputStream(), attachment.getFileName(), attachment.getDescription());
			}
		}

		for (MessageEmbed embed : message.getEmbeds()) {
			ret.addEmbed(MessageEmbedImpl.toBuilder(embed));
		}

		if (message.getAllowedMentions() != null) {
			AllowedMentions mentions = message.getAllowedMentions();
			AllowedMentionsBuilder builder = new AllowedMentionsBuilder();

			if (mentions.allUsers()) {
				builder.setMentionUsers(true);
			} else {
				for (User user : mentions.users()) {
					builder.addUser(user.getId());
				}
			}

			if (mentions.allRoles()) {
				builder.setMentionRoles(true);
			} else {
				for (Role role : mentions.roles()) {
					builder.addRole(role.getId());
				}
			}

			if (mentions.everyone()) {
				builder.setMentionEveryoneAndHere(true);
			}

			if (mentions.repliedUser()) {
				builder.setMentionRepliedUser(true);
			}

			ret.setAllowedMentions(builder.build());
		}

		return ret;
	}
}
