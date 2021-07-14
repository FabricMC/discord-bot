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
import java.util.Objects;
import java.util.Optional;

import org.javacord.api.entity.channel.ServerChannel;
import org.javacord.api.entity.channel.ServerTextChannel;
import org.javacord.api.entity.channel.TextChannel;
import org.javacord.api.entity.server.Server;
import org.javacord.api.exception.DiscordException;
import org.jetbrains.annotations.Nullable;

import net.fabricmc.discord.bot.CachedMessage;
import net.fabricmc.discord.bot.config.ConfigKey;
import net.fabricmc.discord.bot.util.DiscordUtil;

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
		Objects.requireNonNull(user, "null user");

		int ret = context.bot().getUserHandler().getUserId(user, context.server(), true);
		if (ret < 0) throw new CommandException("Unknown or ambiguous user");

		return ret;
	}

	public static long getDiscordUserId(CommandContext context, String user) throws CommandException {
		Objects.requireNonNull(user, "null user");

		long ret = context.bot().getUserHandler().getDiscordUserId(user, context.server(), true);
		if (ret < 0) throw new CommandException("Unknown or ambiguous user");

		return ret;
	}

	public static ServerChannel getChannel(CommandContext context, String channel) throws CommandException {
		Objects.requireNonNull(channel, "null channel");

		ServerChannel ret = getChannelUnchecked(context, channel);

		if (!ret.canYouSee()
				|| ret instanceof ServerTextChannel && !((ServerTextChannel) ret).canYouReadMessageHistory()
				|| !ret.canSee(context.user())) {
			throw new CommandException("Inaccessible channel");
		}

		return ret;
	}

	private static ServerChannel getChannelUnchecked(CommandContext context, String channel) throws CommandException {
		Server server = context.server();
		if (server == null) throw new CommandException("No server context (DM?)");

		if (channel.startsWith("#")) {
			String name = channel.substring(1);

			List<ServerChannel> matches = server.getChannelsByName(name);
			if (matches.isEmpty()) matches = server.getChannelsByNameIgnoreCase(name);
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
			Optional<ServerChannel> ret = server.getChannelById(id);

			if (ret.isPresent()) return ret.get();
		} catch (NumberFormatException e) { }

		List<ServerChannel> matches = server.getChannelsByName(channel);
		if (matches.isEmpty()) matches = server.getChannelsByNameIgnoreCase(channel);
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

	public static CachedMessage getMessage(CommandContext context, String message, boolean includeDeleted) throws CommandException, DiscordException {
		Objects.requireNonNull(message, "null message");

		CachedMessage ret = context.bot().getMessageIndex().get(message, context.server());
		if (ret == null || !includeDeleted && ret.isDeleted()) throw new CommandException("Unknown message");

		return ret;
	}

	public static UserTarget getUserTarget(CommandContext context, String userOrMessage) throws CommandException, DiscordException {
		Objects.requireNonNull(userOrMessage, "null userOrMessage");

		CachedMessage msg = context.bot().getMessageIndex().get(userOrMessage, context.server());
		int userId;

		if (msg != null) {
			userId = context.bot().getUserHandler().getUserId(msg.getAuthorDiscordId());
			if (userId < 0) throw new CommandException("Message from unknown user");
		} else {
			userId = context.bot().getUserHandler().getUserId(userOrMessage, context.server(), true);
			if (userId < 0) throw new CommandException("Unknown or ambiguous user/message");
		}

		return new UserTarget(userId, msg);
	}

	public static record UserTarget(int userId, @Nullable CachedMessage message) { }

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

	public static void checkMessageDeleteAccess(CommandContext context, TextChannel channel) throws CommandException {
		if (!hasMessageDeleteAccess(context, channel)) throw new CommandException("Inaccessible channel");
	}

	public static boolean hasMessageDeleteAccess(CommandContext context, TextChannel channel) {
		return DiscordUtil.canDeleteMessages(channel) && channel.canSee(context.user());
	}

	public static <V> V getConfig(CommandContext context, ConfigKey<V> key) {
		return context.bot().getConfigEntry(key);
	}

	public static <V> @Nullable V getUserConfig(CommandContext context, ConfigKey<V> key) {
		return context.bot().getUserConfig(context.userId(), key);
	}

	public static <V> V getUserConfig(CommandContext context, ConfigKey<V> key, V defaultValue) {
		return context.bot().getUserConfig(context.userId(), key, defaultValue);
	}

	public static <V> boolean setUserConfig(CommandContext context, ConfigKey<V> key, V value) {
		return context.bot().setUserConfig(context.userId(), key, value);
	}

	public static boolean removeUserConfig(CommandContext context, ConfigKey<?> key) {
		return context.bot().removeUserConfig(context.userId(), key);
	}
}
