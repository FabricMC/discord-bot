package net.fabricmc.discord.io;

import java.util.Collection;
import java.util.EnumSet;
import java.util.Set;

public enum Permission {
	CREATE_INSTANT_INVITE(0, false, true, true, true), // Allows creation of instant invites
	KICK_MEMBERS(1, true, false, false, false), // Allows kicking members
	BAN_MEMBERS(2, true, false, false, false), // Allows banning members
	ADMINISTRATOR(3, true, false, false, false), // Allows all permissions and bypasses channel permission overwrites
	MANAGE_CHANNELS(4, true, true, true, true), // Allows management and editing of channels T, V, S
	MANAGE_GUILD(5, true, false, false, false), // Allows management and editing of the guild
	ADD_REACTIONS(6, false, true, true, true), // Allows for adding new reactions to messages. This permission does not apply to reacting with an existing reaction on a message.
	VIEW_AUDIT_LOG(7, false, false, false, false), // Allows for viewing of audit logs
	PRIORITY_SPEAKER(8, false, false, true, false), // Allows for using priority speaker in a voice channel
	STREAM(9, false, false, true, true), // Allows the user to go live
	VIEW_CHANNEL(10, false, true, true, true), // Allows guild members to view a channel, which includes reading messages in text channels and joining voice channels
	SEND_MESSAGES(11, false, true, true, true), // Allows for sending messages in a channel and creating threads in a forum (does not allow sending messages in threads)
	SEND_TTS_MESSAGES(12, false, true, true, true), // Allows for sending of /tts messages
	MANAGE_MESSAGES(13, true, true, true, true), // Allows for deletion of other users messages
	EMBED_LINKS(14, false, true, true, true), // Links sent by users with this permission will be auto-embedded
	ATTACH_FILES(15, false, true, true, true), // Allows for uploading images and files
	READ_MESSAGE_HISTORY(16, false, true, true, true), // Allows for reading of message history
	MENTION_EVERYONE(17, false, true, true, true), // Allows for using the @everyone tag to notify all users in a channel, and the @here tag to notify all online users in a channel
	USE_EXTERNAL_EMOJIS(18, false, true, true, true), // Allows the usage of custom emojis from other servers
	VIEW_GUILD_INSIGHTS(19, false, false, false, false), // Allows for viewing guild insights
	CONNECT(20, false, false, true, true), // Allows for joining of a voice channel
	SPEAK(21, false, false, true, false), // Allows for speaking in a voice channel
	MUTE_MEMBERS(22, false, false, true, true), // Allows for muting members in a voice channel
	DEAFEN_MEMBERS(23, false, false, true, false), // Allows for deafening of members in a voice channel
	MOVE_MEMBERS(24, false, false, true, true), // Allows for moving of members between voice channels
	USE_VAD(25, false, false, true, false), // Allows for using voice-activity-detection in a voice channel
	CHANGE_NICKNAME(26, false, false, false, false), // Allows for modification of own nickname
	MANAGE_NICKNAMES(27, false, false, false, false), // Allows for modification of other users nicknames
	MANAGE_ROLES(28, true, true, true, true), // Allows management and editing of roles
	MANAGE_WEBHOOKS(29, true, true, true, true), // Allows management and editing of webhooks
	MANAGE_GUILD_EXPRESSIONS(30, true, false, false, false), // Allows for editing and deleting emojis, stickers, and soundboard sounds created by all users
	USE_APPLICATION_COMMANDS(31, false, true, true, true), // Allows members to use application commands, including slash commands and context menu commands.
	REQUEST_TO_SPEAK(32, false, false, false, true), // Allows for requesting to speak in stage channels. (This permission is under active development and may be changed or removed.)
	MANAGE_EVENTS(33, false, false, true, true), // Allows for editing and deleting scheduled events created by all users
	MANAGE_THREADS(34, true, true, false, false), // Allows for deleting and archiving threads, and viewing all private threads
	CREATE_PUBLIC_THREADS(35, false, true, false, false), // Allows for creating public and announcement threads
	CREATE_PRIVATE_THREADS(36, false, true, false, false), // Allows for creating private threads
	USE_EXTERNAL_STICKERS(37, false, true, true, true), // Allows the usage of custom stickers from other servers
	SEND_MESSAGES_IN_THREADS(38, false, true, false, false), // Allows for sending messages in threads
	USE_EMBEDDED_ACTIVITIES(39, false, true, true, false), // Allows for using Activities (applications with the EMBEDDED flag)
	MODERATE_MEMBERS(40, false, false, false, false), // Allows for timing out users to prevent them from sending or reacting to messages in chat and threads, and from speaking in voice and stage channels
	VIEW_CREATOR_MONETIZATION_ANALYTICS(41, true, false, false, false), // Allows for viewing role subscription insights
	USE_SOUNDBOARD(42, false, false, true, false), // Allows for using soundboard in a voice channel
	CREATE_GUILD_EXPRESSIONS(43, false, false, false, false), // Allows for creating emojis, stickers, and soundboard sounds, and editing and deleting those created by the current user. Not yet available to developers, see changelog.
	CREATE_EVENTS(44, false, false, true, true), // Allows for creating scheduled events, and editing and deleting those created by the current user. Not yet available to developers, see changelog.
	USE_EXTERNAL_SOUNDS(45, false, false, true, false), // Allows the usage of custom soundboard sounds from other servers V
	SEND_VOICE_MESSAGES(46, false, true, true, true), // Allows sending voice messages
	SEND_POLLS(49, false, true, true, true), // Allows sending polls
	USE_EXTERNAL_APPS(50, false, true, true, true); // Allows user-installed apps to send public responses. When disabled, users will still be allowed to use their apps but the responses will be ephemeral. This only applies to apps not also installed to the server.

	public static final Set<Permission> DM = EnumSet.of(Permission.ADD_REACTIONS, Permission.VIEW_CHANNEL, Permission.READ_MESSAGE_HISTORY); // TODO: add missing ones
	private static final Permission[] INDEX = new Permission[Long.SIZE];

	public final int flag;
	public final boolean text;
	public final boolean voice;
	public final boolean stage;

	Permission(int flag,
			boolean applies2fa, // require the owner account to use two-factor authentication when used on a guild that has server-wide 2FA enabled.
			boolean text, // GUILD_TEXT, GUILD_ANNOUNCEMENT, GUILD_FORUM, GUILD_MEDIA
			boolean voice, // GUILD_VOICE
			boolean stage) { // GUILD_STAGE_VOICE
		this.flag = flag;
		this.text = text;
		this.voice = voice;
		this.stage = stage;
	}

	public long mask() {
		return 1L << flag;
	}

	public static Permission fromFlag(int flag) {
		return INDEX[flag];
	}

	public static Set<Permission> fromMask(long mask) {
		Set<Permission> ret = EnumSet.noneOf(Permission.class);

		while (mask != 0) {
			int flag = Long.numberOfTrailingZeros(mask);
			mask ^= 1L << flag;
			Permission perm = fromFlag(flag);

			if (perm != null) ret.add(perm);
		}

		return ret;
	}

	public static long toMask(Collection<Permission> perms) {
		long ret = 0;

		for (Permission perm : perms) {
			ret |= perm.mask();
		}

		return ret;
	}

	public static long toMask(Permission... perms) {
		long ret = 0;

		for (Permission perm : perms) {
			ret |= perm.mask();
		}

		return ret;
	}

	static {
		for (Permission p : values()) {
			INDEX[p.flag] = p;
		}
	}
}
