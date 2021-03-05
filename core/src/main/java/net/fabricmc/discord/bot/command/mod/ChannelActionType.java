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

import net.fabricmc.discord.bot.DiscordBot;

public enum ChannelActionType {
	LOCK("lock", true, "locked", "unlocked") {
		@Override
		public Integer activate(Server server, ServerChannel target, int data, String reason, DiscordBot bot) {
			if (NOP_MODE) return 0;

			Role role = server.getEveryoneRole();
			Permissions oldPerms = target.getOverwrittenPermissions(role);
			int extraDenyMask = restrictedPerms & ~oldPerms.getDeniedBitmask();
			if (extraDenyMask == 0) return 0;

			target.createUpdater()
			.addPermissionOverwrite(role, Permissions.fromBitmask(oldPerms.getAllowedBitmask(), oldPerms.getDeniedBitmask() | extraDenyMask))
			.setAuditLogReason(reason)
			.update();

			return extraDenyMask;
		}

		@Override
		public void deactivate(Server server, ServerChannel target, Integer resetData, String reason, DiscordBot bot) {
			if (NOP_MODE) return;
			if (resetData == null) resetData = restrictedPerms;

			assert (resetData & ~restrictedPerms) == 0;

			Role role = server.getEveryoneRole();
			Permissions oldPerms = target.getOverwrittenPermissions(role);
			if ((oldPerms.getDeniedBitmask() & resetData) == 0) return;

			target.createUpdater()
			.addPermissionOverwrite(role, Permissions.fromBitmask(oldPerms.getAllowedBitmask(), oldPerms.getDeniedBitmask() & ~resetData))
			.setAuditLogReason(reason)
			.update();
		}

		@Override
		public boolean isActive(Server server, ServerChannel target, int data, DiscordBot bot) {
			return (target.getOverwrittenPermissions(server.getEveryoneRole()).getDeniedBitmask() & restrictedPerms) == restrictedPerms;
		}

		private final int restrictedPerms = PermissionType.ADD_REACTIONS.getValue() | PermissionType.SEND_MESSAGES.getValue();
	},
	SLOWMODE("slowmode", true, "slowmode enabled", "slowmode disabled") {
		@Override
		public Integer activate(Server server, ServerChannel target, int data, String reason, DiscordBot bot) {
			assert data > 0;

			if (!(target instanceof ServerTextChannel)) return null;
			if (NOP_MODE) return 0;

			ServerTextChannel channel = (ServerTextChannel) target;
			int oldSlowmode = channel.getSlowmodeDelayInSeconds();

			channel.createUpdater()
			.setSlowmodeDelayInSeconds(data)
			.setAuditLogReason(reason)
			.update();

			return oldSlowmode;
		}

		@Override
		public void deactivate(Server server, ServerChannel target, Integer resetData, String reason, DiscordBot bot) {
			if (NOP_MODE) return;
			if (!(target instanceof ServerTextChannel)) return;
			if (resetData == null) resetData = 0;

			ServerTextChannel channel = (ServerTextChannel) target;
			if (channel.getSlowmodeDelayInSeconds() <= resetData) return;

			channel.createUpdater()
			.setSlowmodeDelayInSeconds(resetData)
			.setAuditLogReason(reason)
			.update();
		}

		@Override
		public boolean isActive(Server server, ServerChannel target, int data, DiscordBot bot) {
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
	public final boolean hasDeactivation;
	private final String actionDesc;
	private final String revActionDesc;

	public static ChannelActionType get(String id) {
		for (ChannelActionType type : values()) {
			if (type.id.equals(id)) {
				return type;
			}
		}

		throw new IllegalArgumentException("invalid type: "+id);
	}

	ChannelActionType(String id, boolean hasDuration, String actionDesc, String revActionDesc) {
		this.id = id;
		this.hasDuration = hasDuration;
		this.actionDesc = actionDesc;
		this.revActionDesc = revActionDesc;

		try {
			this.hasDeactivation = getClass().getMethod("deactivate", Server.class, ServerChannel.class, Integer.class, String.class, DiscordBot.class).getDeclaringClass() != ChannelActionType.class; // deactivate was overridden
		} catch (ReflectiveOperationException e) {
			throw new RuntimeException(e);
		}
	}

	public String getDesc(boolean reversal) {
		return reversal ? revActionDesc : actionDesc;
	}

	public Integer activate(Server server, ServerChannel target, int data, String reason, DiscordBot bot) {
		return -1;
	}

	public void deactivate(Server server, ServerChannel target, Integer resetData, String reason, DiscordBot bot) { }

	public boolean isActive(Server server, ServerChannel target, int data, DiscordBot bot) {
		return false;
	}

	/**
	 * Compare two data values to determine precedence.
	 *
	 * @param dataA
	 * @param dataB
	 * @return 0 for equal, <0 if b is higher/supersedes a, >0 if a is higher/supersedes b
	 */
	public int compareData(int dataA, int dataB) {
		return 0;
	}

	/**
	 * Determine whether the data value is applicable considering the previous reset data.
	 */
	public boolean checkData(int data, int prevResetData) {
		return true;
	}
}
