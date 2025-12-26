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

package net.fabricmc.discord.bot.module.mcversion;

import java.util.Map;

import net.fabricmc.discord.bot.UserHandler;
import net.fabricmc.discord.bot.command.Command;
import net.fabricmc.discord.bot.command.CommandContext;

public final class McVersionCommand extends Command {
	private final McVersionModule module;

	McVersionCommand(McVersionModule module) {
		this.module = module;
	}

	@Override
	public String name() {
		return String.format("mcVersion");
	}

	@Override
	public String usage() {
		return "";
	}

	@Override
	public String permission() {
		return UserHandler.ADMIN_PERMISSION;
	}

	@Override
	public boolean run(CommandContext context, Map<String, String> arguments) throws Exception {
		context.channel().send(String.format("**Fabric**\nLatest: %s\nLatest Stable: %s\n\n"
				+ "**McMeta**\nRelease: %s\nSnapshot: %s\nLast Update: <t:%d>",
				module.getRepo().getLatest(), module.getRepo().getLatestStable(),
				module.metaFetcher.getLatestRelease(), module.metaFetcher.getLatestSnapshot(), module.metaFetcher.getLastUpdateTimeMs() / 1000));

		return true;
	}
}
