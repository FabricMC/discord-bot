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

package net.fabricmc.discord.bot.module.mapping;

import java.nio.file.Path;

import org.apache.logging.log4j.Logger;
import org.javacord.api.DiscordApi;

import net.fabricmc.discord.bot.DiscordBot;
import net.fabricmc.discord.bot.Module;

public final class MappingModule implements Module {
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
	public void registerConfigEntries(DiscordBot bot) { }

	@Override
	public void setup(DiscordBot bot, DiscordApi api, Logger logger, Path dataDir) {
		repo = new MappingRepository(bot);

		bot.registerCommand(new YarnClassCommand(repo));
		bot.registerCommand(new YarnFieldCommand(repo));
		bot.registerCommand(new YarnMethodCommand(repo));
	}
}
