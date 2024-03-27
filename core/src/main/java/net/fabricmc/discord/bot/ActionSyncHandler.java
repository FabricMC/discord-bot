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

package net.fabricmc.discord.bot;

import java.sql.SQLException;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.javacord.api.entity.auditlog.AuditLog;
import org.javacord.api.entity.auditlog.AuditLogActionType;
import org.javacord.api.entity.auditlog.AuditLogEntry;
import org.javacord.api.entity.auditlog.AuditLogEntryTarget;
import org.javacord.api.entity.server.Ban;
import org.javacord.api.entity.server.Server;
import org.javacord.api.entity.user.User;
import org.javacord.api.event.server.member.ServerMemberBanEvent;
import org.javacord.api.event.server.member.ServerMemberJoinEvent;
import org.javacord.api.listener.ChainableGloballyAttachableListenerManager;
import org.javacord.api.listener.server.member.ServerMemberBanListener;
import org.javacord.api.listener.server.member.ServerMemberJoinListener;

import net.fabricmc.discord.bot.command.mod.ActionUtil;
import net.fabricmc.discord.bot.command.mod.ActionUtil.UserMessageAction;
import net.fabricmc.discord.bot.command.mod.UserActionType;
import net.fabricmc.discord.bot.database.query.ActionQueries;
import net.fabricmc.discord.bot.database.query.ActionQueries.ActionEntry;
import net.fabricmc.discord.bot.database.query.ActionQueries.ActiveActionEntry;
import net.fabricmc.discord.bot.database.query.ActionQueries.ExpiringActionEntry;

/**
 * Mechanism to keep Discord up to date with the bot's actions.
 *
 * <p>This handles time based expiration of temporary actions and re-application of actions that Discord doesn't
 * persistently enforce itself.
 */
public final class ActionSyncHandler implements ServerMemberJoinListener, ServerMemberBanListener {
	private static final int expirationWindowMinutes = 120; // only schedule tasks for the near future in Java
	private static final int retryDelayMinutes = 5;

	private static final Logger LOGGER = LogManager.getLogger("actionSync");

	private final DiscordBot bot;
	private Server server;
	private Future<?> expirationsUpdateFuture; // task for periodically scheduling expirations
	private final Map<Integer, Future<?>> scheduledExpirations = new HashMap<>(); // tasks for every actual expiring action within the expiration window

	ActionSyncHandler(DiscordBot bot) {
		this.bot = bot;

		bot.getActiveHandler().registerReadyHandler(this::onReady);
		bot.getActiveHandler().registerGoneHandler(this::onGone);
		bot.getMessageIndex().registerCreateHandler(this::onMessage);
	}

	private synchronized void onReady(Server server, long prevActive) {
		// re-activate active actions for all members and channels

		this.server = server;

		try {
			long time = System.currentTimeMillis();
			Collection<ActiveActionEntry> activeActions = ActionQueries.getActiveActions(bot.getDatabase());

			for (ActiveActionEntry action : activeActions) {
				if (action.expirationTime() < 0 || action.expirationTime() > time) {
					try {
						action.type().activate(server, action.targetId(), true, action.data() != null ? action.data().data() : 0, action.reason(), bot);
					} catch (Exception e) {
						LOGGER.warn("Error re-activating action on ready", e);
					}
				}
			}
		} catch (SQLException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}

		// start periodic expiration scheduling (runs immediately, then every expirationWindowMinutes)

		expirationsUpdateFuture = bot.getScheduledExecutor().scheduleWithFixedDelay(() -> updateExpirations(), 0, expirationWindowMinutes, TimeUnit.MINUTES);
	}

	private synchronized void onGone(Server server) {
		this.server = null;

		expirationsUpdateFuture.cancel(false);
		expirationsUpdateFuture = null;

		for (Future<?> future : scheduledExpirations.values()) {
			future.cancel(false);
		}

		scheduledExpirations.clear();
	}

	private synchronized void updateExpirations() {
		if (server == null) return; // server gone

		long time = System.currentTimeMillis();
		long maxTime = time + expirationWindowMinutes * 60_000L;

		try {
			for (ExpiringActionEntry entry : ActionQueries.getExpiringActions(bot.getDatabase(), maxTime)) {
				addEntry(entry, time);
			}
		} catch (SQLException e) {
			LOGGER.warn("Error updating expiration", e);
		}
	}

	public void onNewAction(ActionEntry entry) {
		if (entry.expirationTime() <= 0) return; // no expiration

		long time = System.currentTimeMillis();
		if (time + expirationWindowMinutes * 60_000L < entry.expirationTime()) return;

		ExpiringActionEntry expEntry = new ExpiringActionEntry(entry.id(), entry.type(), entry.data(), entry.targetId(), entry.expirationTime());

		try {
			synchronized (this) {
				// make sure the expiration didn't execute yet
				// (updateExpirations may have run between creating the action and calling notify)

				if (!ActionQueries.isExpiringAction(bot.getDatabase(), entry.id())) {
					return;
				}

				addEntry(expEntry, time);
			}
		} catch (SQLException e) {
			LOGGER.warn("Error checking new action for expiration", e);
		}
	}

	private void addEntry(ExpiringActionEntry entry, long time) {
		if (server == null) return; // server gone

		long delay = entry.expirationTime() - time;

		if (delay <= 0) {
			expireAction(entry, false);
		} else if (!scheduledExpirations.containsKey(entry.id())) {
			scheduledExpirations.put(entry.id(), bot.getScheduledExecutor().schedule(() -> expireAction(entry, true), delay, TimeUnit.MILLISECONDS));
		}
	}

	private synchronized void expireAction(ExpiringActionEntry entry, boolean scheduled) {
		if (server == null) return; // server gone
		if (scheduled && scheduledExpirations.remove(entry.id()) == null) return; // no longer valid

		try {
			ActionUtil.expireAction(entry, bot, server);
		} catch (Exception e) {
			LOGGER.warn("Error expiring action", e);

			if (server != null) {
				scheduledExpirations.put(entry.id(), bot.getScheduledExecutor().schedule(() -> expireAction(entry, true), retryDelayMinutes, TimeUnit.MINUTES)); // retry after 5 min
			}
		}
	}

	public synchronized void onActionSuspension(int actionId) {
		// cancel expiration, the suspension handling already reverted the action
		Future<?> future = scheduledExpirations.remove(actionId);
		if (future != null) future.cancel(false);
	}

	void registerEarlyHandlers(ChainableGloballyAttachableListenerManager src) {
		src.addServerMemberJoinListener(this);
		src.addServerMemberBanListener(this);
	}

	@Override
	public void onServerMemberJoin(ServerMemberJoinEvent event) {
		Server server = event.getServer();
		if (server.getId() != bot.getServerId()) return;

		User user = event.getUser();

		try {
			synchronized (this) {
				Collection<ActiveActionEntry> actions = ActionQueries.getActiveDiscordUserActions(bot.getDatabase(), user.getId());
				long time = System.currentTimeMillis();

				for (ActiveActionEntry action : actions) {
					if (action.expirationTime() < 0 || action.expirationTime() > time) {
						try {
							action.type().activate(server, user.getId(), true, action.data() != null ? action.data().data() : 0, action.reason(), bot);
						} catch (Exception e) {
							LOGGER.warn("Error re-activating action on join", e);
						}
					}
				}
			}
		} catch (Exception e) {
			LOGGER.warn("Error checking existing actions on join", e);
		}
	}

	public boolean applyNickLock(Server server, User user) {
		try {
			String displayName = user.getDisplayName(server);
			String lockedNick = ActionQueries.getLockedNick(bot.getDatabase(), user.getId());

			if (lockedNick == null // no active nicklock
					|| lockedNick.equals(displayName)) { // permitted nick change
				return false;
			}

			if (user.getName().equals(lockedNick)) { // user name is fine, drop nick
				user.resetNickname(server, "nicklock");
			} else {
				user.updateNickname(server, lockedNick, "nicklock");
			}

			return true;
		} catch (Exception e) {
			LOGGER.warn("Error applying nicklock", e);
			return false;
		}
	}

	private void onMessage(CachedMessage message, Server server) {
		if (message.isDeleted()) return;

		try {
			Collection<ActiveActionEntry> actions = ActionQueries.getActiveDiscordUserActions(bot.getDatabase(), message.getAuthorDiscordId());
			if (actions.isEmpty()) return;

			long time = System.currentTimeMillis();

			for (ActiveActionEntry action : actions) {
				if ((action.expirationTime() < 0 || action.expirationTime() > time)
						&& action.type().blocksMessages()) {
					message.delete(server, "blocked by action %d".formatted(action.id()));
					break;
				}
			}

		} catch (Exception e) {
			LOGGER.warn("Error checking message against actions", e);
		}
	}

	@Override
	public void onServerMemberBan(ServerMemberBanEvent event) {
		Server server = event.getServer();
		if (server.getId() != bot.getServerId()) return;

		bot.getExecutor().execute(() -> {
			try {
				int targetUserId = bot.getUserHandler().getUserId(event.getUser());
				if (targetUserId < 0) return; // unknown target user

				if (ActionQueries.getActiveAction(bot.getDatabase(), targetUserId, UserActionType.BAN) != null) {
					// action exists
					return;
				}

				Ban ban = event.requestBan().join();
				if (ban == null) return; // not banned

				String reason = ban.getReason().orElse(null);
				if ("null".equals(reason)) reason = null; // appears to use "null" string value for unknown reasons..

				User actor = null;

				if (server.canYouViewAuditLog()) {
					for (int i = 0; i < 10; i++) {
						if (i > 0) Thread.sleep(100 << (i - 1)); // retry with .1, .2, .4, .8, 1.6, 3.2, 6.4, 12.8, 25.6 s delay

						AuditLog log = server.getAuditLog(10, AuditLogActionType.MEMBER_BAN_ADD).join();
						long latestMatch = -1;

						for (AuditLogEntry entry : log.getEntries()) {
							if (entry.getId() <= latestMatch) continue; // the most significant bits of the id are a high resolution timestamp, so it can be compared directly

							AuditLogEntryTarget target = entry.getTarget().orElse(null);
							String curReason = entry.getReason().orElse(null);

							if (target != null && target.getId() == ban.getUser().getId() && Objects.equals(reason, curReason)) {
								actor = entry.getUser().join();
							}
						}

						if (actor != null) break;
					}
				}

				if (actor != null && actor.getId() == bot.getUserHandler().getBotDiscordUserId()) {
					// banned by the bot
					return;
				}

				int actorUserId = actor != null ? bot.getUserHandler().getUserId(actor.getId()) : bot.getUserHandler().getBotUserId();
				reason = reason != null ? "discord import: ".concat(reason) : "discord import";

				ActionUtil.applyUserAction(UserActionType.BAN, 0, targetUserId, "permanent", reason,
						null, UserMessageAction.NONE,
						false, null,
						bot, server, null, actor, actorUserId);
			} catch (Throwable t) {
				LOGGER.warn("Error importing discord action", t);
			}
		});
	}
}
