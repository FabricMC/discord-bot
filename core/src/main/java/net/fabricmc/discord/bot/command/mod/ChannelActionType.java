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

import org.javacord.api.entity.channel.ServerChannel;
import org.javacord.api.entity.channel.ServerTextChannel;
import org.javacord.api.entity.permission.PermissionType;
import org.javacord.api.entity.permission.Permissions;
import org.javacord.api.entity.permission.Role;
import org.javacord.api.entity.server.Server;
import org.javacord.api.exception.DiscordException;

import net.fabricmc.discord.bot.DiscordBot;
import net.fabricmc.discord.bot.util.DiscordUtil;

public enum ChannelActionType implements ActionType {
	LOCK("lock", true) {
		@Override
		protected Integer activate(Server server, ServerChannel target, int data, String reason, DiscordBot bot) throws DiscordException {
			if (NOP_MODE) return 0;

			Role role = server.getEveryoneRole();
			Permissions oldPerms = target.getOverwrittenPermissions(role);
			int extraDenyMask = (restrictedPerms & (int) ~oldPerms.getDeniedBitmask());
			if (extraDenyMask == 0) return 0;

			DiscordUtil.join(target.createUpdater()
					.addPermissionOverwrite(role, Permissions.fromBitmask((int) oldPerms.getAllowedBitmask(), (int) oldPerms.getDeniedBitmask() | extraDenyMask))
					.setAuditLogReason(reason)
					.update());

			return extraDenyMask;
		}

		@Override
		protected void deactivate(Server server, ServerChannel target, Integer resetData, String reason, DiscordBot bot) throws DiscordException {
			if (NOP_MODE) return;
			if (resetData == null) resetData = restrictedPerms;

			assert (resetData & ~restrictedPerms) == 0;

			Role role = server.getEveryoneRole();
			Permissions oldPerms = target.getOverwrittenPermissions(role);
			if ((oldPerms.getDeniedBitmask() & resetData) == 0) return;

			DiscordUtil.join(target.createUpdater()
					.addPermissionOverwrite(role, Permissions.fromBitmask((int) oldPerms.getAllowedBitmask(), (int) oldPerms.getDeniedBitmask() & ~resetData))
					.setAuditLogReason(reason)
					.update());
		}

		@Override
		protected boolean isActive(Server server, ServerChannel target, int data, DiscordBot bot) {
			return (target.getOverwrittenPermissions(server.getEveryoneRole()).getDeniedBitmask() & restrictedPerms) == restrictedPerms;
		}

		private final int restrictedPerms = PermissionType.ADD_REACTIONS.getValue() | PermissionType.SEND_MESSAGES.getValue();
	},
	SLOWMODE("slowmode", true) {
		@Override
		protected Integer activate(Server server, ServerChannel target, int data, String reason, DiscordBot bot) throws DiscordException {
			assert data > 0;

			if (!(target instanceof ServerTextChannel)) return null;
			if (NOP_MODE) return 0;

			ServerTextChannel channel = (ServerTextChannel) target;
			int oldSlowmode = channel.getSlowmodeDelayInSeconds();

			DiscordUtil.join(channel.createUpdater()
					.setSlowmodeDelayInSeconds(data)
					.setAuditLogReason(reason)
					.update());

			return oldSlowmode;
		}

		@Override
		protected void deactivate(Server server, ServerChannel target, Integer resetData, String reason, DiscordBot bot) throws DiscordException {
			if (NOP_MODE) return;
			if (!(target instanceof ServerTextChannel)) return;
			if (resetData == null) resetData = 0;

			ServerTextChannel channel = (ServerTextChannel) target;
			if (channel.getSlowmodeDelayInSeconds() <= resetData) return;

			DiscordUtil.join(channel.createUpdater()
					.setSlowmodeDelayInSeconds(resetData)
					.setAuditLogReason(reason)
					.update());
		}

		@Override
		protected boolean isActive(Server server, ServerChannel target, int data, DiscordBot bot) {
			return target instanceof ServerTextChannel && ((ServerTextChannel) target).getSlowmodeDelayInSeconds() >= data;
		}

		@Override
		public int compareData(int dataA, int dataB) {
			return Integer.compare(dataA, dataB);
		}

		@Override
		public boolean checkData(int data, int prevResetData) {
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
		this.hasDeactivation = UserActionType.isMethodOverridden(getClass(), "deactivate", Server.class, ServerChannel.class, Integer.class, String.class, DiscordBot.class);
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
	public final ActivateResult activate(Server server, long targetId, boolean isDirect, int data, String reason, DiscordBot bot) throws DiscordException {
		ServerChannel channel = server.getChannelById(targetId).orElse(null);
		if (channel == null) return new ActivateResult(true, 0, null);

		Integer resetData = activate(server, channel, data, reason, bot);

		return new ActivateResult(resetData != null, 1, resetData);
	}

	protected Integer activate(Server server, ServerChannel target, int data, String reason, DiscordBot bot) throws DiscordException {
		return null;
	}

	@Override
	public final void deactivate(Server server, long targetId, Integer resetData, String reason, DiscordBot bot) throws DiscordException {
		if (!hasDeactivation) return;

		ServerChannel targetChannel = server.getChannelById(targetId).orElse(null);
		if (targetChannel == null) return;

		deactivate(server, targetChannel, resetData, reason, bot);
	}

	protected void deactivate(Server server, ServerChannel target, Integer resetData, String reason, DiscordBot bot) throws DiscordException { }

	@Override
	public final boolean isActive(Server server, long targetId, int data, DiscordBot bot) {
		ServerChannel targetChannel = server.getChannelById(targetId).orElse(null);

		return targetChannel != null && isActive(server, targetChannel, data, bot);
	}

	protected boolean isActive(Server server, ServerChannel target, int data, DiscordBot bot) {
		return false;
	}
}
