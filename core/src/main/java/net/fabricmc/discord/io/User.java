package net.fabricmc.discord.io;

import java.util.Collection;

public interface User {
	Discord getDiscord();
	long getId();
	String getName();
	String getDiscriminator();
	String getGlobalNickname(); // aka global name, server-independent nickname

	default String getGlobalDisplayName() {
		String ret = getGlobalNickname();

		return ret != null ? ret : getName();
	}

	default String getMentionTag() {
		return getMentionTag(getId());
	}

	static String getMentionTag(long id) {
		return String.format("<@%d>", id);
	}

	default String getNickMentionTag() {
		return getNickMentionTag(getId());
	}

	static String getNickMentionTag(long id) {
		return String.format("<@!%d>", id);
	}

	boolean isBot();
	boolean isYourself();
	Collection<Server> getMutualServers();

	Channel dm();
}
