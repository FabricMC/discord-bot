/*
 * Copyright (c) 2021, 2022 FabricMC
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
import java.util.Collection;
import java.util.Collections;

import it.unimi.dsi.fastutil.longs.LongList;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

import net.fabricmc.discord.bot.CachedMessage;
import net.fabricmc.discord.bot.CachedMessageAttachment;
import net.fabricmc.discord.bot.DiscordBot;
import net.fabricmc.discord.bot.UserHandler;
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
import net.fabricmc.discord.bot.util.FormatUtil;
import net.fabricmc.discord.bot.util.FormatUtil.OutputType;
import net.fabricmc.discord.io.Channel;
import net.fabricmc.discord.io.DiscordException;
import net.fabricmc.discord.io.Member;
import net.fabricmc.discord.io.MessageEmbed;
import net.fabricmc.discord.io.Role;
import net.fabricmc.discord.io.Server;
import net.fabricmc.discord.io.User;

public final class ActionUtil {
	static final Logger LOGGER = LogManager.getLogger("action");
	private static final ConfigKey<String> APPEAL_MESSAGE = new ConfigKey<>("action.appealMessage", ValueSerializers.STRING);

	public static void registerConfig(DiscordBot bot) {
		bot.registerConfigEntry(APPEAL_MESSAGE, "Please contact the moderation to appeal this action.");
		ActionRole.registerConfig(bot);
	}

	static boolean hasRole(Member target, ActionRole actionRole, DiscordBot bot) {
		Role role = actionRole.resolve(target.getServer(), bot);

		return role != null && target.getRoles().contains(role);
	}

	static void addRole(Member target, ActionRole actionRole, String reason, DiscordBot bot) throws DiscordException {
		Role role = actionRole.resolve(target.getServer(), bot);
		if (role == null) throw new IllegalArgumentException("role unconfigured/missing");

		target.addRole(role, reason);
	}

	static void removeRole(Member target, ActionRole actionRole, String reason, DiscordBot bot) throws DiscordException {
		Role role = actionRole.resolve(target.getServer(), bot);
		if (role == null) return;

		target.removeRole(role, reason);
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
	public static void applyUserAction(ActionType type, long data, int targetUserId, @Nullable String duration, String reason,
			@Nullable CachedMessage targetMessage, UserMessageAction targetMessageAction,
			boolean notifyTarget, String privateReason,
			DiscordBot bot, Server server, @Nullable Channel actingChannel, User actor, int actorUserId) throws Exception {
		boolean validTargetMessageAction = targetMessageAction != UserMessageAction.NONE
				&& (targetMessage != null || !targetMessageAction.needsContext);
		String extraBodyDesc;

		if (!validTargetMessageAction) {
			extraBodyDesc = null;
		} else {
			extraBodyDesc = "with %s".formatted(targetMessageAction.desc);
		}

		int actionId = applyAction(type, data, targetUserId, duration, reason,
				targetMessage,
				extraBodyDesc, notifyTarget, privateReason,
				bot, server, actingChannel, actor, actorUserId);

		if (validTargetMessageAction) {
			String deleteReason = "Action %d".formatted(actionId);

			int count = 0;

			if (targetMessage != null && targetMessage.delete(server, deleteReason)) {
				count++;
			}

			Collection<CachedMessage> extraDeleted = switch (targetMessageAction) {
			case CLEAN -> CleanCommand.clean(targetUserId, null, deleteReason, bot, server, actor);
			case CLEAN_LOCAL -> {
				Channel channel;

				if (targetMessage != null
						&& (channel = targetMessage.getChannel(server)) != null) {
					yield CleanCommand.clean(targetUserId, channel, deleteReason, bot, server, actor);
				} else {
					yield Collections.emptyList();
				}
			}
			default -> Collections.emptyList();
			};

			if (actingChannel != null) {
				count += extraDeleted.size();
				actingChannel.send("Deleted %d message%s".formatted(count, count != 1 ? "s" : ""));
			}
		} else if (targetMessageAction != UserMessageAction.NONE && actingChannel != null) {
			actingChannel.send("Skipping %s, missing message context".formatted(targetMessageAction.desc));
		}
	}

	public enum UserMessageAction {
		NONE("none", "none", false),
		DELETE("delete", "delete", true),
		CLEAN("clean", "clean", false),
		CLEAN_LOCAL("cleanLocal", "local clean", true);

		public static UserMessageAction parse(String id) {
			for (UserMessageAction action : UserMessageAction.values()) {
				if (action.id.equals(id)) return action;
			}

			throw new IllegalArgumentException("invalid user message action: "+id);
		}

		UserMessageAction(String id, String desc, boolean needsContext) {
			this.id = id;
			this.desc = desc;
			this.needsContext = needsContext;
		}

		public final String id;
		public final String desc;
		public final boolean needsContext;
	}

	static void applyChannelAction(ActionType type, long data, long targetChannelId, String duration, @Nullable String reason,
			@Nullable String extraBodyDesc,
			DiscordBot bot, Server server, Channel actingChannel, @Nullable User actor, int actorUserId) throws Exception {
		applyAction(type, data, targetChannelId, duration, reason, null,
				extraBodyDesc, false, null,
				bot, server, actingChannel, actor, actorUserId);
	}

	private static int applyAction(ActionType type, long data, long targetId, @Nullable String duration, @Nullable String reason,
			CachedMessage targetMessageContext,
			@Nullable String extraBodyDesc, boolean notifyTarget, @Nullable String privateReason,
			DiscordBot bot, Server server, Channel actingChannel, @Nullable User actor, int actorUserId) throws Exception {
		// check for conflict

		int prevId = -1;
		Long prevResetData = null;

		if (type.hasDuration()) {
			ActiveActionEntry existingAction = ActionQueries.getActiveAction(bot.getDatabase(), targetId, type);

			if (existingAction != null) {
				int cmp = existingAction.data() != null ? type.compareData(existingAction.data().data(), data) : 0;

				if (cmp == 0) { // same action is already active
					throw new CommandException("The %s is already %s", type.getKind().id, ActionDesc.getShort(type, false));
				} else if (cmp > 0 && !type.checkData(data, existingAction.data().resetData())) { // downgrade to or below the prev action's reset level, just suspend with no new action
					suspendAction(type, targetId, reason,
							notifyTarget, privateReason,
							bot, server, actingChannel, actor, actorUserId);

					return existingAction.id();
				}

				if (ActionQueries.suspendAction(bot.getDatabase(), existingAction.id(), actorUserId, "superseded")) {
					bot.getActionSyncHandler().onActionSuspension(existingAction.id());
					prevId = existingAction.id();
					prevResetData = existingAction.data().resetData();
				}
			} else if (type.getKind() == Kind.CHANNEL && type.isActive(server, targetId, data, bot)) { // channel already set to a higher level outside the bot
				throw new CommandException("The %s is already %s", type.getKind().id, ActionDesc.getShort(type, false));
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

		boolean notifyEarly = notifyTarget && type.isNotificationBarrier();

		if (notifyEarly) {
			// TODO: allocate action id first?
			notifyTarget(type, false, null, extraBodyDesc, targetId, creationTime, expirationTime, reason, -1, bot, server);
		}

		// apply discord action

		try {
			result = type.activate(server, targetId, false, data, reason, bot);
		} catch (DiscordException e) {
			throw new CommandException("Action failed: "+e);
		}

		if (type.requiresTargetPresence() && result.targets() == 0) {
			throw new CommandException("Absent target");
		}

		if (!result.applicable()) {
			throw new CommandException("The action is not applicable to the "+type.getKind().id);
		}

		Long resetData = prevId >= 0 ? prevResetData : result.resetData();

		// create db record

		ActionEntry entry = ActionQueries.createAction(bot.getDatabase(),
				type,
				(data != 0 || result.resetData() != null ? new ActionData(data, resetData) : null),
				targetId, actorUserId, durationMs, creationTime, expirationTime, formatReason(reason, privateReason),
				targetMessageContext, prevId);

		// apply discord action again in case the user rejoined quickly (race condition)

		if (type.hasDuration()) {
			try {
				type.activate(server, targetId, false, data, reason, bot);
			} catch (DiscordException e) {
				LOGGER.warn("Action re-application failed: {}", e.toString());
			}
		}

		// announce action

		announceAction(type, false, null, extraBodyDesc,
				targetId,
				entry.creationTime(), entry.expirationTime(), reason,
				entry.id(), targetMessageContext,
				bot, server, actingChannel, actor,
				notifyTarget, notifyEarly, privateReason);

		// record action for expiration

		bot.getActionSyncHandler().onNewAction(entry);

		return entry.id();
	}

	static void suspendAction(ActionType type, long targetId, @Nullable String reason,
			boolean notifyTarget, @Nullable String privateReason,
			DiscordBot bot, Server server, @Nullable Channel actingChannel, @Nullable User actor, int actorUserId) throws Exception {
		if (!type.hasDuration()) throw new RuntimeException("Actions without a duration can't be suspended");

		// determine action to suspend

		ActiveActionEntry entry = ActionQueries.getActiveAction(bot.getDatabase(), targetId, type);
		int actionId;
		Long resetData;

		if (entry == null) {
			actionId = -1;
			resetData = null;
		} else {
			actionId = entry.id();
			resetData = entry.data() != null ? entry.data().resetData() : null;
		}

		// suspend action in bot

		if (entry == null
				|| !ActionQueries.suspendAction(bot.getDatabase(), entry.id(), actorUserId, formatReason(reason, privateReason))) { // action wasn't applied through the bot, determine direct applications (directly through discord)
			if (!type.canRevertBeyondBotDb()) {
				throw new CommandException("%s %d is not %s through the bot.", type.getKind().id, targetId, ActionDesc.getShort(type, false));
			} else if (!type.isActive(server, targetId, 0, bot)) {
				throw new CommandException("%s %d is not %s.", type.getKind().id, targetId, ActionDesc.getShort(type, false));
			}
		} else { // action was applied through the bot, update db record and remove from action sync handler
			bot.getActionSyncHandler().onActionSuspension(entry.id());
		}

		// apply discord action

		try {
			type.deactivate(server, targetId, resetData, reason, bot);
		} catch (DiscordException e) {
			throw new CommandException("Action failed: "+e);
		}

		// announce action

		announceAction(type, true, "", "",
				targetId,
				System.currentTimeMillis(), 0, reason,
				actionId, null,
				bot, server, actingChannel, actor,
				notifyTarget, false, privateReason);
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
				entry.id(), null,
				bot, server, null, null,
				true, false, null);
	}

	static void announceAction(ActionType type, boolean reversal, @Nullable String extraTitleDesc, @Nullable String extraBodyDesc,
			long targetId,
			long creation, long expiration, @Nullable String reason,
			int actionId, CachedMessage targetMessageContext,
			DiscordBot bot, Server server, @Nullable Channel actingChannel, @Nullable User actor,
			boolean notifyTarget, boolean alreadyNotified, @Nullable String privateReason) {
		// log to original channel

		String targetName;
		CharSequence targetListSuffix;

		if (type.getKind() == Kind.USER) {
			int targetUserId = (int) targetId;

			targetName = Integer.toString(targetUserId);
			targetListSuffix = FormatUtil.formatUserList(bot.getUserHandler().getDiscordUserIds(targetUserId), bot, server);
		} else {
			Channel targetChannel = server.getChannel(targetId);

			targetName = targetChannel != null ? targetChannel.getName() : "(unknown)";
			targetListSuffix = "";
		}

		String title = String.format("%s %s%s", // e.g. 'User unbanned'' (expiration)'
				type.getKind().capitalized, ActionDesc.getShort(type, reversal), formatOptionalSuffix(extraTitleDesc));
		String description = String.format("%s%s%s%s:%s\n%s", // e.g. 'User 123 has been unbanned'' automatically'' without notification' +exp/target/duration
				FormatUtil.capitalize(ActionDesc.getThirdPerson(type, reversal, "%s %s".formatted(type.getKind().id, targetName))),
				formatOptionalSuffix(extraBodyDesc), (notifyTarget ? "" : " without notification"),
				formatExpirationSuffix(reversal, expiration),
				targetListSuffix,
				formatDurationSuffix(type, reversal, creation, expiration))
				.trim(); // to remove trailing \n if there's neither duration nor reason

		String actionRef = formatActionRef(type.getKind(), actionId);
		Channel logChannel = bot.getLogHandler().getLogChannel();

		MessageEmbed.Builder msgBuilder = new MessageEmbed.Builder()
				.title(title)
				.description(description.concat(formatReasonSuffix(reason)))
				.footer(actionRef)
				.time(Instant.ofEpochMilli(creation));

		if (actingChannel != null && actingChannel != logChannel) {
			actingChannel.send(msgBuilder.build());
		}

		// log to log channel

		if (logChannel != null) {
			description = description.concat(formatReasonSuffix(formatReason(reason, privateReason)));

			if (targetMessageContext != null) {
				StringBuilder attachmentsSuffix = new StringBuilder();

				if (targetMessageContext.getAttachments().length > 0) {
					attachmentsSuffix.append("\n**Context Message Attachments:** %d".formatted(targetMessageContext.getAttachments().length));

					for (CachedMessageAttachment attachment : targetMessageContext.getAttachments()) {
						attachmentsSuffix.append(String.format("\n`%d`: %.1f kB, [link](%s)",
								attachment.getId(),
								attachment.getSize() * 1e-3,
								attachment.getUrl()));
					}
				}

				// include target message context
				description = String.format("%s\n**Context Channel:** <#%d>\n**Context Message:**%s%s",
						description,
						targetMessageContext.getChannelId(),
						FormatUtil.escape(FormatUtil.truncateMessage(targetMessageContext.getContent(), 600), OutputType.CODE, true),
						attachmentsSuffix); // assume 600 chars of non-context-msg content
			}

			if (actor != null) {
				// include executing moderator info
				description = "%s\n**Moderator:** %s".formatted(description, UserHandler.formatDiscordUser(actor));
			}

			msgBuilder.description(description);

			logChannel.send(msgBuilder.build());
		}

		// message target user

		if (type.getKind() == Kind.USER && notifyTarget && !alreadyNotified) {
			notifyTarget(type, reversal, extraTitleDesc, extraBodyDesc, targetId, creation, expiration, reason, actionId, bot, server);
		}
	}

	static boolean notifyTarget(ActionType type, boolean reversal, @Nullable String extraTitleDesc, @Nullable String extraBodyDesc,
			long targetId,
			long creation, long expiration, @Nullable String reason,
			int actionId,
			DiscordBot bot, Server server) {
		if (type.getKind() != Kind.USER) return false;

		LongList targetDiscordIds = bot.getUserHandler().getDiscordUserIds((int) targetId);
		if (targetDiscordIds.isEmpty()) return false;

		String appealSuffix = !reversal ? "\n\n%s".formatted(bot.getConfigEntry(APPEAL_MESSAGE)) : "";

		MessageEmbed userMsg = new MessageEmbed.Builder()
				.title(String.format("%s%s!", // e.g. 'Unbanned'' (expiration)'!
						FormatUtil.capitalize(ActionDesc.getShort(type, reversal)), formatOptionalSuffix(extraTitleDesc)))
				.description(String.format("%s%s%s.%s%s", // e.g. 'You have been unbanned'' automatically' +exp/reason/appeal
						FormatUtil.capitalize(ActionDesc.getSecondPerson(type, reversal)),
						formatOptionalSuffix(extraBodyDesc),
						formatExpirationSuffix(reversal, expiration),
						formatReasonSuffix(reason),
						appealSuffix))
				.footer(formatActionRef(type.getKind(), actionId))
				.time(Instant.ofEpochMilli(creation))
				.build();

		boolean ret = false;

		for (long targetDiscordId : targetDiscordIds) {
			Member member = server.getMember(targetDiscordId);

			if (member != null) {
				try {
					member.getUser().dm().send(userMsg);
					ret = true;
				} catch (Exception e) {
					LOGGER.warn("Error notifying target {}/{}: {}", targetId, targetDiscordId, e.toString());
				}
			}
		}

		return ret;
	}

	private static String formatOptionalSuffix(@Nullable String content) {
		if (content == null || content.isEmpty()) {
			return "";
		} else if (content.startsWith(" ")) {
			return content;
		} else {
			return " "+content;
		}
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

	private static String formatDurationSuffix(ActionType type, boolean reversal, long creation, long expiration) {
		if (reversal || !type.hasDuration()) {
			return "";
		} else {
			return String.format("\n**Duration:** %s",
					(expiration < 0 ? "permanent" : FormatUtil.formatDuration(expiration - creation)));
		}
	}

	private static String formatReasonSuffix(@Nullable String reason) {
		if (reason == null || reason.isEmpty()) {
			return "";
		} else {
			return "\n**Reason:** %s".formatted(reason);
		}
	}

	private static @Nullable String formatReason(@Nullable String reason, @Nullable String privateReason) {
		if (privateReason != null && !privateReason.isEmpty()) {
			if (reason != null && !reason.isEmpty()) {
				return reason+" "+privateReason;
			} else {
				return privateReason;
			}
		} else {
			return reason;
		}
	}

	private static String formatActionRef(ActionType.Kind kind, int actionId) {
		return actionId >= 0 ? "%s Action ID: %d".formatted(kind.capitalized, actionId) : "Unknown action";
	}
}
