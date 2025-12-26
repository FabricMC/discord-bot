package net.fabricmc.discord.io;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public interface Member {
	Server getServer();
	User getUser();

	default long getId() {
		return getUser().getId();
	}

	String getDisplayName();
	String getNickname();
	void setNickName(String newNick, String reason);

	Instant getJoinTime();
	Status getStatus();
	String getAvatarUrl();

	enum Status {
		ONLINE("online"),
		IDLE("idle"),
		DND("dnd"),
		INVISIBLE("invisible"),
		OFFLINE("offline"),
		OTHER(null);

		private static final Map<String, Status> INDEX = new HashMap<>();
		public final String name;

		Status(String name) {
			this.name = name;
		}

		public static Status fromName(String name) {
			return INDEX.getOrDefault(name, OTHER);
		}

		static {
			for (Status type : values()) {
				if (type != OTHER) INDEX.put(type.name, type);
			}
		}
	}

	default boolean isAdmin() { return hasPermission(Permission.ADMINISTRATOR); }

	List<? extends Role> getRoles();
	void addRole(Role role, String reason);
	void removeRole(Role role, String reason);

	default boolean hasPermission(Permission perm) { return getPermissions().contains(perm); }
	Set<Permission> getPermissions();
	default boolean hasPermission(Channel channel, Permission perm)  { return getPermissions(channel).contains(perm); }
	Set<Permission> getPermissions(Channel channel);

	void kick(String reason);
	void ban(Duration messageDeleteionTimeframe, String reason);
}
