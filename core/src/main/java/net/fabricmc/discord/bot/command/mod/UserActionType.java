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

import org.javacord.api.entity.server.Server;
import org.javacord.api.entity.user.User;
import org.javacord.api.exception.DiscordException;
import org.javacord.api.exception.NotFoundException;

import net.fabricmc.discord.bot.DiscordBot;
import net.fabricmc.discord.bot.database.query.ActionQueries;

public enum UserActionType implements ActionType {
	BAN("ban", true, "banned", "unbanned") {
		@Override
		protected void activate(Server server, User target, String reason, DiscordBot bot) {
			if (!NOP_MODE) server.banUser(target, 0, reason).join();
		}

		@Override
		protected void deactivate(Server server, long targetDiscordUserId, String reason, DiscordBot bot) {
			if (!NOP_MODE) server.unbanUser(targetDiscordUserId, reason).join();
		}

		@Override
		protected boolean isActive(Server server, long targetDiscordUserId, DiscordBot bot) {
			return server.getBans().thenApply(bans -> bans.stream().anyMatch(ban -> ban.getUser().getId() == targetDiscordUserId)).join();
		}
	},
	KICK("kick", false, "kicked", null) {
		@Override
		protected void activate(Server server, User target, String reason, DiscordBot bot) {
			if (!NOP_MODE) server.kickUser(target, reason).join();
		}
	},
	MUTE("mute", true, "muted", "unmuted") {
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
	META_MUTE("metaMute", true, "meta muted", "meta unmuted") {
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
	REACTION_MUTE("reactionMute", true, "reaction muted", "reaction unmuted") {
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
	REQUESTS_MUTE("requestsMute", true, "requests muted", "requests unmuted") {
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
	SUPPORT_MUTE("supportMute", true, "support muted", "support unmuted") {
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
	NICK_LOCK("nickLock", true, "nick locked", "nick unlocked") {
		@Override
		protected void activate(Server server, User target, String reason, DiscordBot bot) {
			if (NOP_MODE) return;

			try {
				if (!ActionQueries.addNickLock(bot.getDatabase(), target.getId(), target.getDisplayName(server))) {
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
	WARN("warn", false, "warned", null),
	RENAME("rename", false, "renamed", null, true);

	private static final boolean NOP_MODE = false; // no-op mode for testing

	public final String id;
	public final boolean hasDuration;
	private final boolean hasDeactivation;
	private final String actionDesc;
	private final String revActionDesc;
	public final boolean hasDedicatedCommand;

	UserActionType(String id, boolean hasDuration, String actionDesc, String revActionDesc) {
		this(id, hasDuration, actionDesc, revActionDesc, false);
	}

	UserActionType(String id, boolean hasDuration, String actionDesc, String revActionDesc, boolean hasDedicatedCommand) {
		if (revActionDesc == null && hasDuration) throw new NullPointerException("null reverse action desc for reversible action");

		this.id = id;
		this.hasDuration = hasDuration;
		this.hasDeactivation = isMethodOverridden(getClass(), "deactivate", Server.class, long.class, String.class, DiscordBot.class);
		this.actionDesc = actionDesc;
		this.revActionDesc = revActionDesc;
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
	public final boolean hasDeactivation() {
		return hasDeactivation;
	}

	@Override
	public final String getDesc(boolean reversal) {
		return reversal ? revActionDesc : actionDesc;
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

		List<Long> targets = bot.getUserHandler().getDiscordUserIds((int) targetId);

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
		List<Long> targets = bot.getUserHandler().getDiscordUserIds((int) targetId);
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
