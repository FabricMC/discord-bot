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
import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.javacord.api.entity.channel.ServerChannel;
import org.javacord.api.entity.channel.TextChannel;
import org.javacord.api.entity.message.embed.EmbedBuilder;
import org.javacord.api.entity.permission.Role;
import org.javacord.api.entity.server.Server;
import org.javacord.api.entity.user.User;
import org.javacord.api.exception.DiscordException;
import org.jetbrains.annotations.Nullable;

import net.fabricmc.discord.bot.DiscordBot;
import net.fabricmc.discord.bot.UserHandler;
import net.fabricmc.discord.bot.command.CommandContext;
import net.fabricmc.discord.bot.command.CommandException;
import net.fabricmc.discord.bot.command.mod.ActionType.ActivateResult;
import net.fabricmc.discord.bot.command.mod.ActionType.Kind;
import net.fabricmc.discord.bot.config.ConfigKey;
import net.fabricmc.discord.bot.config.ValueSerializers;
import net.fabricmc.discord.bot.database.query.ActionQueries;
import net.fabricmc.discord.bot.database.query.ActionQueries.ActionData;
import net.fabricmc.discord.bot.database.query.ActionQueries.ActionEntry;
import net.fabricmc.discord.bot.database.query.ActionQueries.ActiveActionEntry;
import net.fabricmc.discord.bot.database.query.ActionQueries.ExpiringActionEntry;
import net.fabricmc.discord.bot.util.DiscordUtil;
import net.fabricmc.discord.bot.util.FormatUtil;

public final class ActionUtil {
	static final Logger LOGGER = LogManager.getLogger("action");
	private static final ConfigKey<String> APPEAL_MESSAGE = new ConfigKey<>("action.appealMessage", ValueSerializers.STRING);

	public static void registerConfig(DiscordBot bot) {
		bot.registerConfigEntry(APPEAL_MESSAGE, () -> "Please contact the moderation to appeal this action.");
		ActionRole.registerConfig(bot);
	}

	static boolean hasRole(Server server, User target, ActionRole actionRole, DiscordBot bot) {
		Role role = actionRole.resolve(server, bot);

		return role != null && target.getRoles(server).contains(role);
	}

	static void addRole(Server server, User target, ActionRole actionRole, String reason, DiscordBot bot) throws DiscordException {
		Role role = actionRole.resolve(server, bot);
		if (role == null) throw new IllegalArgumentException("role unconfigured/missing");

		DiscordUtil.join(server.addRoleToUser(target, role, reason));
	}

	static void removeRole(Server server, User target, ActionRole actionRole, String reason, DiscordBot bot) throws DiscordException {
		Role role = actionRole.resolve(server, bot);
		if (role == null) return;

		DiscordUtil.join(server.removeRoleFromUser(target, role, reason));
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
	static void applyAction(ActionType type, int data, long targetId, String duration, String reason, @Nullable String extraBodyDesc, CommandContext context) throws Exception {
		// check for conflict

		int prevId = -1;
		Integer prevResetData = null;

		if (type.hasDuration()) {
			ActiveActionEntry existingAction = ActionQueries.getActiveAction(context.bot().getDatabase(), targetId, type);

			if (existingAction != null) {
				int cmp = existingAction.data() != null ? type.compareData(existingAction.data().data(), data) : 0;

				if (cmp == 0) { // same action is already active
					throw new CommandException("The %s is already %s", type.getKind().id, type.getDesc(false));
				} else if (cmp > 0 && !type.checkData(data, existingAction.data().resetData())) { // downgrade to or below the prev action's reset level, just suspend with no new action
					suspendAction(type, targetId, reason, context);
					return;
				}

				if (ActionQueries.suspendAction(context.bot().getDatabase(), existingAction.id(), context.userId(), "superseded")) {
					context.bot().getActionSyncHandler().onActionSuspension(existingAction.id());
					prevId = existingAction.id();
					prevResetData = existingAction.data().resetData();
				}
			} else if (type.isActive(context.server(), targetId, data, context.bot())) { // channel already set to a higher level outside the bot
				throw new CommandException("The channel is already %s", type.getDesc(false));
			}
		}

		// determine duration, creation, expiration

		long durationMs = FormatUtil.parseActionDurationMs(duration, type.hasDuration());
		ActivateResult result;

		long creationTime = System.currentTimeMillis();
		long expirationTime;

		if (type.hasDuration()) {
			expirationTime = durationMs > 0 ? creationTime + durationMs : -1;
		} else {
			expirationTime = 0;
		}

		// message target user (done first since it may no longer be possible after applying the discord action)

		if (type.getKind() == Kind.USER) {
			// TODO: allocate action id first?
			notifyTarget(type, false, null, extraBodyDesc, targetId, creationTime, expirationTime, reason, -1, context.bot(), context.server());
		}

		// apply discord action

		try {
			result = type.activate(context.server(), targetId, false, data, reason, context.bot());
		} catch (DiscordException e) {
			throw new CommandException("Action failed: "+e);
		}

		if (!type.hasDuration() && result.targets() == 0) {
			throw new CommandException("Absent target");
		}

		if (!result.applicable()) {
			throw new CommandException("The action is not applicable to the "+type.getKind().id);
		}

		Integer resetData = prevId >= 0 ? prevResetData : result.resetData();

		// create db record

		ActionEntry entry = ActionQueries.createAction(context.bot().getDatabase(),
				type,
				(data != 0 || result.resetData() != null ? new ActionData(data, resetData) : null),
				targetId, context.userId(), durationMs, creationTime, expirationTime, reason, prevId);

		// apply discord action again in case the user rejoined quickly (race condition)

		if (type.hasDuration()) {
			try {
				type.activate(context.server(), targetId, false, data, reason, context.bot());
			} catch (DiscordException e) {
				LOGGER.warn("Action re-application failed: {}", e.toString());
			}
		}

		// announce action

		announceAction(type, false, null, extraBodyDesc,
				targetId,
				entry.creationTime(), entry.expirationTime(), reason,
				entry.id(),
				context.channel(), context.author().asUser().get(),
				context.bot(), context.server(),
				false);

		// record action for expiration

		context.bot().getActionSyncHandler().onNewAction(entry);
	}

	static void suspendAction(ActionType type, long targetId, String reason, CommandContext context) throws Exception {
		if (!type.hasDuration()) throw new RuntimeException("Actions without a duration can't be suspended");

		// determine action to suspend

		ActiveActionEntry entry = ActionQueries.getActiveAction(context.bot().getDatabase(), targetId, type);
		int actionId;
		Integer resetData;

		if (entry == null) {
			actionId = -1;
			resetData = null;
		} else {
			actionId = entry.id();
			resetData = entry.data() != null ? entry.data().resetData() : null;
		}

		// suspend action in bot

		if (entry == null
				|| !ActionQueries.suspendAction(context.bot().getDatabase(), entry.id(), context.userId(), reason)) { // action wasn't applied through the bot, determine direct applications (directly through discord)
			if (!type.canRevertBeyondBotDb()) {
				throw new CommandException("%s %d is not %s through the bot.", type.getKind().id, targetId, type.getDesc(false));
			} else if (!type.isActive(context.server(), targetId, 0, context.bot())) {
				throw new CommandException("%s %d is not %s.", type.getKind().id, targetId, type.getDesc(false));
			}
		} else { // action was applied through the bot, update db record and remove from action sync handler
			context.bot().getActionSyncHandler().onActionSuspension(entry.id());
		}

		// apply discord action

		try {
			type.deactivate(context.server(), targetId, resetData, reason, context.bot());
		} catch (DiscordException e) {
			throw new CommandException("Action failed: "+e);
		}

		// announce action

		announceAction(type, true, "", "",
				targetId,
				System.currentTimeMillis(), 0, reason,
				actionId,
				context.channel(), context.author().asUser().get(),
				context.bot(), context.server(),
				true);
	}

	public static void expireAction(ExpiringActionEntry entry, DiscordBot bot, Server server) throws SQLException {
		try {
			entry.type().deactivate(server, entry.targetId(), entry.data() != null ? entry.data().resetData() : null, "automatic expiration", bot);
		} catch (DiscordException e) {
			LOGGER.warn("{} {} action {} expiration failed: {}", entry.type().getKind().id, entry.type().getId(), entry.id(), e.toString());
			return;
		}

		ActionQueries.expireAction(bot.getDatabase(), entry.id());

		announceAction(entry.type(), true, "(expiration)", "automatically",
				entry.targetId(),
				System.currentTimeMillis(), 0, null,
				entry.id(),
				null, null,
				bot, server,
				true);
	}

	static void announceAction(ActionType type, boolean reversal, @Nullable String extraTitleDesc, @Nullable String extraBodyDesc,
			long targetId,
			long creation, long expiration, String reason,
			int actionId,
			TextChannel actingChannel, User actor,
			DiscordBot bot, Server server,
			boolean notifyTarget) {
		if (extraTitleDesc == null || extraTitleDesc.isEmpty()) {
			extraTitleDesc = "";
		} else {
			extraTitleDesc = " "+extraTitleDesc;
		}

		if (extraBodyDesc == null || extraBodyDesc.isEmpty()) {
			extraBodyDesc = "";
		} else {
			extraBodyDesc = " "+extraBodyDesc;
		}

		// log to original channel

		List<Long> targetDiscordIds;
		String targetType, targetName;
		CharSequence targetListSuffix;

		if (type.getKind() == Kind.USER) {
			int targetUserId = (int) targetId;
			targetDiscordIds = bot.getUserHandler().getDiscordUserIds(targetUserId);

			targetType = "User";
			targetName = Integer.toString(targetUserId);
			targetListSuffix = FormatUtil.formatUserList(targetDiscordIds, bot, server);
		} else {
			ServerChannel targetChannel = server.getChannelById(targetId).orElse(null);
			targetDiscordIds = null;

			targetType = "Channel";
			targetName = targetChannel != null ? targetChannel.getName() : "(unknown)";
			targetListSuffix = "";
		}

		String actionDesc = type.getDesc(reversal);
		String title = String.format("%s %s%s", // e.g. 'User' 'unbanned'' (expiration)'
				targetType, actionDesc, extraTitleDesc);
		String description = String.format("%s %s has been %s%s%s:%s%s", // e.g. 'User' '123' has been 'unbanned'' automatically' +exp/target/reason
				targetType, targetName,
				actionDesc, extraBodyDesc,
				formatExpirationSuffix(reversal, expiration),
				targetListSuffix,
				formatReasonSuffix(reason));

		String actionRef = actionId >= 0 ? "%s Action ID: %d".formatted(targetType, actionId) : "Unknown action";
		TextChannel logChannel = bot.getLogHandler().getLogChannel();

		EmbedBuilder msg = new EmbedBuilder()
				.setTitle(title)
				.setDescription(description)
				.setFooter(actionRef)
				.setTimestamp(Instant.ofEpochMilli(creation));

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

		if (type.getKind() == Kind.USER && notifyTarget) {
			notifyTarget(type, reversal, extraTitleDesc, extraBodyDesc, actionId, creation, expiration, reason, actionId, bot, server);
		}
	}

	static boolean notifyTarget(ActionType type, boolean reversal, @Nullable String extraTitleDesc, @Nullable String extraBodyDesc,
			long targetId,
			long creation, long expiration, String reason,
			int actionId,
			DiscordBot bot, Server server) {
		if (type.getKind() != Kind.USER) return false;

		List<Long> targetDiscordIds = bot.getUserHandler().getDiscordUserIds((int) targetId);
		if (targetDiscordIds.isEmpty()) return false;

		String actionDesc = type.getDesc(reversal);
		String appealSuffix = !reversal ? "\n\n%s".formatted(bot.getConfigEntry(APPEAL_MESSAGE)) : "";

		EmbedBuilder userMsg = new EmbedBuilder()
				.setTitle(String.format("%s%s!", // e.g. 'Unbanned'' (expiration)'!
						actionDesc, extraTitleDesc))
				.setDescription(String.format("You have been %s%s%s.%s%s", // e.g. You have been 'unbanned'' automatically' +exp/reason/appeal
						actionDesc, extraBodyDesc,
						formatExpirationSuffix(reversal, expiration),
						formatReasonSuffix(reason),
						appealSuffix))
				.setFooter(formatActionRef(type.getKind(), actionId))
				.setTimestamp(Instant.ofEpochMilli(creation));

		boolean ret = false;

		for (long targetDiscordId : targetDiscordIds) {
			User user = server.getMemberById(targetDiscordId).orElse(null);

			if (user != null) {
				try {
					DiscordUtil.join(user.sendMessage(userMsg));
					ret = true;
				} catch (DiscordException e) {
					LOGGER.warn("Error notifying target {}/{}: {}", targetId, targetDiscordId, e.toString());
				}
			}
		}

		return ret;
	}

	private static String formatExpirationSuffix(boolean reversal, long expiration) {
		if (reversal || expiration == 0) {
			return "";
		} else if (expiration < 0) {
			return " permanently";
		} else {
			return " until ".concat(FormatUtil.dateTimeFormatter.format(Instant.ofEpochMilli(expiration)));
		}
	}

	private static String formatReasonSuffix(String reason) {
		if (reason == null || reason.isEmpty()) {
			return "";
		} else {
			return "\n\n**Reason:** %s".formatted(reason);
		}
	}

	private static String formatActionRef(ActionType.Kind kind, int actionId) {
		return actionId >= 0 ? "%s Action ID: %d".formatted(kind.name(), actionId) : "Unknown action";
	}
}
