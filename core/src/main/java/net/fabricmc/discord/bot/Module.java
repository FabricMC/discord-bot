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

package net.fabricmc.discord.bot;

import java.nio.file.Path;

import org.apache.logging.log4j.Logger;
import org.javacord.api.DiscordApi;

import net.fabricmc.discord.bot.config.ConfigKey;

public interface Module {
	String getName();

	default boolean shouldLoad() {
		return true;
	}

	default void registerConfigEntries(DiscordBot bot) { }

	/**
	 * When called a module should setup.
	 *
	 * @param bot the bot instance
	 * @param api the api instance to communicate with discord
	 * @param logger the logger for this module
	 * @param dataDir the data directory the bot is using
	 */
	void setup(DiscordBot bot, DiscordApi api, Logger logger, Path dataDir);

	default void onAllSetup(DiscordBot bot, DiscordApi api) { }

	/**
	 * Called when a config entry has changed.
	 *
	 * @param key the key of the config entry
	 * @param value the value the config entry was set to
	 */
	default void onConfigValueChanged(ConfigKey<?> key, Object value) { }
}
