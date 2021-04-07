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

package net.fabricmc.discord.bot;

import java.sql.SQLException;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.javacord.api.entity.server.Server;
import org.javacord.api.entity.user.User;
import org.javacord.api.event.server.member.ServerMemberJoinEvent;
import org.javacord.api.listener.ChainableGloballyAttachableListenerManager;
import org.javacord.api.listener.server.member.ServerMemberJoinListener;

import net.fabricmc.discord.bot.command.mod.ActionUtil;
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
public final class ActionSyncHandler implements ServerMemberJoinListener {
	private static final int expirationWindowMinutes = 120; // only schedule tasks for the near future in Java
	private static final int retryDelayMinutes = 5;

	private final DiscordBot bot;
	private Server server;
	private Future<?> expirationsUpdateFuture; // task for periodically scheduling expirations
	private final Map<Integer, Future<?>> scheduledExpirations = new HashMap<>(); // tasks for every actual expiring action within the expiration window

	ActionSyncHandler(DiscordBot bot) {
		this.bot = bot;

		bot.getActiveHandler().registerReadyHandler(this::onReady);
		bot.getActiveHandler().registerGoneHandler(this::onGone);
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
						// TODO Auto-generated catch block
						e.printStackTrace();
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
			// TODO Auto-generated catch block
			e.printStackTrace();
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
			// TODO Auto-generated catch block
			e.printStackTrace();
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
			// TODO Auto-generated catch block
			e.printStackTrace();

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
							// TODO Auto-generated catch block
							e.printStackTrace();
						}
					}
				}
			}
		} catch (Exception e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
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
			// TODO Auto-generated catch block
			e.printStackTrace();
			return false;
		}
	}
}
