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
import java.util.List;

import it.unimi.dsi.fastutil.longs.LongList;
import org.javacord.api.entity.server.Server;
import org.javacord.api.entity.user.User;
import org.javacord.api.exception.DiscordException;
import org.javacord.api.exception.NotFoundException;

import net.fabricmc.discord.bot.DiscordBot;
import net.fabricmc.discord.bot.database.query.ActionQueries;
import net.fabricmc.discord.bot.util.DiscordUtil;

public enum UserActionType implements ActionType {
	BAN("ban", true, true, true, false) {
		@Override
		protected void activate(Server server, User target, String reason, DiscordBot bot) throws DiscordException {
			if (!NOP_MODE) DiscordUtil.join(server.banUser(target, 0, reason));
		}

		@Override
		protected void deactivate(Server server, long targetDiscordUserId, String reason, DiscordBot bot) throws DiscordException {
			if (!NOP_MODE) DiscordUtil.join(server.unbanUser(targetDiscordUserId, reason));
		}

		@Override
		protected boolean isActive(Server server, long targetDiscordUserId, DiscordBot bot) {
			return server.getBans().thenApply(bans -> bans.stream().anyMatch(ban -> ban.getUser().getId() == targetDiscordUserId)).join();
		}
	},
	KICK("kick", false, false, true, false) {
		@Override
		protected void activate(Server server, User target, String reason, DiscordBot bot) throws DiscordException {
			if (!NOP_MODE) DiscordUtil.join(server.kickUser(target, reason));
		}
	},
	MUTE("mute", true, true, false, false) {
		@Override
		protected void activate(Server server, User target, String reason, DiscordBot bot) throws DiscordException {
			if (!NOP_MODE) ActionUtil.addRole(server, target, ActionRole.MUTE, reason, bot);
		}

		@Override
		protected void deactivate(Server server, long targetDiscordUserId, String reason, DiscordBot bot) throws DiscordException {
			if (NOP_MODE) return;

			User target = server.getMemberById(targetDiscordUserId).orElse(null);

			if (target!= null) ActionUtil.removeRole(server, target, ActionRole.MUTE, reason, bot);
		}

		@Override
		protected boolean isActive(Server server, long targetDiscordUserId, DiscordBot bot) {
			User target = server.getMemberById(targetDiscordUserId).orElse(null);

			return target != null && ActionUtil.hasRole(server, target, ActionRole.MUTE, bot);
		}
	},
	META_MUTE("metaMute", true) {
		@Override
		protected void activate(Server server, User target, String reason, DiscordBot bot) throws DiscordException {
			if (!NOP_MODE) ActionUtil.addRole(server, target, ActionRole.META_MUTE, reason, bot);
		}

		@Override
		protected void deactivate(Server server, long targetDiscordUserId, String reason, DiscordBot bot) throws DiscordException {
			if (NOP_MODE) return;

			User target = server.getMemberById(targetDiscordUserId).orElse(null);

			if (target!= null) ActionUtil.removeRole(server, target, ActionRole.META_MUTE, reason, bot);
		}

		@Override
		protected boolean isActive(Server server, long targetDiscordUserId, DiscordBot bot) {
			User target = server.getMemberById(targetDiscordUserId).orElse(null);

			return target != null && ActionUtil.hasRole(server, target, ActionRole.META_MUTE, bot);
		}
	},
	REACTION_MUTE("reactionMute", true) {
		@Override
		protected void activate(Server server, User target, String reason, DiscordBot bot) throws DiscordException {
			if (!NOP_MODE) ActionUtil.addRole(server, target, ActionRole.REACTION_MUTE, reason, bot);
		}

		@Override
		protected void deactivate(Server server, long targetDiscordUserId, String reason, DiscordBot bot) throws DiscordException {
			if (NOP_MODE) return;

			User target = server.getMemberById(targetDiscordUserId).orElse(null);

			if (target!= null) ActionUtil.removeRole(server, target, ActionRole.REACTION_MUTE, reason, bot);
		}

		@Override
		protected boolean isActive(Server server, long targetDiscordUserId, DiscordBot bot) {
			User target = server.getMemberById(targetDiscordUserId).orElse(null);

			return target != null && ActionUtil.hasRole(server, target, ActionRole.REACTION_MUTE, bot);
		}
	},
	REQUESTS_MUTE("requestsMute", true) {
		@Override
		protected void activate(Server server, User target, String reason, DiscordBot bot) throws DiscordException {
			if (!NOP_MODE) ActionUtil.addRole(server, target, ActionRole.REQUESTS_MUTE, reason, bot);
		}

		@Override
		protected void deactivate(Server server, long targetDiscordUserId, String reason, DiscordBot bot) throws DiscordException {
			if (NOP_MODE) return;

			User target = server.getMemberById(targetDiscordUserId).orElse(null);

			if (target!= null) ActionUtil.removeRole(server, target, ActionRole.REQUESTS_MUTE, reason, bot);
		}

		@Override
		protected boolean isActive(Server server, long targetDiscordUserId, DiscordBot bot) {
			User target = server.getMemberById(targetDiscordUserId).orElse(null);

			return target != null && ActionUtil.hasRole(server, target, ActionRole.REQUESTS_MUTE, bot);
		}
	},
	SUPPORT_MUTE("supportMute", true) {
		@Override
		protected void activate(Server server, User target, String reason, DiscordBot bot) throws DiscordException {
			if (!NOP_MODE) ActionUtil.addRole(server, target, ActionRole.SUPPORT_MUTE, reason, bot);
		}

		@Override
		protected void deactivate(Server server, long targetDiscordUserId, String reason, DiscordBot bot) throws DiscordException {
			if (NOP_MODE) return;

			User target = server.getMemberById(targetDiscordUserId).orElse(null);

			if (target!= null) ActionUtil.removeRole(server, target, ActionRole.SUPPORT_MUTE, reason, bot);
		}

		@Override
		protected boolean isActive(Server server, long targetDiscordUserId, DiscordBot bot) {
			User target = server.getMemberById(targetDiscordUserId).orElse(null);

			return target != null && ActionUtil.hasRole(server, target, ActionRole.SUPPORT_MUTE, bot);
		}
	},
	NICK_LOCK("nickLock", true) {
		@Override
		protected void activate(Server server, User target, String reason, DiscordBot bot) {
			if (NOP_MODE) return;

			try {
				if (!ActionQueries.addNickLock(bot.getDatabase(), target.getId(), target.getDisplayName(server))) { // new nicklock gets applied by the caller
					bot.getActionSyncHandler().applyNickLock(server, target);
				}
			} catch (SQLException e) {
				throw new RuntimeException(e);
			}
		}

		@Override
		protected void deactivate(Server server, long targetDiscordUserId, String reason, DiscordBot bot) {
			try {
				if (!NOP_MODE) ActionQueries.removeNickLock(bot.getDatabase(), targetDiscordUserId);
			} catch (SQLException e) {
				throw new RuntimeException(e);
			}
		}

		@Override
		protected boolean isActive(Server server, long targetDiscordUserId, DiscordBot bot) {
			try {
				return ActionQueries.getLockedNick(bot.getDatabase(), targetDiscordUserId) != null;
			} catch (SQLException e) {
				throw new RuntimeException(e);
			}
		}
	},
	WARN("warn", false),
	DELETE_MESSAGE("deleteMessage", false, false, false, true),
	RENAME("rename", false, false, false, true);

	private static final boolean NOP_MODE = false; // no-op mode for testing

	public final String id;
	public final boolean hasDuration;
	private final boolean blocksMessages;
	private final boolean hasDeactivation;
	private final boolean notificationBarrier;
	public final boolean hasDedicatedCommand;

	public static UserActionType parse(String id) {
		for (UserActionType type : UserActionType.values()) {
			if (type.id.equals(id)) return type;
		}

		throw new IllegalArgumentException("invalid user action type: "+id);
	}

	UserActionType(String id, boolean hasDuration) {
		this(id, hasDuration, false, false, false);
	}

	UserActionType(String id, boolean hasDuration, boolean blocksMessages, boolean notificationBarrier, boolean hasDedicatedCommand) {
		this.id = id;
		this.hasDuration = hasDuration;
		this.blocksMessages = blocksMessages;
		this.hasDeactivation = isMethodOverridden(getClass(), "deactivate", Server.class, long.class, String.class, DiscordBot.class);
		this.notificationBarrier = notificationBarrier;
		this.hasDedicatedCommand = hasDedicatedCommand;
	}

	static boolean isMethodOverridden(Class<?> owner, String name, Class<?>... args) {
		if (owner == UserActionType.class) return false;

		try {
			try {
				owner.getDeclaredMethod(name, args); // throws if missing
				return true;
			} catch (NoSuchMethodException e) {
				owner.getSuperclass().getDeclaredMethod(name, args); // ensures the parent has it
				return false;
			}
		} catch (ReflectiveOperationException e) {
			throw new RuntimeException(e);
		}
	}

	@Override
	public final Kind getKind() {
		return Kind.USER;
	}

	@Override
	public final String getId() {
		return id;
	}

	@Override
	public final boolean hasDuration() {
		return hasDuration;
	}

	@Override
	public boolean blocksMessages() {
		return blocksMessages;
	}

	@Override
	public final boolean hasDeactivation() {
		return hasDeactivation;
	}

	@Override
	public boolean isNotificationBarrier() {
		return notificationBarrier;
	}

	@Override
	public final ActivateResult activate(Server server, long targetId, boolean isDirect, int data, String reason, DiscordBot bot) throws DiscordException {
		int count = 0;

		if (isDirect) {
			User user = server.getMemberById(targetId).orElse(null);

			if (user != null) {
				activate(server, user, reason, bot);
				count++;
			}
		} else {
			List<User> targets = bot.getUserHandler().getDiscordUsers((int) targetId, server);

			for (User user : targets) {
				try {
					activate(server, user, reason, bot);
					count++;
				} catch (NotFoundException e) {
					// ignore, user likely left between gathering users and executing the action
				}
			}
		}

		return new ActivateResult(true, count, null);
	}

	protected void activate(Server server, User target, String reason, DiscordBot bot) throws DiscordException { }

	@Override
	public final void deactivate(Server server, long targetId, Integer resetData, String reason, DiscordBot bot) throws DiscordException {
		if (!hasDeactivation) return;

		LongList targets = bot.getUserHandler().getDiscordUserIds((int) targetId);

		for (long discordUserId : targets) {
			try {
				deactivate(server, discordUserId, reason, bot);
			} catch (NotFoundException e) {
				// ignore, user likely left between gathering users and executing the action
			}
		}
	}

	protected void deactivate(Server server, long targetDiscordUserId, String reason, DiscordBot bot) throws DiscordException { }

	@Override
	public final boolean isActive(Server server, long targetId, int data, DiscordBot bot) {
		LongList targets = bot.getUserHandler().getDiscordUserIds((int) targetId);
		boolean ret = false;

		for (long discordUserId : targets) {
			ret |= isActive(server, discordUserId, bot);
		}

		return ret;
	}

	protected boolean isActive(Server server, long targetDiscordUserId, DiscordBot bot) {
		return false;
	}
}
