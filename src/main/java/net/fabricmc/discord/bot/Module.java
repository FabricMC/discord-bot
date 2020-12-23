package net.fabricmc.discord.bot;

import java.nio.file.Path;

import org.javacord.api.DiscordApi;

public interface Module {
	String getName();

	/**
	 * When called a module should setup.
	 *
	 * @param bot the bot instance
	 * @param api the api instance to communicate with discord
	 * @param configDir the directory of the configs
	 * @return if this module has successfully loaded
	 */
	boolean setup(DiscordBot bot, DiscordApi api, Path configDir);
}
