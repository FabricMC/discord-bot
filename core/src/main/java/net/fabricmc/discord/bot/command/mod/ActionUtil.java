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

import org.javacord.api.entity.channel.ServerChannel;
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
import net.fabricmc.discord.bot.database.query.ChannelActionQueries;
import net.fabricmc.discord.bot.database.query.ChannelActionQueries.ActiveChannelActionEntry;
import net.fabricmc.discord.bot.database.query.ChannelActionQueries.ChannelActionEntry;
import net.fabricmc.discord.bot.database.query.ChannelActionQueries.ExpiringChannelActionEntry;
import net.fabricmc.discord.bot.database.query.UserActionQueries;
import net.fabricmc.discord.bot.database.query.UserActionQueries.ExpiringUserActionEntry;
import net.fabricmc.discord.bot.database.query.UserActionQueries.UserActionEntry;

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
	 * @param context command context
	 * @return true if the action was executed successfully
	 */
	static void applyUserAction(UserActionType type, String target, String duration, String reason, CommandContext context) throws Exception {
		// determine target user

		int targetUserId = Command.getUserId(context, target);
		if (targetUserId == context.userId()) throw new CommandException("You can't target yourself");

		Command.checkImmunity(context, targetUserId, false);

		// check for conflict

		int prevId = -1;

		if (type.hasDuration) {
			int existingActionId = UserActionQueries.getActiveAction(context.bot().getDatabase(), targetUserId, type);
			if (existingActionId >= 0) throw new CommandException("The user is already %s", type.getDesc(false));
		}

		// determine duration

		long durationMs = parseActionDurationMs(duration, type.hasDuration);

		// determine target discord users

		List<Long> targetDiscordIds = context.bot().getUserHandler().getDiscordUserIds(targetUserId);

		if (!type.hasDuration && targetDiscordIds.isEmpty()) {
			throw new CommandException("Absent target user");
		}

		// create db record

		UserActionEntry entry = UserActionQueries.createAction(context.bot().getDatabase(), type, targetUserId, context.userId(), durationMs, reason, prevId);

		// announce action

		announceUserAction(type, false, "", "",
				targetUserId, targetDiscordIds,
				entry.creationTime(), entry.expirationTime(), reason,
				entry.id(),
				context.channel(), context.author().asUser().get(),
				context.bot(), context.server());

		// apply discord action

		for (long targetDiscordId : targetDiscordIds) {
			User user = context.server().getMemberById(targetDiscordId).orElse(null);
			if (user != null) type.activate(context.server(), user, reason, context.bot());
		}

		context.bot().getActionSyncHandler().onNewUserAction(entry);
	}

	static void suspendUserAction(UserActionType type, String target, String reason, CommandContext context) throws Exception {
		if (!type.hasDuration) throw new RuntimeException("Actions without a duration can't be suspended");

		// determine target user

		int targetUserId = Command.getUserId(context, target);

		// determine target discord users

		List<Long> targetDiscordIds = context.bot().getUserHandler().getDiscordUserIds(targetUserId);

		// determine action to suspend

		int actionId = UserActionQueries.getActiveAction(context.bot().getDatabase(), targetUserId, type);

		// suspend action in bot

		if (actionId < 0
				|| !UserActionQueries.suspendAction(context.bot().getDatabase(), actionId, context.userId(), reason)) { // action wasn't applied through the bot, determine direct applications (directly through discord)
			// filter for present users with the action active on discord, purely db-recorded users are irrelevant for direct interaction
			for (Iterator<Long> it = targetDiscordIds.iterator(); it.hasNext(); ) {
				long targetDiscordId = it.next();

				if (!type.isActive(context.server(), targetDiscordId, context.bot())) {
					it.remove();
				}
			}

			if (targetDiscordIds.isEmpty()) throw new CommandException("User %d is not %s.", targetUserId, type.getDesc(false));
		} else { // action was applied through the bot, update db record and remove from action sync handler
			context.bot().getActionSyncHandler().onUserActionSuspension(actionId);
		}

		// announce action

		announceUserAction(type, true, "", "",
				targetUserId, targetDiscordIds,
				System.currentTimeMillis(), 0, reason,
				actionId,
				context.channel(), context.author().asUser().get(),
				context.bot(), context.server());

		// apply discord action

		for (long targetDiscordId : targetDiscordIds) {
			type.deactivate(context.server(), targetDiscordId, reason, context.bot());
		}
	}

	public static void expireUserAction(ExpiringUserActionEntry entry, DiscordBot bot, Server server) throws SQLException {
		List<Long> targets = bot.getUserHandler().getDiscordUserIds(entry.targetUserId());

		if (entry.type().hasDeactivation) {
			for (long discordUserId : targets) {
				entry.type().deactivate(server, discordUserId, "automatic expiration", bot);
			}
		}

		UserActionQueries.expireAction(bot.getDatabase(), entry.id());

		announceUserAction(entry.type(), true, "(expiration)", "automatically",
				entry.targetUserId(), targets,
				System.currentTimeMillis(), 0, null,
				entry.id(),
				null, null,
				bot, server);
	}

	static void applyChannelAction(ChannelActionType type, String target, int data, String duration, String reason, String extraBodyDesc, CommandContext context) throws Exception {
		// determine target channel

		ServerChannel targetChannel = Command.getChannel(context, target);

		// check for conflict

		int prevId = -1;
		int prevResetData = 0;

		if (type.hasDuration) {
			ActiveChannelActionEntry existingAction = ChannelActionQueries.getActiveAction(context.bot().getDatabase(), targetChannel.getId(), type);

			if (existingAction != null) {
				int cmp = type.compareData(existingAction.data(), data);

				if (cmp == 0) { // same action is already active
					throw new CommandException("The channel is already %s", type.getDesc(false));
				} else if (cmp > 0 && !type.checkData(data, existingAction.resetData())) { // downgrade to or below the prev action's reset level, just suspend with no new action
					suspendChannelAction(type, target, reason, context);
					return;
				}

				if (ChannelActionQueries.suspendAction(context.bot().getDatabase(), existingAction.id(), context.userId(), "superseded")) {
					context.bot().getActionSyncHandler().onChannelActionSuspension(existingAction.id());
					prevId = existingAction.id();
					prevResetData = existingAction.resetData();
				}
			} else if (type.isActive(context.server(), targetChannel, data, context.bot())) { // channel already set to a higher level outside the bot
				throw new CommandException("The channel is already %s", type.getDesc(false));
			}
		}

		// determine duration

		long durationMs = parseActionDurationMs(duration, type.hasDuration);

		// apply discord action

		Integer resetData = type.activate(context.server(), targetChannel, data, reason, context.bot());
		if (resetData == null) throw new CommandException("The action is not applicable to the channel");

		if (prevId >= 0) resetData = prevResetData;

		// create db record

		ChannelActionEntry entry = ChannelActionQueries.createAction(context.bot().getDatabase(), type, targetChannel.getId(), data, resetData, context.userId(), durationMs, reason, prevId);

		// announce action

		announceChannelAction(type, false, "", extraBodyDesc,
				targetChannel,
				entry.creationTime(), entry.expirationTime(), reason,
				entry.id(),
				context.channel(), context.author().asUser().get(),
				context.bot(), context.server());

		// apply discord action

		context.bot().getActionSyncHandler().onNewChannelAction(entry);
	}

	static void suspendChannelAction(ChannelActionType type, String target, String reason, CommandContext context) throws Exception {
		if (!type.hasDuration) throw new RuntimeException("Actions without a duration can't be suspended");

		// determine target channel

		ServerChannel targetChannel = Command.getChannel(context, target);

		// determine action to suspend

		ActiveChannelActionEntry entry = ChannelActionQueries.getActiveAction(context.bot().getDatabase(), targetChannel.getId(), type);
		int actionId;
		Integer resetData;

		if (entry == null) {
			actionId = -1;
			resetData = null;
		} else {
			actionId = entry.id();
			resetData = entry.resetData();
		}

		// suspend action in bot

		if (entry == null
				|| !ChannelActionQueries.suspendAction(context.bot().getDatabase(), entry.id(), context.userId(), reason)) { // action wasn't applied through the bot
			throw new CommandException("Channel %s is not %s through the bot.", targetChannel.getName(), type.getDesc(false));
		} else { // action was applied through the bot, update db record and remove from action sync handler
			context.bot().getActionSyncHandler().onChannelActionSuspension(actionId);
		}

		// announce action

		announceChannelAction(type, true, "", "",
				targetChannel,
				System.currentTimeMillis(), 0, reason,
				actionId,
				context.channel(), context.author().asUser().get(),
				context.bot(), context.server());

		// apply discord action

		type.deactivate(context.server(), targetChannel, resetData, reason, context.bot());
	}

	public static void expireChannelAction(ExpiringChannelActionEntry entry, DiscordBot bot, Server server) throws SQLException {
		ServerChannel targetChannel = server.getChannelById(entry.channelId()).orElse(null);

		if (entry.type().hasDeactivation && targetChannel != null) {
			entry.type().deactivate(server, targetChannel, entry.resetData(), "automatic expiration", bot);
		}

		ChannelActionQueries.expireAction(bot.getDatabase(), entry.id());

		if (targetChannel != null) {
			announceChannelAction(entry.type(), true, "(expiration)", "automatically",
					targetChannel,
					System.currentTimeMillis(), 0, null,
					entry.id(),
					null, null,
					bot, server);
		}
	}

	static void announceUserAction(UserActionType type, boolean reversal, String extraTitleDesc, String extraBodyDesc,
			int targetUserId, List<Long> targetDiscordIds,
			long creation, long expiration, String reason,
			int actionId,
			TextChannel actingChannel, User actor,
			DiscordBot bot, Server server) {
		announceAction(type.getDesc(reversal), reversal, extraTitleDesc, extraBodyDesc,
				targetUserId, targetDiscordIds, null,
				creation, expiration, reason,
				actionId,
				actingChannel, actor, bot, server);
	}

	static void announceChannelAction(ChannelActionType type, boolean reversal, String extraTitleDesc, String extraBodyDesc,
			ServerChannel targetChannel,
			long creation, long expiration, String reason,
			int actionId,
			TextChannel actingChannel, User actor,
			DiscordBot bot, Server server) {
		announceAction(type.getDesc(reversal), reversal, extraTitleDesc, extraBodyDesc,
				-1, null, targetChannel,
				creation, expiration, reason,
				actionId,
				actingChannel, actor, bot, server);
	}

	private static void announceAction(String actionDesc, boolean reversal, String extraTitleDesc, String extraBodyDesc,
			int targetUserId, List<Long> targetDiscordIds, ServerChannel targetChannel,
			long creation, long expiration, String reason,
			int actionId,
			TextChannel actingChannel, User actor,
			DiscordBot bot, Server server) {
		if (!extraTitleDesc.isEmpty()) extraTitleDesc = " "+extraTitleDesc;
		if (!extraBodyDesc.isEmpty()) extraBodyDesc = " "+extraBodyDesc;

		// log to original channel

		String expirationSuffix;

		if (reversal || expiration == 0) {
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
		boolean targetIsUser = targetChannel == null;
		String targetType, targetName;
		CharSequence targetListSuffix;

		if (targetIsUser) {
			targetType = "User";
			targetName = Integer.toString(targetUserId);
			targetListSuffix = formatUserList(targetDiscordIds, bot, server);
		} else {
			targetType = "Channel";
			targetName = targetChannel.getName();
			targetListSuffix = "";
		}

		String title = String.format("%s %s%s", // e.g. 'User' 'unbanned'' (expiration)'
				targetType, actionDesc, extraTitleDesc);
		String description = String.format("%s %s has been %s%s%s:%s%s", // e.g. 'User' '123' has been 'unbanned'' automatically' +exp/target/reason
				targetType, targetName,
				actionDesc, extraBodyDesc,
				expirationSuffix,
				targetListSuffix,
				reasonSuffix);

		String actionRef = actionId >= 0 ? "%s Action ID: %d".formatted(targetType, actionId) : "Unknown action";
		TextChannel logChannel = bot.getLogHandler().getLogChannel();

		EmbedBuilder msg = new EmbedBuilder()
				.setTitle(title)
				.setDescription(description)
				.setFooter(actionRef)
				.setTimestamp(creationTime);

		if (actingChannel != null && actingChannel != logChannel) {
			actingChannel.sendMessage(msg);
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

		if (targetIsUser) {
			String appealSuffix = !reversal ? "\n\n%s".formatted(bot.getConfigEntry(APPEAL_MESSAGE)) : "";

			title = String.format("%s%s!", // e.g. 'Unbanned'' (expiration)'!
					actionDesc, extraTitleDesc);
			description = String.format("You have been %s%s%s.%s%s", // e.g. You have been 'unbanned'' automatically' +exp/reason/appeal
					actionDesc, extraBodyDesc,
					expirationSuffix,
					reasonSuffix,
					appealSuffix);

			EmbedBuilder userMsg = new EmbedBuilder()
					.setTitle(title)
					.setDescription(description)
					.setFooter(actionRef)
					.setTimestamp(creationTime);

			for (long targetDiscordId : targetDiscordIds) {
				User user = server.getMemberById(targetDiscordId).orElse(null);
				if (user != null) user.sendMessage(userMsg); // no get
			}
		}
	}

	private static long parseActionDurationMs(String duration, boolean requireDuration) throws CommandException {
		long durationMs;

		if (duration == null) {
			durationMs = 0;
		} else if (duration.equalsIgnoreCase("perm") || duration.equalsIgnoreCase("permanent")) {
			durationMs = -1;
		} else {
			durationMs = parseDurationMs(duration);
			if (durationMs < 0) throw new CommandException("Invalid duration");
		}

		if (durationMs == 0 && requireDuration) {
			throw new CommandException("Invalid zero duration");
		}

		return durationMs;
	}

	static long parseDurationMs(String str) {
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
		return formatDuration(durationMs, Integer.MAX_VALUE);
	}

	public static String formatDuration(long durationMs, int maxParts) {
		StringBuilder ret = new StringBuilder();

		if (durationMs < 0) {
			ret.append('-');
			durationMs = -durationMs;
		}

		while (durationMs > 0 && maxParts-- > 0) {
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

		if (ret.length() == 0 || ret.length() == 1 && ret.charAt(0) == '-') {
			return "0";
		} else {
			return ret.toString();
		}
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
