/*
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

package net.fabricmc.discord.bot.module;

import java.nio.file.Path;

import org.javacord.api.DiscordApi;

import net.fabricmc.discord.bot.DiscordBot;
import net.fabricmc.discord.bot.Module;

public final class TestModule implements Module {
	@Override
	public String getName() {
		return "test";
	}

	@Override
	public boolean setup(DiscordBot bot, DiscordApi api, Path configDir) {
		return true;
	}
}
