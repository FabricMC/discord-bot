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

package net.fabricmc.discord.bot.command.mod;

import java.sql.SQLException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;

import org.javacord.api.entity.channel.TextChannel;
import org.javacord.api.entity.message.embed.EmbedBuilder;
import org.javacord.api.entity.permission.Role;
import org.javacord.api.entity.server.Server;
import org.javacord.api.entity.user.User;

import net.fabricmc.discord.bot.DiscordBot;
import net.fabricmc.discord.bot.UserHandler;
import net.fabricmc.discord.bot.command.CommandContext;
import net.fabricmc.discord.bot.config.ConfigKey;
import net.fabricmc.discord.bot.config.ValueSerializers;
import net.fabricmc.discord.bot.database.query.ActionQueries;
import net.fabricmc.discord.bot.database.query.ActionQueries.ActionEntry;
import net.fabricmc.discord.bot.database.query.ActionQueries.ExpiringActionEntry;

public final class ActionUtil {
	public static final DateTimeFormatter timeFormatter = DateTimeFormatter.ofPattern("EEE, d MMM y HH:mm:ss z", Locale.ENGLISH).withZone(ZoneOffset.UTC);
	private static final ConfigKey<Long> MUTE_ROLE = new ConfigKey<>("action.muteRole", ValueSerializers.LONG);
	private static final ConfigKey<String> APPEAL_MESSAGE = new ConfigKey<>("action.appealMessage", ValueSerializers.STRING);

	public static void registerConfig(DiscordBot bot) {
		bot.registerConfigEntry(MUTE_ROLE, () -> -1L);
		bot.registerConfigEntry(APPEAL_MESSAGE, () -> "Please contact the moderation to appeal this action.");
	}

	public static boolean isUserMuted(Server server, User target, DiscordBot bot) {
		return hasRole(server, target, MUTE_ROLE, bot);
	}

	public static void muteUser(Server server, User target, String reason, DiscordBot bot) {
		addRole(server, target, MUTE_ROLE, reason, bot);
	}

	public static void unmuteUser(Server server, User target, String reason, DiscordBot bot) {
		removeRole(server, target, MUTE_ROLE, reason, bot);
	}

	private static boolean hasRole(Server server, User target, ConfigKey<Long> roleKey, DiscordBot bot) {
		Role role = getRole(server, roleKey, bot);

		return role != null && target.getRoles(server).contains(role);
	}

	private static void addRole(Server server, User target, ConfigKey<Long> roleKey, String reason, DiscordBot bot) {
		Role role = getRole(server, roleKey, bot);
		if (role == null) throw new RuntimeException("role unconfigured/missing");

		server.addRoleToUser(target, role, reason).join();
	}

	private static void removeRole(Server server, User target, ConfigKey<Long> roleKey, String reason, DiscordBot bot) {
		Role role = getRole(server, roleKey, bot);
		if (role == null) return;

		server.removeRoleFromUser(target, role, reason).join();
	}

	private static Role getRole(Server server, ConfigKey<Long> key, DiscordBot bot) {
		long roleId = bot.getConfigEntry(key);
		if (roleId < 0) return null;

		return server.getRoleById(roleId).orElse(null);
	}

	/**
	 * Apply a specific action to a discord user.
	 *
	 * <p>This creates a DB record, logs the action to the current and the log channels, notifies the target user via
	 * direct message and executes the given discord action.
	 *
	 * @param type action type, e.g. kick or ban
	 * @param target target user in any format supported by UserHandler
	 * @param duration duration string, e.g. 4h30, 4h30m, 3w, perm, permanent or null if none (only applicable to duration-less actions)
	 * @param reason reason for the action, may be null for none
	 * @param prevId action id being replaced by this new action, for adjusting a previous action's severity
	 * @param context command context
	 * @return true if the action was executed successfully
	 */
	static boolean applyAction(ActionType type, String target, String duration, String reason, int prevId, CommandContext context) {
		try {
			// determine target user

			int targetUserId = context.bot().getUserHandler().getUserId(target, context.server(), true);

			if (targetUserId < 0) {
				context.channel().sendMessage("Unknown or ambiguous target user");
				return false;
			}

			if (targetUserId == context.userId()) {
				context.channel().sendMessage("You can't target yourself");
				return false;
			}

			// check for conflict

			if (type.hasDuration) {
				int existingActionId = ActionQueries.getActiveAction(context.bot().getDatabase(), targetUserId, type);

				if (existingActionId >= 0) {
					context.channel().sendMessage("The user is already %s".formatted(type.actionDesc));
					return false;
				}
			}

			// determine duration

			long durationMs;

			if (duration == null) {
				durationMs = 0;
			} else if (duration.equalsIgnoreCase("perm") || duration.equalsIgnoreCase("permanent")) {
				durationMs = -1;
			} else {
				durationMs = parseDurationMs(duration);

				if (durationMs < 0) {
					context.channel().sendMessage("Invalid duration");
					return false;
				}
			}

			if (durationMs == 0 && type.hasDuration) {
				context.channel().sendMessage("Invalid zero duration");
				return false;
			}

			// determine target discord users

			List<User> targets = context.bot().getUserHandler().getDiscordUsers(targetUserId, context.server());

			if (!type.hasDuration && targets.isEmpty()) {
				context.channel().sendMessage("Absent target user");
				return false;
			}

			// create db record

			ActionEntry entry = ActionQueries.createAction(context.bot().getDatabase(), type, targetUserId, context.userId(), durationMs, reason, prevId);

			// log to original channel

			String expirationSuffix;

			if (!type.hasDuration) {
				expirationSuffix = "";
			} else if (entry.expirationTime() < 0) {
				expirationSuffix = " permanently";
			} else {
				expirationSuffix = " until ".concat(timeFormatter.format(Instant.ofEpochMilli(entry.expirationTime())));
			}

			String reasonSuffix;

			if (reason == null || reason.isEmpty()) {
				reasonSuffix = "";
			} else {
				reasonSuffix = "\n\n**Reason:** %s".formatted(reason);
			}

			StringBuilder userList = new StringBuilder();

			for (User user : targets) {
				userList.append("\n".concat(UserHandler.formatDiscordUser(user)));
			}

			Instant creationTime = Instant.ofEpochMilli(entry.creationTime());
			String description = "User %d has been %s%s:%s%s".formatted(targetUserId, type.actionDesc, expirationSuffix, userList, reasonSuffix);
			TextChannel logChannel = context.bot().getLogHandler().getLogChannel();

			EmbedBuilder msg = new EmbedBuilder()
					.setTitle("User %s".formatted(type.actionDesc))
					.setDescription(description)
					.setFooter("Action ID: %d".formatted(entry.id()))
					.setTimestamp(creationTime);

			if (context.channel() != logChannel) {
				context.channel().sendMessage(msg).get();
			}

			// log to log channel

			if (logChannel != null) {
				// include executing moderator info
				msg.setDescription("%s\n**Moderator:** %s".formatted(description, UserHandler.formatDiscordUser(context.author().asUser().get())));

				logChannel.sendMessage(msg).get();
			}

			// message target user

			EmbedBuilder userMsg = new EmbedBuilder()
					.setTitle("%s!".formatted(type.actionDesc))
					.setDescription("You have been %s%s.%s\n\n%s".formatted(type.actionDesc, expirationSuffix, reasonSuffix, context.bot().getConfigEntry(APPEAL_MESSAGE)))
					.setFooter("Action ID: %d".formatted(entry.id()))
					.setTimestamp(creationTime);

			for (User user : targets) {
				user.sendMessage(userMsg); // no get
			}

			// apply discord action

			for (User user : targets) {
				type.activate(context.server(), user, reason, context.bot());
			}

			context.bot().getActionSyncHandler().onNewAction(entry);

			return true;
		} catch (Exception e) {
			e.printStackTrace();
			context.channel().sendMessage("Execution failed:\n`%s`".formatted(e));
			return false;
		}
	}

	static boolean suspendAction(ActionType type, String target, String reason, CommandContext context) {
		try {
			if (!type.hasDuration) {
				context.channel().sendMessage("Actions without a duration can't be suspended");
				return false;
			}

			// determine target user

			int targetUserId = context.bot().getUserHandler().getUserId(target, context.server(), true);

			if (targetUserId < 0) {
				context.channel().sendMessage("Unknown or ambiguous target user");
				return false;
			}

			// determine target discord users

			List<Long> targets = context.bot().getUserHandler().getDiscordUserIds(targetUserId);

			// determine action

			int actionId = ActionQueries.getActiveAction(context.bot().getDatabase(), targetUserId, type);

			if (actionId < 0
					|| !ActionQueries.suspendAction(context.bot().getDatabase(), actionId, context.userId(), reason)) { // action wasn't applied through the bot, determine direct applications (directly through discord)
				for (Iterator<Long> it = targets.iterator(); it.hasNext(); ) {
					long targetDiscordId = it.next();

					if (!type.isActive(context.server(), targetDiscordId, context.bot())) {
						it.remove();
					}
				}

				if (targets.isEmpty()) {
					context.channel().sendMessage(String.format("User %d is not %s.", targetUserId, type.actionDesc));
					return false;
				}
			} else { // action was applied through the bot, update db record and remove from action sync handler
				context.bot().getActionSyncHandler().onActionSuspension(actionId);
			}

			// log to original channel

			String reasonSuffix;

			if (reason == null || reason.isEmpty()) {
				reasonSuffix = "";
			} else {
				reasonSuffix = "\n\n**Reason:** %s".formatted(reason);
			}

			StringBuilder userList = new StringBuilder();

			for (long targetDiscordId : targets) {
				userList.append("\n".concat(context.bot().getUserHandler().formatDiscordUser(targetDiscordId, context.server())));
			}

			Instant creationTime = Instant.now();
			String description = "User %d has been un%s:%s%s".formatted(targetUserId, type.actionDesc, userList, reasonSuffix);
			TextChannel logChannel = context.bot().getLogHandler().getLogChannel();

			EmbedBuilder msg = new EmbedBuilder()
					.setTitle("User un%s".formatted(type.actionDesc))
					.setDescription(description)
					.setTimestamp(creationTime);

			if (actionId < 0) {
				msg.setFooter("Unknown action");
			} else {
				msg.setFooter("Action ID: %d".formatted(actionId));
			}

			if (context.channel() != logChannel) {
				context.channel().sendMessage(msg).get();
			}

			// log to log channel

			if (logChannel != null) {
				// include executing moderator info
				msg.setDescription("%s\n**Moderator:** %s".formatted(description, UserHandler.formatDiscordUser(context.author().asUser().get())));

				logChannel.sendMessage(msg).get();
			}

			// apply discord action

			for (long targetDiscordId : targets) {
				type.deactivate(context.server(), targetDiscordId, reason, context.bot());
			}

			return true;
		} catch (Exception e) {
			e.printStackTrace();
			context.channel().sendMessage("Execution failed:\n`%s`".formatted(e));
			return false;
		}
	}

	public static void expireAction(ExpiringActionEntry entry, DiscordBot bot, Server server) throws SQLException {
		List<Long> discordUserIds = bot.getUserHandler().getDiscordUserIds(entry.targetUserId());

		if (entry.type().hasDeactivation) {
			for (long discordUserId : discordUserIds) {
				entry.type().deactivate(server, discordUserId, "automatic expiration", bot);
			}
		}

		ActionQueries.expireAction(bot.getDatabase(), entry.id());

		TextChannel logChannel = bot.getLogHandler().getLogChannel();
		if (logChannel == null) return;

		StringBuilder userList = new StringBuilder();

		for (long targetDiscordId : discordUserIds) {
			userList.append("\n".concat(bot.getUserHandler().formatDiscordUser(targetDiscordId, server)));
		}

		logChannel.sendMessage(new EmbedBuilder()
				.setTitle("User un%s (expiration)".formatted(entry.type().actionDesc))
				.setDescription("User %d has been un%s automatically:%s".formatted(entry.targetUserId(), entry.type().actionDesc, userList))
				.setFooter("Action ID: %d".formatted(entry.id()))
				.setTimestampToNow());
	}

	private static long parseDurationMs(String str) {
		int start = 0;
		int end = str.length();

		boolean empty = true;
		int lastQualifierIdx = 2; // pretend the prev qualifier was minutes (idx 2) to make it select seconds (idx 1) by default
		long ret = 0;

		mainLoop: while (start < end) {
			char c = str.charAt(start);

			// skip leading whitespace
			while (Character.isWhitespace(c)) {
				if (++start >= end) break mainLoop;
				c = str.charAt(start);
			}

			// read numeric part
			long accum = 0;

			if (c < '0' || c > '9') {
				return -1; // no numeric part
			} else {
				do {
					accum = accum * 10 + (c - '0');

					if (++start >= end) {
						c = 0;
					} else {
						c = str.charAt(start);
					}
				} while (c >= '0' && c <= '9');
			}

			// skip whitespace between number and potential qualifier
			while (c != 0 && Character.isWhitespace(c)) {
				if (++start >= end) {
					c = 0;
				} else {
					c = str.charAt(start);
				}
			}

			// read qualifier (determine its end pos)
			int qualStart = start;

			while (c != 0 && (c < '0' || c > '9') && !Character.isWhitespace(c)) {
				if (++start >= end) {
					c = 0;
				} else {
					c = str.charAt(start);
				}
			}

			// parse qualifier
			long mul;

			if (start == qualStart) { // no qualifier
				// use one less than the prev qualifier if possible, interprets e.g. 4h30 as 4h30m
				mul = durationLengthsMs[Math.max(lastQualifierIdx - 1, 0)];
			} else {
				mul = -1;
				int len = start - qualStart;

				qualLoop: for (int i = 0; i < durationQualifiers.length; i++) {
					for (String q : durationQualifiers[i]) {
						if (q.length() == len && str.startsWith(q, qualStart)) {
							lastQualifierIdx = i;
							mul = durationLengthsMs[i];
							break qualLoop;
						}
					}
				}

				if (mul < 0) return -1; // no valid qualifier
			}

			empty = false;
			ret += accum * mul;
		}

		return empty ? -1 : ret;
	}

	public static String formatDuration(long durationMs) {
		StringBuilder ret = new StringBuilder();

		while (durationMs > 0) {
			int maxQualIdx = 0;

			for (int i = durationLengthsMs.length - 1; i > 0; i--) {
				if (durationLengthsMs[i] <= durationMs) {
					maxQualIdx = i;
					break;
				}
			}

			long currentMul = durationLengthsMs[maxQualIdx];
			String currentQual = durationQualifiers[maxQualIdx][0];

			long count = durationMs / currentMul;
			durationMs -= count * currentMul;

			ret.append(Long.toString(count));
			ret.append(currentQual);
		}

		if (ret.length() == 0) return "0";

		return ret.toString();
	}

	private static final String[][] durationQualifiers = { {"ms"},
			{"s", "sec", "second", "seconds"}, {"m", "min", "minute", "minutes"}, {"h", "hour", "hours"},
			{"d", "D", "day", "days"}, {"w", "W", "week", "weeks"}, {"M", "mo", "month", "months"}, {"y", "Y", "year", "years"} };
	private static final long[] durationLengthsMs = { 1,
			1_000, 60_000L, 3_600_000L,
			86_400_000L,  604_800_000L, 2_592_000_000L, 31_536_000_000L };
}
