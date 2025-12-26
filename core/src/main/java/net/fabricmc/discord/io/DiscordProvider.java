package net.fabricmc.discord.io;

public interface DiscordProvider {
	Discord create(DiscordBuilder.DiscordConfig config);
}
