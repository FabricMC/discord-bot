/*
 * Copyright (c) 2020, 2021 FabricMC
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

package net.fabricmc.discord.bot.config;

import java.util.Objects;
import java.util.Set;

import org.jetbrains.annotations.Nullable;
import org.spongepowered.configurate.objectmapping.ConfigSerializable;

// Note: Fields get converted from camelCase to kebab-case inside of the config:
// https://github.com/SpongePowered/Configurate/wiki/Object-Mapper#naming-scheme
public record BotConfig(Secrets secrets, GuildInfo guild, Modules modules) {
	public BotConfig {
		Objects.requireNonNull(secrets, "Secrets are required to start the bot!");
		Objects.requireNonNull(guild, "Guild info is required to start the bot!");
		Objects.requireNonNull(modules, "Module settings are required to start the bot!");
	}

	@ConfigSerializable
	public record Secrets(String token, @Nullable String databaseUsername, @Nullable String databasePassword) {
		public Secrets {
			if (token == null) {
				throw new IllegalArgumentException("Token cannot be null!");
			}

			if (token.isEmpty()) {
				throw new IllegalArgumentException("Token value cannot be empty!");
			}
		}
	}

	@ConfigSerializable
	public record GuildInfo(Long id, String commandPrefix, Set<Long> ignoredChannels) {
		public GuildInfo {
			// FIXME: Make the guild id snowflake a primitive long: awaiting configurate to support primitive types properly for that.
			if (id == null) {
				throw new IllegalArgumentException("Guild id cannot be null!");
			}

			if (commandPrefix == null) {
				throw new IllegalArgumentException("Command prefix is required!");
			}

			if (ignoredChannels.contains(null)) {
				throw new IllegalArgumentException("Ignored channels cannot contain any null");
			}
		}
	}

	@ConfigSerializable
	public record Modules(Set<String> disabled) {
	}
}
