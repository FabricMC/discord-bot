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

import org.jetbrains.annotations.Nullable;

import net.fabricmc.discord.bot.DiscordBot;
import net.fabricmc.discord.io.Channel;
import net.fabricmc.discord.io.Channel.PermissionOverwriteData;
import net.fabricmc.discord.io.DiscordException;
import net.fabricmc.discord.io.Permission;
import net.fabricmc.discord.io.Role;
import net.fabricmc.discord.io.Server;

public enum ChannelActionType implements ActionType {
	LOCK("lock", true) {
		@Override
		protected Long activate(Server server, Channel target, long data, @Nullable String reason, DiscordBot bot) throws DiscordException {
			if (!target.getType().text) return null;
			if (NOP_MODE) return 0L;

			Role role = server.getEveryoneRole();
			PermissionOverwriteData oldPerms = target.getPermissionOverwrites(role);
			long deniedMask = Permission.toMask(oldPerms.denied());
			long extraDenyMask = (restrictedPerms & ~deniedMask);
			if (extraDenyMask == 0) return 0L;

			target.setPermissionOverwrites(role, new PermissionOverwriteData(oldPerms.allowed(), Permission.fromMask(deniedMask | extraDenyMask)), reason);

			return extraDenyMask;
		}

		@Override
		protected void deactivate(Server server, Channel target, Long resetData, @Nullable String reason, DiscordBot bot) throws DiscordException {
			if (!target.getType().text) return;
			if (NOP_MODE) return;
			if (resetData == null) resetData = restrictedPerms;

			assert (resetData & ~restrictedPerms) == 0;

			Role role = server.getEveryoneRole();
			PermissionOverwriteData oldPerms = target.getPermissionOverwrites(role);
			long deniedMask = Permission.toMask(oldPerms.denied());
			if ((deniedMask & resetData) == 0) return;

			target.setPermissionOverwrites(role, new PermissionOverwriteData(oldPerms.allowed(), Permission.fromMask(deniedMask & ~resetData)), reason);
		}

		@Override
		protected boolean isActive(Server server, Channel target, long data, DiscordBot bot) {
			return target.getType().text
					&& (Permission.toMask(target.getPermissionOverwrites(server.getEveryoneRole()).denied()) & restrictedPerms) == restrictedPerms;
		}

		private final long restrictedPerms = Permission.ADD_REACTIONS.mask() | Permission.SEND_MESSAGES.mask();
	},
	SLOWMODE("slowmode", true) {
		@Override
		protected Long activate(Server server, Channel target, long data, @Nullable String reason, DiscordBot bot) throws DiscordException {
			assert data > 0;

			if (!target.getType().text) return null;
			if (NOP_MODE) return 0L;

			int oldSlowmode = target.getSlowmodeDelaySeconds();

			target.setSlowmodeDelaySeconds((int) data, reason);

			return (long) oldSlowmode;
		}

		@Override
		protected void deactivate(Server server, Channel target, Long resetData, @Nullable String reason, DiscordBot bot) throws DiscordException {
			if (!target.getType().text) return;
			if (NOP_MODE) return;
			if (resetData == null) resetData = 0L;

			if (target.getSlowmodeDelaySeconds() <= resetData) return;

			target.setSlowmodeDelaySeconds(resetData.intValue(), reason);
		}

		@Override
		protected boolean isActive(Server server, Channel target, long data, DiscordBot bot) {
			return target.getType().text
					&& target.getSlowmodeDelaySeconds() >= data;
		}

		@Override
		public int compareData(long dataA, long dataB) {
			return Long.compare(dataA, dataB);
		}

		@Override
		public boolean checkData(long data, long prevResetData) {
			return data > prevResetData;
		}
	};

	private static final boolean NOP_MODE = false; // no-op mode for testing

	public final String id;
	public final boolean hasDuration;
	private final boolean hasDeactivation;

	ChannelActionType(String id, boolean hasDuration) {
		this.id = id;
		this.hasDuration = hasDuration;
		this.hasDeactivation = UserActionType.isMethodOverridden(getClass(), "deactivate");
	}

	@Override
	public final Kind getKind() {
		return Kind.CHANNEL;
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
	public boolean blocksMessages() {
		return false;
	}

	@Override
	public boolean isNotificationBarrier() {
		return false;
	}

	@Override
	public boolean canRevertBeyondBotDb() {
		return false;
	}

	@Override
	public final ActivateResult activate(Server server, long targetId, boolean isDirect, long data, @Nullable String reason, DiscordBot bot) throws DiscordException {
		Channel channel = server.getChannel(targetId);

		Long resetData = activate(server, channel, data, reason, bot);

		return new ActivateResult(resetData != null, 1, resetData);
	}

	protected Long activate(Server server, Channel target, long data, @Nullable String reason, DiscordBot bot) throws DiscordException {
		return null;
	}

	@Override
	public final void deactivate(Server server, long targetId, Long resetData, @Nullable String reason, DiscordBot bot) throws DiscordException {
		if (!hasDeactivation) return;

		Channel targetChannel = server.getChannel(targetId);

		deactivate(server, targetChannel, resetData, reason, bot);
	}

	protected void deactivate(Server server, Channel target, Long resetData, @Nullable String reason, DiscordBot bot) throws DiscordException { }

	@Override
	public final boolean isActive(Server server, long targetId, long data, DiscordBot bot) {
		Channel targetChannel = server.getChannel(targetId);

		return isActive(server, targetChannel, data, bot);
	}

	protected boolean isActive(Server server, Channel target, long data, DiscordBot bot) {
		return false;
	}
}
