/*
 * Copyright (c) 2021 FabricMC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.fabricmc.discord.bot.command;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.javacord.api.entity.channel.ServerChannel;
import org.javacord.api.entity.channel.ServerTextChannel;

public abstract class Command {
	/**
	 * @return the name of the command
	 */
	public abstract String name();

	public List<String> aliases() {
		return List.of();
	}

	/**
	 * Gets the usage of the command.
	 * The command usage will be used to parse arguments and determine whether an argument is required or not.
	 *
	 * @return the command usage
	 * @see CommandParser#parse(CharSequence, UsageParser.Node, Map)
	 */
	public abstract String usage();

	/**
	 * Get the permission required to be able to run the command.
	 *
	 * @return permission name or null if none required
	 */
	public String permission() {
		return null;
	}

	public String shortHelp() {
		return null;
	}

	public String longHelp() {
		return null;
	}

	/**
	 * Called when a command should run.
	 *
	 * @param context the command context
	 * @param arguments the arguments the command was run with
	 * @return whether the command was successfully executed
	 */
	public abstract boolean run(CommandContext context, Map<String, String> arguments) throws Exception;

	public static int getUserId(CommandContext context, String user) throws CommandException {
		int ret = context.bot().getUserHandler().getUserId(user, context.server(), true);
		if (ret < 0) throw new CommandException("Unknown or ambiguous user");

		return ret;
	}

	public static long getDiscordUserId(CommandContext context, String user) throws CommandException {
		long ret = context.bot().getUserHandler().getDiscordUserId(user, context.server(), true);
		if (ret < 0) throw new CommandException("Unknown or ambiguous user");

		return ret;
	}

	public static ServerChannel getChannel(CommandContext context, String channel) throws CommandException {
		if (channel.startsWith("#")) {
			String name = channel.substring(1);

			List<ServerChannel> matches = context.server().getChannelsByName(name);
			if (matches.isEmpty()) matches = context.server().getChannelsByNameIgnoreCase(name);
			if (matches.size() == 1) return matches.get(0);
		}

		try {
			int start, end;

			if (channel.startsWith("<#") && channel.endsWith(">")) {
				start = 2;
				end = -1;
			} else {
				start = end = 0;
			}

			long id = Long.parseUnsignedLong(channel.substring(start, channel.length() + end));
			Optional<ServerChannel> ret = context.server().getChannelById(id);

			if (ret.isPresent()) return ret.get();
		} catch (NumberFormatException e) { }

		List<ServerChannel> matches = context.server().getChannelsByName(channel);
		if (matches.isEmpty()) matches = context.server().getChannelsByNameIgnoreCase(channel);
		if (matches.size() != 1) throw new CommandException("Unknown or ambiguous channel");

		return matches.get(0);
	}

	public static ServerTextChannel getTextChannel(CommandContext context, String channel) throws CommandException {
		ServerChannel ret = getChannel(context, channel);

		if (ret instanceof ServerTextChannel) {
			return (ServerTextChannel) ret;
		} else {
			throw new CommandException("Not a text channel");
		}
	}

	public static void checkSelfTarget(CommandContext context, int targetUserId) throws CommandException {
		if (targetUserId == context.userId()) {
			throw new CommandException("You can't target yourself");
		}
	}

	public static void checkImmunity(CommandContext context, int targetUserId, boolean allowBotTarget) throws CommandException {
		if (context.bot().getUserHandler().hasImmunity(targetUserId, context.userId(), allowBotTarget)) {
			throw new CommandException("The target has immunity");
		}
	}

	public static void checkImmunity(CommandContext context, long targetDiscordUserId, boolean allowBotTarget) throws CommandException {
		if (context.bot().getUserHandler().hasImmunity(targetDiscordUserId, context.userId(), allowBotTarget)) {
			throw new CommandException("The target has immunity");
		}
	}
}
