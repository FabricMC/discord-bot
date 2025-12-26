package net.fabricmc.discord.io;

import java.util.List;
import java.util.Set;

public interface Channel {
	Discord getDiscord();
	Server getServer();
	User getUser();
	long getId();
	Type getType();

	enum Type {
		// https://discord.com/developers/docs/resources/channel#channel-object-channel-types
		GUILD_TEXT(0, true, false, true, false), // a text channel within a server
		DM(1, true, true, false, false), // a direct message between users
		GUILD_VOICE(2, true, true, true, false), // a voice channel within a server
		GROUP_DM(3, true, false, false, false), // a direct message between multiple users
		GUILD_CATEGORY(4, false, false, true, false), // an organizational category that contains up to 50 channels
		GUILD_ANNOUNCEMENT(5, true, false, true, false), // a channel that users can follow and crosspost into their own server (formerly news channels)
		ANNOUNCEMENT_THREAD(10, true, false, true, true), // a temporary sub-channel within a GUILD_ANNOUNCEMENT channel
		PUBLIC_THREAD(11, true, false, true, true), // a temporary sub-channel within a GUILD_TEXT or GUILD_FORUM channel
		PRIVATE_THREAD(12, true, false, true, true), // a temporary sub-channel within a GUILD_TEXT channel that is only viewable by those invited and those with the MANAGE_THREADS permission
		GUILD_STAGE_VOICE(13, true, true, true, false), // a voice channel for hosting events with an audience
		GUILD_DIRECTORY(14, false, false, true, false), // the channel in a hub containing the listed servers
		GUILD_FORUM(15, false, false, true, false), // Channel that can only contain threads
		GUILD_MEDIA(16, false, false, true, false), // Channel that can only contain threads, similar to GUILD_FORUM channels
		OTHER(-1, false, false, false, false);

		private static final Type[] INDEX;

		public final int id;
		public final boolean text;
		public final boolean voice;
		public final boolean guild;
		public final boolean thread;

		Type(int id, boolean text, boolean voice, boolean guild, boolean thread) {
			this.id = id;
			this.text = text;
			this.voice = voice;
			this.guild = guild;
			this.thread = thread;
		}

		public static Type fromId(int id) {
			if (id >= 0 && id < INDEX.length) {
				Type ret = INDEX[id];
				if (ret != null) return ret;
			}

			return OTHER;
		}

		static {
			Type[] values = values();
			INDEX = new Type[values[values.length - 2].id + 1];

			for (Type type : values) {
				if (type != OTHER) INDEX[type.id] = type;
			}
		}
	}

	String getName();

	default boolean hasPermission(User user, Permission perm) { return getPermissions(user).contains(perm); }
	Set<Permission> getPermissions(User user);
	default boolean haveYouPermission(Permission perm) { return hasPermission(getDiscord().getYourself(), perm); }
	default boolean canSee(User user) { return hasPermission(user, Permission.VIEW_CHANNEL); }
	default boolean canYouSee() { return canSee(getDiscord().getYourself()); }
	PermissionOverwriteData getPermissionOverwrites(Role role);
	void setPermissionOverwrites(Role role, PermissionOverwriteData data, String reason);
	record PermissionOverwriteData(Set<Permission> allowed, Set<Permission> denied) { }

	Message getMessage(long id);
	List<? extends Message> getMessages(int limit);
	List<? extends Message> getMessagesBetween(long firstId, long lastId, int limit);
	Message send(String message);
	Message send(MessageEmbed message);
	Message send(Message message);
	void deleteMessage(long id, String reason);
	void deleteMessages(long[] messageIds, String reason);

	int getSlowmodeDelaySeconds();
	void setSlowmodeDelaySeconds(int delaySec, String reason);
}
