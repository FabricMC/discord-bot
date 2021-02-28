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
import net.fabricmc.discord.bot.command.Command;
import net.fabricmc.discord.bot.command.CommandContext;
import net.fabricmc.discord.bot.command.CommandException;
import net.fabricmc.discord.bot.config.ConfigKey;
import net.fabricmc.discord.bot.config.ValueSerializers;
import net.fabricmc.discord.bot.database.query.ActionQueries;
import net.fabricmc.discord.bot.database.query.ActionQueries.ActionEntry;
import net.fabricmc.discord.bot.database.query.ActionQueries.ExpiringActionEntry;

public final class ActionUtil {
	public static final DateTimeFormatter dateFormatter = DateTimeFormatter.ISO_LOCAL_DATE.withZone(ZoneOffset.UTC);
	public static final DateTimeFormatter dateTimeFormatter = DateTimeFormatter.ofPattern("EEE, d MMM y HH:mm:ss z", Locale.ENGLISH).withZone(ZoneOffset.UTC);
	private static final ConfigKey<String> APPEAL_MESSAGE = new ConfigKey<>("action.appealMessage", ValueSerializers.STRING);

	public static void registerConfig(DiscordBot bot) {
		bot.registerConfigEntry(APPEAL_MESSAGE, () -> "Please contact the moderation to appeal this action.");
		ActionRole.registerConfig(bot);
	}

	static boolean hasRole(Server server, User target, ActionRole actionRole, DiscordBot bot) {
		Role role = actionRole.resolve(server, bot);

		return role != null && target.getRoles(server).contains(role);
	}

	static void addRole(Server server, User target, ActionRole actionRole, String reason, DiscordBot bot) {
		Role role = actionRole.resolve(server, bot);
		if (role == null) throw new IllegalArgumentException("role unconfigured/missing");

		server.addRoleToUser(target, role, reason).join();
	}

	static void removeRole(Server server, User target, ActionRole actionRole, String reason, DiscordBot bot) {
		Role role = actionRole.resolve(server, bot);
		if (role == null) return;

		server.removeRoleFromUser(target, role, reason).join();
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
	static void applyAction(ActionType type, String target, String duration, String reason, int prevId, CommandContext context) throws Exception {
		// determine target user

		int targetUserId = Command.getUserId(context, target);
		if (targetUserId == context.userId()) throw new CommandException("You can't target yourself");

		// check for conflict

		if (type.hasDuration) {
			int existingActionId = ActionQueries.getActiveAction(context.bot().getDatabase(), targetUserId, type);
			if (existingActionId >= 0) throw new CommandException("The user is already %s", type.getDesc(false));
		}

		// determine duration

		long durationMs;

		if (duration == null) {
			durationMs = 0;
		} else if (duration.equalsIgnoreCase("perm") || duration.equalsIgnoreCase("permanent")) {
			durationMs = -1;
		} else {
			durationMs = parseDurationMs(duration);
			if (durationMs < 0) throw new CommandException("Invalid duration");
		}

		if (durationMs == 0 && type.hasDuration) {
			throw new CommandException("Invalid zero duration");
		}

		// determine target discord users

		List<Long> targetDiscordIds = context.bot().getUserHandler().getDiscordUserIds(targetUserId);

		if (!type.hasDuration && targetDiscordIds.isEmpty()) {
			throw new CommandException("Absent target user");
		}

		// create db record

		ActionEntry entry = ActionQueries.createAction(context.bot().getDatabase(), type, targetUserId, context.userId(), durationMs, reason, prevId);

		// announce action

		announceAction(type, false, "", "",
				targetUserId, targetDiscordIds, entry.creationTime(), entry.expirationTime(), reason,
				entry.id(),
				context.channel(), context.author().asUser().get(),
				context.bot(), context.server());

		// apply discord action

		for (long targetDiscordId : targetDiscordIds) {
			User user = context.server().getMemberById(targetDiscordId).orElse(null);
			if (user != null) type.activate(context.server(), user, reason, context.bot());
		}

		context.bot().getActionSyncHandler().onNewAction(entry);
	}

	static void suspendAction(ActionType type, String target, String reason, CommandContext context) throws Exception {
		if (!type.hasDuration) throw new RuntimeException("Actions without a duration can't be suspended");

		// determine target user

		int targetUserId = Command.getUserId(context, target);

		// determine target discord users

		List<Long> targetDiscordIds = context.bot().getUserHandler().getDiscordUserIds(targetUserId);

		// determine action

		int actionId = ActionQueries.getActiveAction(context.bot().getDatabase(), targetUserId, type);

		if (actionId < 0
				|| !ActionQueries.suspendAction(context.bot().getDatabase(), actionId, context.userId(), reason)) { // action wasn't applied through the bot, determine direct applications (directly through discord)
			for (Iterator<Long> it = targetDiscordIds.iterator(); it.hasNext(); ) {
				long targetDiscordId = it.next();

				if (!type.isActive(context.server(), targetDiscordId, context.bot())) {
					it.remove();
				}
			}

			if (targetDiscordIds.isEmpty()) throw new CommandException("User %d is not %s.", targetUserId, type.getDesc(false));
		} else { // action was applied through the bot, update db record and remove from action sync handler
			context.bot().getActionSyncHandler().onActionSuspension(actionId);
		}

		// announce action

		announceAction(type, true, "", "",
				targetUserId, targetDiscordIds, System.currentTimeMillis(), 0, reason,
				actionId,
				context.channel(), context.author().asUser().get(),
				context.bot(), context.server());

		// apply discord action

		for (long targetDiscordId : targetDiscordIds) {
			type.deactivate(context.server(), targetDiscordId, reason, context.bot());
		}
	}

	public static void expireAction(ExpiringActionEntry entry, DiscordBot bot, Server server) throws SQLException {
		List<Long> targets = bot.getUserHandler().getDiscordUserIds(entry.targetUserId());

		if (entry.type().hasDeactivation) {
			for (long discordUserId : targets) {
				entry.type().deactivate(server, discordUserId, "automatic expiration", bot);
			}
		}

		ActionQueries.expireAction(bot.getDatabase(), entry.id());

		announceAction(entry.type(), true, "(expiration)", "automatically",
				entry.targetUserId(), targets, System.currentTimeMillis(), 0, null,
				entry.id(),
				null, null,
				bot, server);
	}

	static void announceAction(ActionType type, boolean reversal, String extraTitleDesc, String extraBodyDesc,
			int targetUserId, List<Long> targetDiscordIds, long creation, long expiration, String reason,
			int actionId,
			TextChannel channel, User actor,
			DiscordBot bot, Server server) {
		if (!extraTitleDesc.isEmpty()) extraTitleDesc = " "+extraTitleDesc;
		if (!extraBodyDesc.isEmpty()) extraBodyDesc = " "+extraBodyDesc;

		// log to original channel

		String expirationSuffix;

		if (expiration == 0) {
			expirationSuffix = "";
		} else if (expiration < 0) {
			expirationSuffix = " permanently";
		} else {
			expirationSuffix = " until ".concat(dateTimeFormatter.format(Instant.ofEpochMilli(expiration)));
		}

		String reasonSuffix;

		if (reason == null || reason.isEmpty()) {
			reasonSuffix = "";
		} else {
			reasonSuffix = "\n\n**Reason:** %s".formatted(reason);
		}

		Instant creationTime = Instant.ofEpochMilli(creation);
		String desc = type.getDesc(reversal);
		String description = String.format("User %d has been %s%s%s:%s%s",
				targetUserId,
				desc, extraBodyDesc,
				expirationSuffix,
				formatUserList(targetDiscordIds, bot, server),
				reasonSuffix);
		String actionRef = actionId >= 0 ? "Action ID: %d".formatted(actionId) : "Unknown action";
		TextChannel logChannel = bot.getLogHandler().getLogChannel();

		EmbedBuilder msg = new EmbedBuilder()
				.setTitle("User %s%s".formatted(desc, extraTitleDesc))
				.setDescription(description)
				.setFooter(actionRef)
				.setTimestamp(creationTime);

		if (channel != null && channel != logChannel) {
			channel.sendMessage(msg);
		}

		// log to log channel

		if (logChannel != null) {
			if (actor != null) {
				// include executing moderator info
				msg.setDescription("%s\n**Moderator:** %s".formatted(description, UserHandler.formatDiscordUser(actor)));
			}

			logChannel.sendMessage(msg);
		}

		// message target user

		String appealSuffix = !reversal ? "\n\n%s".formatted(bot.getConfigEntry(APPEAL_MESSAGE)) : "";
		EmbedBuilder userMsg = new EmbedBuilder()
				.setTitle("%s!".formatted(desc, extraTitleDesc))
				.setDescription("You have been %s%s%s.%s%s".formatted(desc, extraBodyDesc, expirationSuffix, reasonSuffix, appealSuffix))
				.setFooter(actionRef)
				.setTimestamp(creationTime);

		for (long targetDiscordId : targetDiscordIds) {
			User user = server.getMemberById(targetDiscordId).orElse(null);
			if (user != null) user.sendMessage(userMsg); // no get
		}
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

	static CharSequence formatUserList(List<User> targets) {
		StringBuilder ret = new StringBuilder();

		for (User user : targets) {
			ret.append('\n');
			ret.append(UserHandler.formatDiscordUser(user));
		}

		return ret;
	}

	static CharSequence formatUserList(List<Long> targets, CommandContext context) {
		return formatUserList(targets, context.bot(), context.server());
	}

	static CharSequence formatUserList(List<Long> targets, DiscordBot bot, Server server) {
		StringBuilder ret = new StringBuilder();

		for (long targetDiscordId : targets) {
			ret.append('\n');
			ret.append(bot.getUserHandler().formatDiscordUser(targetDiscordId, server));
		}

		return ret;
	}
}
