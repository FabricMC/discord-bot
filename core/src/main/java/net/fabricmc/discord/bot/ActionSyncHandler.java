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
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.javacord.api.entity.channel.ServerChannel;
import org.javacord.api.entity.server.Server;
import org.javacord.api.entity.user.User;
import org.javacord.api.event.server.member.ServerMemberJoinEvent;
import org.javacord.api.listener.ChainableGloballyAttachableListenerManager;
import org.javacord.api.listener.server.member.ServerMemberJoinListener;

import net.fabricmc.discord.bot.command.mod.ActionUtil;
import net.fabricmc.discord.bot.database.query.UserActionQueries;
import net.fabricmc.discord.bot.database.query.UserActionQueries.UserActionEntry;
import net.fabricmc.discord.bot.database.query.UserActionQueries.ActiveUserActionEntry;
import net.fabricmc.discord.bot.database.query.UserActionQueries.ExpiringUserActionEntry;
import net.fabricmc.discord.bot.database.query.ChannelActionQueries;
import net.fabricmc.discord.bot.database.query.ChannelActionQueries.ActiveChannelActionEntry;
import net.fabricmc.discord.bot.database.query.ChannelActionQueries.ChannelActionEntry;
import net.fabricmc.discord.bot.database.query.ChannelActionQueries.ExpiringChannelActionEntry;

/**
 * Mechanism to keep Discord up to date with the bot's actions.
 *
 * <p>This handles time based expiration of temporary actions and re-application of actions that Discord doesn't
 * persistently enforce itself.
 */
public final class ActionSyncHandler implements ServerMemberJoinListener {
	private static final int expirationWindowMinutes = 120; // only schedule tasks for the near future in Java

	private final DiscordBot bot;
	private Server server;
	private Future<?> expirationsUpdateFuture; // task for periodically scheduling expirations
	private final Map<Integer, Future<?>> scheduledActionExpirations = new HashMap<>(); // tasks for every actual expiring action within the expiration window
	private final Map<Integer, Future<?>> scheduledChannelActionExpirations = new HashMap<>(); // tasks for every actual expiring action within the expiration window

	ActionSyncHandler(DiscordBot bot) {
		this.bot = bot;

		bot.getActiveHandler().registerReadyHandler(this::onReady);
		bot.getActiveHandler().registerGoneHandler(this::onGone);
	}

	private synchronized void onReady(Server server, long prevActive) {
		// re-activate active actions for all members and channels

		this.server = server;

		Collection<User> users = server.getMembers();
		Collection<Long> discordUserIds = new ArrayList<>(users.size());

		for (User user : users) {
			discordUserIds.add(user.getId());
		}

		try {
			long time = System.currentTimeMillis();
			Collection<ActiveUserActionEntry> activeActions = UserActionQueries.getActiveActions(bot.getDatabase(), discordUserIds);

			for (ActiveUserActionEntry action : activeActions) {
				User user = server.getMemberById(action.targetDiscordUserId()).orElse(null);
				if (user == null) continue;

				if (action.expirationTime() < 0 || action.expirationTime() > time) {
					try {
						action.type().activate(server, user, action.reason(), bot);
					} catch (Exception e) {
						// TODO Auto-generated catch block
						e.printStackTrace();
					}
				}
			}

			Collection<ActiveChannelActionEntry> activeChannelActions = ChannelActionQueries.getActiveActions(bot.getDatabase());

			for (ActiveChannelActionEntry action : activeChannelActions) {
				ServerChannel channel = server.getChannelById(action.channelId()).orElse(null);
				if (channel == null) continue;

				if (action.expirationTime() < 0 || action.expirationTime() > time) {
					try {
						action.type().activate(server, channel, action.data(), action.reason(), bot);
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

		for (Future<?> future : scheduledActionExpirations.values()) {
			future.cancel(false);
		}

		scheduledActionExpirations.clear();

		for (Future<?> future : scheduledChannelActionExpirations.values()) {
			future.cancel(false);
		}

		scheduledChannelActionExpirations.clear();
	}

	private synchronized void updateExpirations() {
		if (server == null) return; // server gone

		long time = System.currentTimeMillis();
		long maxTime = time + expirationWindowMinutes * 60_000L;

		try {
			for (ExpiringUserActionEntry entry : UserActionQueries.getExpiringActions(bot.getDatabase(), maxTime)) {
				addUserEntry(entry, time);
			}

			for (ExpiringChannelActionEntry entry : ChannelActionQueries.getExpiringActions(bot.getDatabase(), maxTime)) {
				addChannelEntry(entry, time);
			}
		} catch (SQLException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}

	public void onNewUserAction(UserActionEntry entry) {
		if (entry.expirationTime() <= 0) return; // no expiration

		long time = System.currentTimeMillis();
		if (time + expirationWindowMinutes * 60_000L < entry.expirationTime()) return;

		ExpiringUserActionEntry expEntry = new ExpiringUserActionEntry(entry.id(), entry.type(), entry.targetUserId(), entry.expirationTime());

		try {
			synchronized (this) {
				// make sure the expiration didn't execute yet
				// (updateExpirations may have run between creating the action and calling notify)

				if (!UserActionQueries.isExpiringAction(bot.getDatabase(), entry.id())) {
					return;
				}

				addUserEntry(expEntry, time);
			}
		} catch (SQLException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}

	private void addUserEntry(ExpiringUserActionEntry entry, long time) {
		if (server == null) return; // server gone

		long delay = entry.expirationTime() - time;

		if (delay <= 0) {
			expireUserAction(entry, false);
		} else if (!scheduledActionExpirations.containsKey(entry.id())) {
			scheduledActionExpirations.put(entry.id(), bot.getScheduledExecutor().schedule(() -> expireUserAction(entry, true), delay, TimeUnit.MILLISECONDS));
		}
	}

	private synchronized void expireUserAction(ExpiringUserActionEntry entry, boolean scheduled) {
		if (server == null) return; // server gone
		if (scheduled && scheduledActionExpirations.remove(entry.id()) == null) return; // no longer valid

		try {
			ActionUtil.expireUserAction(entry, bot, server);
		} catch (Exception e) {
			// TODO Auto-generated catch block
			e.printStackTrace();

			if (server != null) {
				scheduledActionExpirations.put(entry.id(), bot.getScheduledExecutor().schedule(() -> expireUserAction(entry, true), 5, TimeUnit.MINUTES)); // retry after 5 min
			}
		}
	}

	public synchronized void onUserActionSuspension(int actionId) {
		// cancel expiration, the suspension handling already reverted the action
		Future<?> future = scheduledActionExpirations.remove(actionId);
		if (future != null) future.cancel(false);
	}

	public void onNewChannelAction(ChannelActionEntry entry) {
		if (entry.expirationTime() <= 0) return; // no expiration

		long time = System.currentTimeMillis();
		if (time + expirationWindowMinutes * 60_000L < entry.expirationTime()) return;

		ExpiringChannelActionEntry expEntry = new ExpiringChannelActionEntry(entry.id(), entry.type(), entry.channelId(), entry.resetData(), entry.expirationTime());

		try {
			synchronized (this) {
				// make sure the expiration didn't execute yet
				// (updateExpirations may have run between creating the action and calling notify)

				if (!ChannelActionQueries.isExpiringAction(bot.getDatabase(), entry.id())) {
					return;
				}

				addChannelEntry(expEntry, time);
			}
		} catch (SQLException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}

	private void addChannelEntry(ExpiringChannelActionEntry entry, long time) {
		if (server == null) return; // server gone

		long delay = entry.expirationTime() - time;

		if (delay <= 0) {
			expireChannelAction(entry, false);
		} else if (!scheduledChannelActionExpirations.containsKey(entry.id())) {
			scheduledChannelActionExpirations.put(entry.id(), bot.getScheduledExecutor().schedule(() -> expireChannelAction(entry, true), delay, TimeUnit.MILLISECONDS));
		}
	}

	private synchronized void expireChannelAction(ExpiringChannelActionEntry entry, boolean scheduled) {
		if (server == null) return; // server gone
		if (scheduled && scheduledChannelActionExpirations.remove(entry.id()) == null) return; // no longer valid

		try {
			ActionUtil.expireChannelAction(entry, bot, server);
		} catch (Exception e) {
			// TODO Auto-generated catch block
			e.printStackTrace();

			if (server != null) {
				scheduledChannelActionExpirations.put(entry.id(), bot.getScheduledExecutor().schedule(() -> expireChannelAction(entry, true), 5, TimeUnit.MINUTES)); // retry after 5 min
			}
		}
	}

	public synchronized void onChannelActionSuspension(int channelActionId) {
		// cancel expiration, the suspension handling already reverted the action
		Future<?> future = scheduledChannelActionExpirations.remove(channelActionId);
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
				Collection<ActiveUserActionEntry> actions = UserActionQueries.getActiveActions(bot.getDatabase(), user.getId());
				long time = System.currentTimeMillis();

				for (ActiveUserActionEntry action : actions) {
					if (action.expirationTime() < 0 || action.expirationTime() > time) {
						try {
							action.type().activate(server, user, action.reason(), bot);
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
			String lockedNick = UserActionQueries.getLockedNick(bot.getDatabase(), user.getId());

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
