/*
 * Copyright (c) 2024 FabricMC
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

import net.fabricmc.discord.bot.command.Command;
import net.fabricmc.discord.bot.command.CommandContext;
import net.fabricmc.discord.bot.command.CommandException;

public final class LauncherNewsCommand extends Command {
	private final McVersionModule module;

	LauncherNewsCommand(McVersionModule module) {
		this.module = module;
	}

	@Override
	public String name() {
		return String.format("launcherNews");
	}

	@Override
	public String usage() {
		return "[snapshot | release]";
	}

	@Override
	public boolean run(CommandContext context, Map<String, String> arguments) throws Exception {
		String arg = arguments.get("unnamed_0");
		LauncherNewsFetcher.Version version;

		if ("snapshot".equals(arg)) {
			version = module.launcherNewsFetcher.getLatestSnapshot();
		} else if ("release".equals(arg)) {
			version = module.launcherNewsFetcher.getLatestRelease();
		} else {
			LauncherNewsFetcher.Version release = module.launcherNewsFetcher.getLatestRelease();
			LauncherNewsFetcher.Version snapshot = module.launcherNewsFetcher.getLatestSnapshot();

			if (release == null) {
				version = snapshot;
			} else if (snapshot == null) {
				version = release;
			} else {
				version = release.compareTo(snapshot) > 0 ? release : snapshot;
			}
		}

		if (version == null) {
			throw new CommandException("Version not loaded");
		}

		context.channel().send(version.toEmbed());
		return true;
	}
}
