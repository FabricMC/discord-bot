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

import org.javacord.api.entity.channel.RegularServerChannel;
import org.javacord.api.entity.channel.ServerChannel;
import org.javacord.api.entity.channel.ServerTextChannel;
import org.javacord.api.entity.permission.PermissionType;
import org.javacord.api.entity.permission.Permissions;
import org.javacord.api.entity.permission.Role;
import org.javacord.api.entity.server.Server;
import org.javacord.api.exception.DiscordException;
import org.jetbrains.annotations.Nullable;

import net.fabricmc.discord.bot.DiscordBot;
import net.fabricmc.discord.bot.util.DiscordUtil;

public enum ChannelActionType implements ActionType {
	LOCK("lock", true) {
		@SuppressWarnings("unchecked")
		@Override
		protected Long activate(Server server, ServerChannel target, long data, @Nullable String reason, DiscordBot bot) throws DiscordException {
			if (!(target instanceof RegularServerChannel)) return null;
			if (NOP_MODE) return 0L;

			RegularServerChannel channel = (RegularServerChannel) target;
			Role role = server.getEveryoneRole();
			Permissions oldPerms = channel.getOverwrittenPermissions(role);
			long extraDenyMask = (restrictedPerms & ~oldPerms.getDeniedBitmask());
			if (extraDenyMask == 0) return 0L;

			DiscordUtil.join(channel.createUpdater()
					.addPermissionOverwrite(role, Permissions.fromBitmask(oldPerms.getAllowedBitmask(), oldPerms.getDeniedBitmask() | extraDenyMask))
					.setAuditLogReason(reason)
					.update());

			return extraDenyMask;
		}

		@SuppressWarnings("unchecked")
		@Override
		protected void deactivate(Server server, ServerChannel target, Long resetData, @Nullable String reason, DiscordBot bot) throws DiscordException {
			if (!(target instanceof RegularServerChannel)) return;
			if (NOP_MODE) return;
			if (resetData == null) resetData = restrictedPerms;

			assert (resetData & ~restrictedPerms) == 0;

			RegularServerChannel channel = (RegularServerChannel) target;
			Role role = server.getEveryoneRole();
			Permissions oldPerms = channel.getOverwrittenPermissions(role);
			if ((oldPerms.getDeniedBitmask() & resetData) == 0) return;

			DiscordUtil.join(channel.createUpdater()
					.addPermissionOverwrite(role, Permissions.fromBitmask(oldPerms.getAllowedBitmask(), oldPerms.getDeniedBitmask() & ~resetData))
					.setAuditLogReason(reason)
					.update());
		}

		@Override
		protected boolean isActive(Server server, ServerChannel target, long data, DiscordBot bot) {
			return target instanceof RegularServerChannel
					&& (((RegularServerChannel) target).getOverwrittenPermissions(server.getEveryoneRole()).getDeniedBitmask() & restrictedPerms) == restrictedPerms;
		}

		private final long restrictedPerms = PermissionType.ADD_REACTIONS.getValue() | PermissionType.SEND_MESSAGES.getValue();
	},
	SLOWMODE("slowmode", true) {
		@Override
		protected Long activate(Server server, ServerChannel target, long data, @Nullable String reason, DiscordBot bot) throws DiscordException {
			assert data > 0;

			if (!(target instanceof ServerTextChannel)) return null;
			if (NOP_MODE) return 0L;

			ServerTextChannel channel = (ServerTextChannel) target;
			int oldSlowmode = channel.getSlowmodeDelayInSeconds();

			DiscordUtil.join(channel.createUpdater()
					.setSlowmodeDelayInSeconds((int) data)
					.setAuditLogReason(reason)
					.update());

			return (long) oldSlowmode;
		}

		@Override
		protected void deactivate(Server server, ServerChannel target, Long resetData, @Nullable String reason, DiscordBot bot) throws DiscordException {
			if (!(target instanceof ServerTextChannel)) return;
			if (NOP_MODE) return;
			if (resetData == null) resetData = 0L;

			ServerTextChannel channel = (ServerTextChannel) target;
			if (channel.getSlowmodeDelayInSeconds() <= resetData) return;

			DiscordUtil.join(channel.createUpdater()
					.setSlowmodeDelayInSeconds(resetData.intValue())
					.setAuditLogReason(reason)
					.update());
		}

		@Override
		protected boolean isActive(Server server, ServerChannel target, long data, DiscordBot bot) {
			return target instanceof ServerTextChannel
					&& ((ServerTextChannel) target).getSlowmodeDelayInSeconds() >= data;
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
		ServerChannel channel = server.getChannelById(targetId).orElse(null);

		Long resetData = activate(server, channel, data, reason, bot);

		return new ActivateResult(resetData != null, 1, resetData);
	}

	protected Long activate(Server server, ServerChannel target, long data, @Nullable String reason, DiscordBot bot) throws DiscordException {
		return null;
	}

	@Override
	public final void deactivate(Server server, long targetId, Long resetData, @Nullable String reason, DiscordBot bot) throws DiscordException {
		if (!hasDeactivation) return;

		ServerChannel targetChannel = server.getChannelById(targetId).orElse(null);

		deactivate(server, targetChannel, resetData, reason, bot);
	}

	protected void deactivate(Server server, ServerChannel target, Long resetData, @Nullable String reason, DiscordBot bot) throws DiscordException { }

	@Override
	public final boolean isActive(Server server, long targetId, long data, DiscordBot bot) {
		ServerChannel targetChannel = server.getChannelById(targetId).orElse(null);

		return isActive(server, targetChannel, data, bot);
	}

	protected boolean isActive(Server server, ServerChannel target, long data, DiscordBot bot) {
		return false;
	}
}
