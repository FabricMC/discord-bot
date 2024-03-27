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

package net.fabricmc.discord.bot.module.mapping;

import java.nio.file.Path;
import java.util.List;

import org.apache.logging.log4j.Logger;
import org.javacord.api.DiscordApi;

import net.fabricmc.discord.bot.DiscordBot;
import net.fabricmc.discord.bot.Module;
import net.fabricmc.discord.bot.config.ConfigKey;
import net.fabricmc.discord.bot.config.ValueSerializers;
import net.fabricmc.discord.bot.module.mapping.SetNamespaceCommand.NamespaceApplication;
import net.fabricmc.discord.bot.module.mapping.repo.MappingRepository;

public final class MappingModule implements Module {
	static final List<String> supportedNamespaces = List.of("official", "intermediary", "yarn", "mojmap", "srg", "mcp");
	private static final List<String> defaultNamespaces = List.of("official", "intermediary", "yarn");
	private static final List<String> publicNamespaces = defaultNamespaces;

	// global properties
	static final ConfigKey<List<String>> DEFAULT_NAMESPACES = new ConfigKey<>("mapping.defaultNamespaces", ValueSerializers.STRING_LIST);
	static final ConfigKey<List<String>> PUBLIC_NAMESPACES = new ConfigKey<>("mapping.publicNamespaces", ValueSerializers.STRING_LIST);

	// user properties
	static final ConfigKey<List<String>> QUERY_NAMESPACES = new ConfigKey<>("mapping.queryNamespaces", ValueSerializers.STRING_LIST);
	static final ConfigKey<List<String>> DISPLAY_NAMESPACES = new ConfigKey<>("mapping.displayNamespaces", ValueSerializers.STRING_LIST);

	private MappingRepository repo;

	@Override
	public String getName() {
		return "mapping";
	}

	@Override
	public boolean shouldLoad() {
		return true;
	}

	@Override
	public void registerConfigEntries(DiscordBot bot) {
		bot.registerConfigEntry(DEFAULT_NAMESPACES, () -> defaultNamespaces);
		bot.registerConfigEntry(PUBLIC_NAMESPACES, () -> publicNamespaces);
	}

	@Override
	public void setup(DiscordBot bot, DiscordApi api, Logger logger, Path dataDir) {
		repo = new MappingRepository(bot, dataDir);

		bot.registerCommand(new MappingStatusCommand(repo));
		bot.registerCommand(new YarnClassCommand(repo));
		bot.registerCommand(new YarnFieldCommand(repo));
		bot.registerCommand(new YarnMethodCommand(repo));

		for (NamespaceApplication application : NamespaceApplication.values()) {
			bot.registerCommand(new SetNamespaceCommand(application));
		}
	}
}
