/**
 * Copyright (c) 2020 FabricMC
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

import org.javacord.api.DiscordApi;

public interface Module {
	String getName();

	/**
	 * When called a module should setup.
	 *
	 * @param bot the bot instance
	 * @param api the api instance to communicate with discord
	 * @param configDir the directory of the configs
	 * @return if this module has successfully loaded
	 */
	boolean setup(DiscordBot bot, DiscordApi api, Path configDir);
}
