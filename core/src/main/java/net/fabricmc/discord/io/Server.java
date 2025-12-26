package net.fabricmc.discord.io;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

public interface Server {
	Discord getDiscord();
	long getId();

	Channel getChannel(long id);

	default Channel getTextChannel(long id) {
		Channel ret = getChannel(id);

		return ret != null && ret.getType().text ? ret : null;
	}


	List<? extends Channel> getChannels();
	List<? extends Channel> getChannelsFiltered(Predicate<String> nameFilter);

	default List<? extends Channel> getChannelsByName(String name) {
		return getChannelsFiltered(n -> n.equals(name));
	}

	default List<? extends Channel> getChannelsByNameIgnoreCase(String name) {
		return getChannelsFiltered(n -> n.equalsIgnoreCase(name));
	}

	Member getMember(long id);

	default User getUser(long id) {
		Member ret = getMember(id);

		return ret != null ? ret.getUser() : null;
	}

	Member getMember(String username, String discriminator);
	Collection<? extends Member> getMembers();
	Collection<? extends Member> getMembersFiltered(Predicate<String> nameFilter, boolean testServerNick, boolean testGlobalNick, boolean testUsername);
	Member getYourself();

	Emoji getEmoji(long id);

	Role getEveryoneRole();
	Role getRole(long id);

	Ban getBan(long userId);
	default Ban getBan(User user) { return getBan(user.getId()); }
	static record Ban(Server server, User user, String reason) { }
	void unban(long userId, String reason);

	List<AuditLogEntry> getAuditLog(AuditLogType type, int count);
	record AuditLogEntry(long id, AuditLogType type, long actorId, User actor, long targetId, Object target, String reason) { }

	enum AuditLogTargetType {
		AUTO_MODERATION_RULE,
		CHANNEL,
		EMOJI,
		GUILD,
		INTEGRATION,
		INVITE,
		MEMBER,
		ONBOARDING,
		ONBOARDING_PROMPT_STRUCTURE,
		ROLE,
		SCHEDULED_EVENT,
		SOUNDBOARD_SOUND,
		STAGE_INSTANCE,
		STICKER,
		THREAD,
		WEBHOOK,
		OTHER
	}

	enum AuditLogType {
		GUILD_UPDATE(1, AuditLogTargetType.GUILD), // Server settings were updated
		CHANNEL_CREATE(10, AuditLogTargetType.CHANNEL), // Channel was created
		CHANNEL_UPDATE(11, AuditLogTargetType.CHANNEL), // Channel settings were updated
		CHANNEL_DELETE(12, AuditLogTargetType.CHANNEL), // Channel was deleted
		CHANNEL_OVERWRITE_CREATE(13, AuditLogTargetType.CHANNEL), // Permission overwrite was added to a channel
		CHANNEL_OVERWRITE_UPDATE(14, AuditLogTargetType.CHANNEL), // Permission overwrite was updated for a channel
		CHANNEL_OVERWRITE_DELETE(15, AuditLogTargetType.CHANNEL), // Permission overwrite was deleted from a channel
		MEMBER_KICK(20, AuditLogTargetType.MEMBER), // Member was removed from server
		MEMBER_PRUNE(21, AuditLogTargetType.MEMBER), // Members were pruned from server
		MEMBER_BAN_ADD(22, AuditLogTargetType.MEMBER), // Member was banned from server
		MEMBER_BAN_REMOVE(23, AuditLogTargetType.MEMBER), // Server ban was lifted for a member
		MEMBER_UPDATE(24, AuditLogTargetType.MEMBER), // Member was updated in server
		MEMBER_ROLE_UPDATE(25, AuditLogTargetType.MEMBER), // Member was added or removed from a role
		MEMBER_MOVE(26, AuditLogTargetType.MEMBER), // Member was moved to a different voice channel
		MEMBER_DISCONNECT(27, AuditLogTargetType.MEMBER), // Member was disconnected from a voice channel
		BOT_ADD(28, AuditLogTargetType.MEMBER), // Bot user was added to server
		ROLE_CREATE(30, AuditLogTargetType.ROLE), // Role was created
		ROLE_UPDATE(31, AuditLogTargetType.ROLE), // Role was edited
		ROLE_DELETE(32, AuditLogTargetType.ROLE), // Role was deleted
		INVITE_CREATE(40, AuditLogTargetType.INVITE), // Server invite was created
		INVITE_UPDATE(41, AuditLogTargetType.INVITE), // Server invite was updated
		INVITE_DELETE(42, AuditLogTargetType.INVITE), // Server invite was deleted
		WEBHOOK_CREATE(50, AuditLogTargetType.WEBHOOK), // Webhook was created
		WEBHOOK_UPDATE(51, AuditLogTargetType.WEBHOOK), // Webhook properties or channel were updated
		WEBHOOK_DELETE(52, AuditLogTargetType.WEBHOOK), // Webhook was deleted
		EMOJI_CREATE(60, AuditLogTargetType.EMOJI), // Emoji was created
		EMOJI_UPDATE(61, AuditLogTargetType.EMOJI), // Emoji name was updated
		EMOJI_DELETE(62, AuditLogTargetType.EMOJI), // Emoji was deleted
		MESSAGE_DELETE(72, AuditLogTargetType.OTHER), // Single message was deleted
		MESSAGE_BULK_DELETE(73, AuditLogTargetType.OTHER), // Multiple messages were deleted
		MESSAGE_PIN(74, AuditLogTargetType.OTHER), // Message was pinned to a channel
		MESSAGE_UNPIN(75, AuditLogTargetType.OTHER), // Message was unpinned from a channel
		INTEGRATION_CREATE(80, AuditLogTargetType.INTEGRATION), // App was added to server
		INTEGRATION_UPDATE(81, AuditLogTargetType.INTEGRATION), // App was updated (as an example, its scopes were updated)
		INTEGRATION_DELETE(82, AuditLogTargetType.INTEGRATION), // App was removed from server
		STAGE_INSTANCE_CREATE(83, AuditLogTargetType.STAGE_INSTANCE), // Stage instance was created (stage channel becomes live)
		STAGE_INSTANCE_UPDATE(84, AuditLogTargetType.STAGE_INSTANCE), // Stage instance details were updated
		STAGE_INSTANCE_DELETE(85, AuditLogTargetType.STAGE_INSTANCE), // Stage instance was deleted (stage channel no longer live)
		STICKER_CREATE(90, AuditLogTargetType.STICKER), // Sticker was created
		STICKER_UPDATE(91, AuditLogTargetType.STICKER), // Sticker details were updated
		STICKER_DELETE(92, AuditLogTargetType.STICKER), // Sticker was deleted
		GUILD_SCHEDULED_EVENT_CREATE(100, AuditLogTargetType.SCHEDULED_EVENT), // Event was created
		GUILD_SCHEDULED_EVENT_UPDATE(101, AuditLogTargetType.SCHEDULED_EVENT), // Event was updated
		GUILD_SCHEDULED_EVENT_DELETE(102, AuditLogTargetType.SCHEDULED_EVENT), // Event was cancelled
		THREAD_CREATE(110, AuditLogTargetType.THREAD), // Thread was created in a channel
		THREAD_UPDATE(111, AuditLogTargetType.THREAD), // Thread was updated
		THREAD_DELETE(112, AuditLogTargetType.THREAD), // Thread was deleted
		APPLICATION_COMMAND_PERMISSION_UPDATE(121, AuditLogTargetType.INTEGRATION), // Permissions were updated for a command
		SOUNDBOARD_SOUND_CREATE(130, AuditLogTargetType.SOUNDBOARD_SOUND), // Soundboard sound was created
		SOUNDBOARD_SOUND_UPDATE(131, AuditLogTargetType.SOUNDBOARD_SOUND), // Soundboard sound was updated
		SOUNDBOARD_SOUND_DELETE(132, AuditLogTargetType.SOUNDBOARD_SOUND), // Soundboard sound was deleted
		AUTO_MODERATION_RULE_CREATE(140, AuditLogTargetType.AUTO_MODERATION_RULE), // Auto Moderation rule was created
		AUTO_MODERATION_RULE_UPDATE(141, AuditLogTargetType.AUTO_MODERATION_RULE), // Auto Moderation rule was updated
		AUTO_MODERATION_RULE_DELETE(142, AuditLogTargetType.AUTO_MODERATION_RULE), // Auto Moderation rule was deleted
		AUTO_MODERATION_BLOCK_MESSAGE(143, AuditLogTargetType.OTHER), // Message was blocked by Auto Moderation
		AUTO_MODERATION_FLAG_TO_CHANNEL(144, AuditLogTargetType.OTHER), // Message was flagged by Auto Moderation
		AUTO_MODERATION_USER_COMMUNICATION_DISABLED(145, AuditLogTargetType.OTHER), // Member was timed out by Auto Moderation
		CREATOR_MONETIZATION_REQUEST_CREATED(150, AuditLogTargetType.OTHER), // Creator monetization request was created
		CREATOR_MONETIZATION_TERMS_ACCEPTED(151, AuditLogTargetType.OTHER), // Creator monetization terms were accepted
		ONBOARDING_PROMPT_CREATE(163, AuditLogTargetType.ONBOARDING_PROMPT_STRUCTURE), // Guild Onboarding Question was created
		ONBOARDING_PROMPT_UPDATE(164, AuditLogTargetType.ONBOARDING_PROMPT_STRUCTURE), // Guild Onboarding Question was updated
		ONBOARDING_PROMPT_DELETE(165, AuditLogTargetType.ONBOARDING_PROMPT_STRUCTURE), // Guild Onboarding Question was deleted
		ONBOARDING_CREATE(166, AuditLogTargetType.ONBOARDING), // Guild Onboarding was created
		ONBOARDING_UPDATE(167, AuditLogTargetType.ONBOARDING), // Guild Onboarding was updated
		HOME_SETTINGS_CREATE(190, AuditLogTargetType.OTHER), // Guild Server Guide was created
		HOME_SETTINGS_UPDATE(191, AuditLogTargetType.OTHER), // Guild Server Guide was updated
		OTHER(-1, AuditLogTargetType.OTHER);

		private static final Map<Integer, AuditLogType> INDEX = new HashMap<>();

		public final int id;
		public final AuditLogTargetType targetType;

		AuditLogType(int id, AuditLogTargetType targetType) {
			this.id = id;
			this.targetType = targetType;
		}

		public static AuditLogType fromId(int id) {
			return INDEX.getOrDefault(id, OTHER);
		}

		static {
			for (AuditLogType type : values()) {
				if (type != OTHER) INDEX.put(type.id, type);
			}
		}
	}

	boolean hasAllMembersInCache();
}
