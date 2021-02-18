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

import org.javacord.api.entity.server.Server;
import org.javacord.api.entity.user.User;

import net.fabricmc.discord.bot.DiscordBot;

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
			if (!NOP_MODE) ActionUtil.muteUser(server, target, reason, bot);
		}

		@Override
		public void deactivate(Server server, long targetDiscordUserId, String reason, DiscordBot bot) {
			if (NOP_MODE) return;

			User target = server.getMemberById(targetDiscordUserId).orElse(null);

			if (target!= null) ActionUtil.unmuteUser(server, target, reason, bot);
		}

		@Override
		public boolean isActive(Server server, long targetDiscordUserId, DiscordBot bot) {
			User target = server.getMemberById(targetDiscordUserId).orElse(null);

			return target != null && ActionUtil.isUserMuted(server, target, bot);
		}
	};

	private static final boolean NOP_MODE = true; // no-op mode for testing

	public final String id;
	public final boolean hasDuration;
	public final boolean hasDeactivation;
	public final String actionDesc;

	public static ActionType get(String id) {
		for (ActionType type : values()) {
			if (type.id.equals(id)) {
				return type;
			}
		}

		throw new IllegalArgumentException("invalid type: "+id);
	}

	ActionType(String id, boolean hasDuration, String actionDesc) {
		this.id = id;
		this.hasDuration = hasDuration;
		this.actionDesc = actionDesc;

		try {
			this.hasDeactivation = getClass().getMethod("deactivate", Server.class, long.class, String.class, DiscordBot.class).getDeclaringClass() != ActionType.class; // deactivate was overridden
		} catch (ReflectiveOperationException e) {
			throw new RuntimeException(e);
		}
	}

	public abstract void activate(Server server, User target, String reason, DiscordBot bot);

	public void deactivate(Server server, long targetDiscordUserId, String reason, DiscordBot bot) { }

	public boolean isActive(Server server, long targetDiscordUserId, DiscordBot bot) {
		return false;
	}
}
