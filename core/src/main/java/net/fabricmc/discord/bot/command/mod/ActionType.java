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

import org.javacord.api.entity.server.Server;
import org.javacord.api.entity.user.User;

import net.fabricmc.discord.bot.DiscordBot;
import net.fabricmc.discord.bot.database.query.ActionQueries;

public enum ActionType {
	BAN("ban", true, "banned") {
		@Override
		public void activate(Server server, User target, String reason, DiscordBot bot) {
			if (!NOP_MODE) server.banUser(target, 0, reason).join();
		}

		@Override
		public void deactivate(Server server, long targetDiscordUserId, String reason, DiscordBot bot) {
			if (!NOP_MODE) server.unbanUser(targetDiscordUserId, reason).join();
		}

		@Override
		public boolean isActive(Server server, long targetDiscordUserId, DiscordBot bot) {
			return server.getBans().thenApply(bans -> bans.stream().anyMatch(ban -> ban.getUser().getId() == targetDiscordUserId)).join();
		}
	},
	KICK("kick", false, "kicked") {
		@Override
		public void activate(Server server, User target, String reason, DiscordBot bot) {
			if (!NOP_MODE) server.kickUser(target, reason).join();
		}
	},
	MUTE("mute", true, "muted") {
		@Override
		public void activate(Server server, User target, String reason, DiscordBot bot) {
			if (!NOP_MODE) ActionUtil.addRole(server, target, ActionRole.MUTE, reason, bot);
		}

		@Override
		public void deactivate(Server server, long targetDiscordUserId, String reason, DiscordBot bot) {
			if (NOP_MODE) return;

			User target = server.getMemberById(targetDiscordUserId).orElse(null);

			if (target!= null) ActionUtil.removeRole(server, target, ActionRole.MUTE, reason, bot);
		}

		@Override
		public boolean isActive(Server server, long targetDiscordUserId, DiscordBot bot) {
			User target = server.getMemberById(targetDiscordUserId).orElse(null);

			return target != null && ActionUtil.hasRole(server, target, ActionRole.MUTE, bot);
		}
	},
	META_MUTE("metaMute", true, "meta muted") {
		@Override
		public void activate(Server server, User target, String reason, DiscordBot bot) {
			if (!NOP_MODE) ActionUtil.addRole(server, target, ActionRole.META_MUTE, reason, bot);
		}

		@Override
		public void deactivate(Server server, long targetDiscordUserId, String reason, DiscordBot bot) {
			if (NOP_MODE) return;

			User target = server.getMemberById(targetDiscordUserId).orElse(null);

			if (target!= null) ActionUtil.removeRole(server, target, ActionRole.META_MUTE, reason, bot);
		}

		@Override
		public boolean isActive(Server server, long targetDiscordUserId, DiscordBot bot) {
			User target = server.getMemberById(targetDiscordUserId).orElse(null);

			return target != null && ActionUtil.hasRole(server, target, ActionRole.META_MUTE, bot);
		}
	},
	REACTION_MUTE("reactionMute", true, "reaction muted") {
		@Override
		public void activate(Server server, User target, String reason, DiscordBot bot) {
			if (!NOP_MODE) ActionUtil.addRole(server, target, ActionRole.REACTION_MUTE, reason, bot);
		}

		@Override
		public void deactivate(Server server, long targetDiscordUserId, String reason, DiscordBot bot) {
			if (NOP_MODE) return;

			User target = server.getMemberById(targetDiscordUserId).orElse(null);

			if (target!= null) ActionUtil.removeRole(server, target, ActionRole.REACTION_MUTE, reason, bot);
		}

		@Override
		public boolean isActive(Server server, long targetDiscordUserId, DiscordBot bot) {
			User target = server.getMemberById(targetDiscordUserId).orElse(null);

			return target != null && ActionUtil.hasRole(server, target, ActionRole.REACTION_MUTE, bot);
		}
	},
	REQUESTS_MUTE("requestsMute", true, "requests muted") {
		@Override
		public void activate(Server server, User target, String reason, DiscordBot bot) {
			if (!NOP_MODE) ActionUtil.addRole(server, target, ActionRole.REQUESTS_MUTE, reason, bot);
		}

		@Override
		public void deactivate(Server server, long targetDiscordUserId, String reason, DiscordBot bot) {
			if (NOP_MODE) return;

			User target = server.getMemberById(targetDiscordUserId).orElse(null);

			if (target!= null) ActionUtil.removeRole(server, target, ActionRole.REQUESTS_MUTE, reason, bot);
		}

		@Override
		public boolean isActive(Server server, long targetDiscordUserId, DiscordBot bot) {
			User target = server.getMemberById(targetDiscordUserId).orElse(null);

			return target != null && ActionUtil.hasRole(server, target, ActionRole.REQUESTS_MUTE, bot);
		}
	},
	SUPPORT_MUTE("supportMute", true, "support muted") {
		@Override
		public void activate(Server server, User target, String reason, DiscordBot bot) {
			if (!NOP_MODE) ActionUtil.addRole(server, target, ActionRole.SUPPORT_MUTE, reason, bot);
		}

		@Override
		public void deactivate(Server server, long targetDiscordUserId, String reason, DiscordBot bot) {
			if (NOP_MODE) return;

			User target = server.getMemberById(targetDiscordUserId).orElse(null);

			if (target!= null) ActionUtil.removeRole(server, target, ActionRole.SUPPORT_MUTE, reason, bot);
		}

		@Override
		public boolean isActive(Server server, long targetDiscordUserId, DiscordBot bot) {
			User target = server.getMemberById(targetDiscordUserId).orElse(null);

			return target != null && ActionUtil.hasRole(server, target, ActionRole.SUPPORT_MUTE, bot);
		}
	},
	NICK_LOCK("nickLock", true, "nick locked") {
		@Override
		public void activate(Server server, User target, String reason, DiscordBot bot) {
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
		public void deactivate(Server server, long targetDiscordUserId, String reason, DiscordBot bot) {
			try {
				if (!NOP_MODE) ActionQueries.removeNickLock(bot.getDatabase(), targetDiscordUserId);
			} catch (SQLException e) {
				throw new RuntimeException(e);
			}
		}

		@Override
		public boolean isActive(Server server, long targetDiscordUserId, DiscordBot bot) {
			try {
				return ActionQueries.getLockedNick(bot.getDatabase(), targetDiscordUserId) != null;
			} catch (SQLException e) {
				throw new RuntimeException(e);
			}
		}
	},
	WARN("warn", false, "warned"),
	RENAME("rename", false, "renamed", true);

	private static final boolean NOP_MODE = true; // no-op mode for testing

	public final String id;
	public final boolean hasDuration;
	public final boolean hasDeactivation;
	private final String actionDesc;
	public final boolean hasDedicatedCommand;

	public static ActionType get(String id) {
		for (ActionType type : values()) {
			if (type.id.equals(id)) {
				return type;
			}
		}

		throw new IllegalArgumentException("invalid type: "+id);
	}

	ActionType(String id, boolean hasDuration, String actionDesc) {
		this(id, hasDuration, actionDesc, false);
	}

	ActionType(String id, boolean hasDuration, String actionDesc, boolean hasDedicatedCommand) {
		this.id = id;
		this.hasDuration = hasDuration;
		this.actionDesc = actionDesc;
		this.hasDedicatedCommand = hasDedicatedCommand;

		try {
			this.hasDeactivation = getClass().getMethod("deactivate", Server.class, long.class, String.class, DiscordBot.class).getDeclaringClass() != ActionType.class; // deactivate was overridden
		} catch (ReflectiveOperationException e) {
			throw new RuntimeException(e);
		}
	}

	public String getDesc(boolean reversal) {
		return reversal ? "un"+actionDesc : actionDesc;
	}

	public void activate(Server server, User target, String reason, DiscordBot bot) { }

	public void deactivate(Server server, long targetDiscordUserId, String reason, DiscordBot bot) { }

	public boolean isActive(Server server, long targetDiscordUserId, DiscordBot bot) {
		return false;
	}
}
