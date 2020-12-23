package net.fabricmc.discord.bot.module;

import java.nio.file.Path;

import org.javacord.api.DiscordApi;

import net.fabricmc.discord.bot.DiscordBot;
import net.fabricmc.discord.bot.Module;

public final class TestModule implements Module {
	@Override
	public String getName() {
		return "test";
	}

	@Override
	public boolean setup(DiscordBot bot, DiscordApi api, Path configDir) {
		return true;
	}
}
