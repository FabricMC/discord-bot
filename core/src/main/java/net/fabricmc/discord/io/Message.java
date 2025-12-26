package net.fabricmc.discord.io;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

public interface Message {
	Channel getChannel();
	long getId();
	Type getType();

	public enum Type {
		DEFAULT(0, false, true),
		RECIPIENT_ADD(1, true, false),
		RECIPIENT_REMOVE(2, true, false),
		CALL(3, true, false),
		CHANNEL_NAME_CHANGE(4, true, false),
		CHANNEL_ICON_CHANGE(5, true, false),
		CHANNEL_PINNED_MESSAGE(6, true, true),
		USER_JOIN(7, true, true),
		GUILD_BOOST(8, true, true),
		GUILD_BOOST_TIER_1(9, true, true),
		GUILD_BOOST_TIER_2(10, true, true),
		GUILD_BOOST_TIER_3(11, true, true),
		CHANNEL_FOLLOW_ADD(12, true, true),
		GUILD_DISCOVERY_DISQUALIFIED(14, true, true),
		GUILD_DISCOVERY_REQUALIFIED(15, true, true),
		GUILD_DISCOVERY_GRACE_PERIOD_INITIAL_WARNING(16,true,  true),
		GUILD_DISCOVERY_GRACE_PERIOD_FINAL_WARNING(17, true, true),
		THREAD_CREATED(18, true, true),
		REPLY(19, false, true),
		CHAT_INPUT_COMMAND(20, false, true),
		THREAD_STARTER_MESSAGE(21, false, false),
		GUILD_INVITE_REMINDER(22, true, true),
		CONTEXT_MENU_COMMAND(23, false, true),
		AUTO_MODERATION_ACTION(24, true, true),
		ROLE_SUBSCRIPTION_PURCHASE(25, true, true),
		INTERACTION_PREMIUM_UPSELL(26, true, true),
		STAGE_START(27, true, true),
		STAGE_END(28, true, true),
		STAGE_SPEAKER(29, true, true),
		STAGE_TOPIC(31, true, true),
		GUILD_APPLICATION_PREMIUM_SUBSCRIPTION(32, true, true),
		GUILD_INCIDENT_ALERT_MODE_ENABLED(36, true, true),
		GUILD_INCIDENT_ALERT_MODE_DISABLED(37, true, true),
		GUILD_INCIDENT_REPORT_RAID(38, true, true),
		GUILD_INCIDENT_REPORT_FALSE_ALARM(39, true, true),
		PURCHASE_NOTIFICATION(44, true, true),
		POLL_RESULT(46, true, true),
		OTHER(-1, true, false);

		public static final Type[] VALUES = values();
		private static final Type[] INDEX;

		public final int id;
		public final boolean system;
		public final boolean deletable;

		Type(int id, boolean system, boolean deletable) {
			this.id = id;
			this.system = system;
			this.deletable = deletable;
		}

		public static Type get(int id) {
			if (id >= 0 && id < INDEX.length) {
				return INDEX[id];
			} else {
				return OTHER;
			}
		}

		static {
			INDEX = new Type[VALUES[VALUES.length - 2].id + 2];
			Arrays.fill(INDEX, OTHER);

			for (Type t : VALUES) {
				INDEX[t.id] = t;
			}
		}
	}

	User getAuthor();
	boolean isFromWebhook();
	String getContent();
	Instant getLastEditTime();
	Message getReferencedMessage();
	List<? extends MessageAttachment> getAttachments();
	List<? extends MessageEmbed> getEmbeds();
	List<? extends User> getMentionedUsers();
	List<? extends Role> getMentionedRoles();
	boolean hasEveryoneMentions();

	void addReaction(Emoji emote);

	default void addReactions(List<Emoji> emotes) {
		for (Emoji emote : emotes) {
			addReaction(emote);
		}
	}

	void removeReaction(Emoji emote, User user);

	default void removeReaction(Emoji emote, long userId) {
		removeReaction(emote, getChannel().getDiscord().getUser(userId, true));
	}

	void removeAllReactions();
	void crosspost();
	void delete(String reason);

	Message edit(String content);
	Message edit(MessageEmbed embed);

	public record AllowedMentions(boolean allUsers, boolean allRoles, boolean everyone,
			Collection<? extends User> users, Collection<? extends Role> roles,
			boolean repliedUser) {
		public static AllowedMentions ofNone() {
			return new AllowedMentions(false, false, false, Collections.emptyList(), Collections.emptyList(), false);
		}

		public static AllowedMentions ofEveryoneAndHere() {
			return new AllowedMentions(false, false, true, Collections.emptyList(), Collections.emptyList(), false);
		}
	}

	default AllowedMentions getAllowedMentions() { // builder only
		return null;
	}

	public static class Builder {
		private String content;
		private final List<MessageAttachment> attachments = new ArrayList<>();
		private final List<MessageEmbed> embeds = new ArrayList<>();
		private AllowedMentions allowedMentions;

		public Builder content(String content) {
			this.content = content;

			return this;
		}

		public Builder attachment(MessageAttachment attachment) {
			attachments.add(attachment);

			return this;
		}

		public Builder embed(MessageEmbed attachment) {
			embeds.add(attachment);

			return this;
		}

		public Builder allowedMentions(AllowedMentions mentions) {
			allowedMentions = mentions;

			return this;
		}

		public Builder noAllowedMentions() {
			allowedMentions = AllowedMentions.ofNone();

			return this;
		}

		public Message build() {
			return new BuiltMessage(content,
					attachments, embeds,
					allowedMentions);
		}
	}

	static class BuiltMessage implements Message {
		private final String content;
		private final List<? extends MessageAttachment> attachments;
		private final List<? extends MessageEmbed> embeds;
		private final AllowedMentions allowedMentions;

		BuiltMessage(String content,
				List<? extends MessageAttachment> attachments, List<? extends MessageEmbed> embeds,
				AllowedMentions allowedMentions) {
			this.content = content;
			this.attachments = attachments;
			this.embeds = embeds;
			this.allowedMentions = allowedMentions;
		}

		@Override
		public Channel getChannel() {
			throw new UnsupportedOperationException();
		}

		@Override
		public long getId() {
			throw new UnsupportedOperationException();
		}

		@Override
		public Type getType() {
			return Type.DEFAULT;
		}

		@Override
		public User getAuthor() {
			throw new UnsupportedOperationException();
		}

		@Override
		public boolean isFromWebhook() {
			return false;
		}

		@Override
		public String getContent() {
			return content;
		}

		@Override
		public Instant getLastEditTime() {
			return null;
		}

		@Override
		public Message getReferencedMessage() {
			return null;
		}

		@Override
		public List<? extends MessageAttachment> getAttachments() {
			return attachments;
		}

		@Override
		public List<? extends MessageEmbed> getEmbeds() {
			return embeds;
		}

		@Override
		public List<? extends User> getMentionedUsers() {
			return Collections.emptyList();
		}

		@Override
		public List<? extends Role> getMentionedRoles() {
			return Collections.emptyList();
		}

		@Override
		public boolean hasEveryoneMentions() {
			return false;
		}

		@Override
		public void addReaction(Emoji emote) {
			throw new UnsupportedOperationException();
		}

		@Override
		public void removeReaction(Emoji emote, User user) {
			throw new UnsupportedOperationException();
		}

		@Override
		public void removeAllReactions() {
			throw new UnsupportedOperationException();
		}

		@Override
		public void crosspost() {
			throw new UnsupportedOperationException();
		}

		@Override
		public void delete(String reason) {
			throw new UnsupportedOperationException();
		}

		@Override
		public Message edit(String content) {
			throw new UnsupportedOperationException();
		}

		@Override
		public Message edit(MessageEmbed embed) {
			throw new UnsupportedOperationException();
		}

		@Override
		public AllowedMentions getAllowedMentions() {
			return allowedMentions;
		}
	}
}
