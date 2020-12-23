package net.fabricmc.discord.bot.command;

import java.net.URL;

import org.javacord.api.entity.channel.TextChannel;
import org.javacord.api.entity.message.MessageAuthor;
import org.javacord.api.entity.server.Server;
import org.jetbrains.annotations.Nullable;

import net.fabricmc.discord.bot.DiscordBot;

/**
 * An object which represents the context in which a command is being executed.
 */
public record CommandContext(
		DiscordBot bot,
		@Nullable Server server,
		URL messageLink,
		MessageAuthor author,
		TextChannel channel,
		String content,
		long messageId
) {}
