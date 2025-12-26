package net.fabricmc.discord.io;

public interface Role {
	Server getServer();
	long getId();
	boolean isEveryone();

	default String getMentionTag() {
		if (isEveryone()) {
			return "@everyone";
		} else {
			return String.format("<@&%s>", getId());
		}
	}
}
