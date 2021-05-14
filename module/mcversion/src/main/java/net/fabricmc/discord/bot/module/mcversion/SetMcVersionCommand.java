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

package net.fabricmc.discord.bot.module.mcversion;

import java.util.List;
import java.util.Map;

import net.fabricmc.discord.bot.command.Command;
import net.fabricmc.discord.bot.command.CommandContext;
import net.fabricmc.discord.bot.command.CommandException;

public final class SetMcVersionCommand extends Command {
	private final McVersionRepo repo;

	SetMcVersionCommand(McVersionRepo repo) {
		this.repo = repo;
	}

	@Override
	public String name() {
		return String.format("setMcVersion");
	}

	@Override
	public List<String> aliases() {
		return List.of("setMc", "setVersion");
	}

	@Override
	public String usage() {
		return "reset | latest | latestStable | <mcVersion>";
	}

	@Override
	public boolean run(CommandContext context, Map<String, String> arguments) throws Exception {
		String arg = arguments.get("unnamed_0");

		if ("reset".equals(arg)) {
			removeUserConfig(context, McVersionModule.DEFAULT_VERSION);
			context.channel().sendMessage("Default MC version reset");
		} else {
			if (arg == null) {
				arg = arguments.get("mcVersion");

				if (!repo.isValidVersion(arg)) {
					throw new CommandException("Invalid/unavailable MC version");
				}
			}

			setUserConfig(context, McVersionModule.DEFAULT_VERSION, arg);
			context.channel().sendMessage("Default MC version updated");
		}

		return true;
	}
}
