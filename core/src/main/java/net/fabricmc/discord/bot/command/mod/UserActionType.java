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

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;

import it.unimi.dsi.fastutil.longs.LongList;
import org.jetbrains.annotations.Nullable;

import net.fabricmc.discord.bot.DiscordBot;
import net.fabricmc.discord.bot.database.query.ActionQueries;
import net.fabricmc.discord.io.DiscordException;
import net.fabricmc.discord.io.Member;
import net.fabricmc.discord.io.NotFoundException;
import net.fabricmc.discord.io.Server;

public enum UserActionType implements ActionType {
	BAN("ban", true, true, true, false) {
		@Override
		protected void activate(Server server, Member target, @Nullable String reason, DiscordBot bot) throws DiscordException {
			if (!NOP_MODE) target.ban(Duration.ZERO, reason);
		}

		@Override
		protected void deactivate(Server server, long targetDiscordUserId, @Nullable String reason, DiscordBot bot) throws DiscordException {
			if (!NOP_MODE) server.unban(targetDiscordUserId, reason);
		}

		@Override
		protected boolean isActive(Server server, long targetDiscordUserId, DiscordBot bot) {
			return server.getBan(targetDiscordUserId) != null;
		}
	},
	KICK("kick", false, false, true, false) {
		@Override
		protected void activate(Server server, Member target, @Nullable String reason, DiscordBot bot) throws DiscordException {
			if (!NOP_MODE) target.kick(reason);
		}
	},
	MUTE("mute", true, true, false, false) {
		@Override
		protected void activate(Server server, Member target, @Nullable String reason, DiscordBot bot) throws DiscordException {
			if (!NOP_MODE) ActionUtil.addRole(target, ActionRole.MUTE, reason, bot);
		}

		@Override
		protected void deactivate(Server server, long targetDiscordUserId, @Nullable String reason, DiscordBot bot) throws DiscordException {
			if (NOP_MODE) return;

			Member target = server.getMember(targetDiscordUserId);

			if (target!= null) ActionUtil.removeRole(target, ActionRole.MUTE, reason, bot);
		}

		@Override
		protected boolean isActive(Server server, long targetDiscordUserId, DiscordBot bot) {
			Member target = server.getMember(targetDiscordUserId);

			return target != null && ActionUtil.hasRole(target, ActionRole.MUTE, bot);
		}
	},
	META_MUTE("metaMute", true) {
		@Override
		protected void activate(Server server, Member target, @Nullable String reason, DiscordBot bot) throws DiscordException {
			if (!NOP_MODE) ActionUtil.addRole(target, ActionRole.META_MUTE, reason, bot);
		}

		@Override
		protected void deactivate(Server server, long targetDiscordUserId, @Nullable String reason, DiscordBot bot) throws DiscordException {
			if (NOP_MODE) return;

			Member target = server.getMember(targetDiscordUserId);

			if (target!= null) ActionUtil.removeRole(target, ActionRole.META_MUTE, reason, bot);
		}

		@Override
		protected boolean isActive(Server server, long targetDiscordUserId, DiscordBot bot) {
			Member target = server.getMember(targetDiscordUserId);

			return target != null && ActionUtil.hasRole(target, ActionRole.META_MUTE, bot);
		}
	},
	REACTION_MUTE("reactionMute", true) {
		@Override
		protected void activate(Server server, Member target, @Nullable String reason, DiscordBot bot) throws DiscordException {
			if (!NOP_MODE) ActionUtil.addRole(target, ActionRole.REACTION_MUTE, reason, bot);
		}

		@Override
		protected void deactivate(Server server, long targetDiscordUserId, @Nullable String reason, DiscordBot bot) throws DiscordException {
			if (NOP_MODE) return;

			Member target = server.getMember(targetDiscordUserId);

			if (target!= null) ActionUtil.removeRole(target, ActionRole.REACTION_MUTE, reason, bot);
		}

		@Override
		protected boolean isActive(Server server, long targetDiscordUserId, DiscordBot bot) {
			Member target = server.getMember(targetDiscordUserId);

			return target != null && ActionUtil.hasRole(target, ActionRole.REACTION_MUTE, bot);
		}
	},
	REQUESTS_MUTE("requestsMute", true) {
		@Override
		protected void activate(Server server, Member target, @Nullable String reason, DiscordBot bot) throws DiscordException {
			if (!NOP_MODE) ActionUtil.addRole(target, ActionRole.REQUESTS_MUTE, reason, bot);
		}

		@Override
		protected void deactivate(Server server, long targetDiscordUserId, @Nullable String reason, DiscordBot bot) throws DiscordException {
			if (NOP_MODE) return;

			Member target = server.getMember(targetDiscordUserId);

			if (target!= null) ActionUtil.removeRole(target, ActionRole.REQUESTS_MUTE, reason, bot);
		}

		@Override
		protected boolean isActive(Server server, long targetDiscordUserId, DiscordBot bot) {
			Member target = server.getMember(targetDiscordUserId);

			return target != null && ActionUtil.hasRole(target, ActionRole.REQUESTS_MUTE, bot);
		}
	},
	SUPPORT_MUTE("supportMute", true) {
		@Override
		protected void activate(Server server, Member target, @Nullable String reason, DiscordBot bot) throws DiscordException {
			if (!NOP_MODE) ActionUtil.addRole(target, ActionRole.SUPPORT_MUTE, reason, bot);
		}

		@Override
		protected void deactivate(Server server, long targetDiscordUserId, @Nullable String reason, DiscordBot bot) throws DiscordException {
			if (NOP_MODE) return;

			Member target = server.getMember(targetDiscordUserId);

			if (target!= null) ActionUtil.removeRole(target, ActionRole.SUPPORT_MUTE, reason, bot);
		}

		@Override
		protected boolean isActive(Server server, long targetDiscordUserId, DiscordBot bot) {
			Member target = server.getMember(targetDiscordUserId);

			return target != null && ActionUtil.hasRole(target, ActionRole.SUPPORT_MUTE, bot);
		}
	},
	NICK_LOCK("nickLock", true) {
		@Override
		protected void activate(Server server, Member target, @Nullable String reason, DiscordBot bot) {
			if (NOP_MODE) return;

			try {
				if (!ActionQueries.addNickLock(bot.getDatabase(), target.getId(), target.getDisplayName())) { // new nicklock gets applied by the caller
					bot.getActionSyncHandler().applyNickLock(target);
				}
			} catch (SQLException e) {
				throw new RuntimeException(e);
			}
		}

		@Override
		protected void deactivate(Server server, long targetDiscordUserId, @Nullable String reason, DiscordBot bot) {
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
	DELETE_MESSAGE("deleteMessage", false, false, false, true) {
		@Override
		public boolean requiresTargetPresence() {
			return false;
		}
	},
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
		this.hasDeactivation = isMethodOverridden(getClass(), "deactivate");
		this.notificationBarrier = notificationBarrier;
		this.hasDedicatedCommand = hasDedicatedCommand;
	}

	static boolean isMethodOverridden(Class<?> owner, String name) {
		if (owner == UserActionType.class) return false;

		if (hasVirtualMethod(owner, name)) return true; // throws if missing
		if (hasVirtualMethod(owner.getSuperclass(), name)) return false; // ensures the parent has it

		throw new IllegalArgumentException("invalid method: "+name);
	}

	private static boolean hasVirtualMethod(Class<?> owner, String name) {
		boolean ret = false;

		for (Method m : owner.getDeclaredMethods()) {
			if (!m.getName().equals(name)) continue;
			int mod = m.getModifiers();
			if (Modifier.isStatic(mod) || Modifier.isFinal(mod) || Modifier.isPrivate(mod)) continue;

			if (ret) throw new IllegalArgumentException("non-unique virtual method "+name+" in "+owner.getName());

			ret = true;
		}

		return ret;
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
	public final ActivateResult activate(Server server, long targetId, boolean isDirect, long data, @Nullable String reason, DiscordBot bot) throws DiscordException {
		int count = 0;

		if (isDirect) {
			Member user = server.getMember(targetId);

			if (user != null) {
				activate(server, user, reason, bot);
				count++;
			}
		} else {
			List<Member> targets = bot.getUserHandler().getDiscordUsers((int) targetId, server);

			for (Member user : targets) {
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

	protected void activate(Server server, Member target, @Nullable String reason, DiscordBot bot) throws DiscordException { }

	@Override
	public final void deactivate(Server server, long targetId, Long resetData, @Nullable String reason, DiscordBot bot) throws DiscordException {
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

	protected void deactivate(Server server, long targetDiscordUserId, @Nullable String reason, DiscordBot bot) throws DiscordException { }

	@Override
	public final boolean isActive(Server server, long targetId, long data, DiscordBot bot) {
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
