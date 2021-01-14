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

package net.fabricmc.discord.bot;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Properties;

final class BotConfig {
	static BotConfig load(Properties properties) {
		if (!properties.containsKey("token")) {
			throw new RuntimeException("A discord token is required to create the discord bot!");
		}

		if (!properties.containsKey("database-url")) {
			throw new RuntimeException("A database url is required to create the discord bot!");
		}

		if (!properties.containsKey("guild-id")) {
			throw new RuntimeException("The guild id is required to create the discord bot!");
		}

		if (!properties.containsKey("command-prefix")) {
			throw new RuntimeException("The command prefix is required to create the discord bot!");
		}

		final String token = properties.getProperty("token");

		if (token.isEmpty()) {
			throw new RuntimeException("The token value cannot be empty!");
		}

		final String databaseUrl = properties.getProperty("database-url");
		final String guildId = properties.getProperty("guild-id");

		if (guildId.isEmpty()) {
			throw new RuntimeException("Guild id cannot be empty!");
		}

		final String commandPrefix = properties.getProperty("command-prefix");

		if (commandPrefix.isEmpty()) {
			throw new RuntimeException("Command prefix cannot be empty!");
		}

		if (commandPrefix.length() != 1) {
			throw new RuntimeException("Command prefix can only be a single character but it was %s characters".formatted(commandPrefix.length()));
		}

		Collection<String> disabledModules = null;

		// Disabled modules list is optional
		if (properties.containsKey("disabled-modules")) {
			final String rawDisabledModules = properties.getProperty("disabled-modules");

			if (!rawDisabledModules.isEmpty()) {
				// Split the raw list at commas and whitespace into individual elements
				final String[] modules = rawDisabledModules.split("\\s*,\\s*");

				disabledModules = Collections.unmodifiableCollection(Arrays.asList(modules));
			}
		}

		if (disabledModules == null) {
			disabledModules = Collections.emptyList();
		}

		return new BotConfig(token, databaseUrl, guildId, commandPrefix, disabledModules);
	}

	private final String token;
	private final String databaseUrl;
	private final String guildId;
	private final String commandPrefix;
	private final Collection<String> disabledModules;

	BotConfig(String token, String databaseUrl, String guildId, String commandPrefix, Collection<String> disabledModules) {
		this.token = token;
		this.databaseUrl = databaseUrl;
		this.guildId = guildId;
		this.commandPrefix = commandPrefix;
		this.disabledModules = disabledModules;
	}

	String getToken() {
		return this.token;
	}

	String getDatabaseUrl() {
		return this.databaseUrl;
	}

	String getGuildId() {
		return this.guildId;
	}

	String getCommandPrefix() {
		return this.commandPrefix;
	}

	Collection<String> getDisabledModules() {
		return this.disabledModules;
	}
}
