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

import org.javacord.api.entity.permission.Role;
import org.javacord.api.entity.server.Server;

import net.fabricmc.discord.bot.DiscordBot;
import net.fabricmc.discord.bot.config.ConfigKey;
import net.fabricmc.discord.bot.config.ValueSerializers;

enum ActionRole {
	META_MUTE, MUTE, REACTION_MUTE, REQUESTS_MUTE, SUPPORT_MUTE;

	ActionRole() {
		configKey = new ConfigKey<>("action.%sRole".formatted(toJavaCase(name())), ValueSerializers.LONG);
	}

	public Role resolve(Server server, DiscordBot bot) {
		long roleId = bot.getConfigEntry(configKey);
		if (roleId < 0) return null;

		return server.getRoleById(roleId).orElse(null);
	}

	static void registerConfig(DiscordBot bot) {
		for (ActionRole role : values()) {
			bot.registerConfigEntry(role.configKey, () -> -1L);
		}
	}

	private static CharSequence toJavaCase(String s) {
		StringBuilder ret = new StringBuilder(s.length());
		boolean capital = false;

		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);

			if (c == '_') {
				capital = true;
			} else if (capital) {
				ret.append(c);
				capital = false;
			} else {
				ret.append(Character.toLowerCase(c));
			}
		}

		return ret;
	}

	private final ConfigKey<Long> configKey;
}
