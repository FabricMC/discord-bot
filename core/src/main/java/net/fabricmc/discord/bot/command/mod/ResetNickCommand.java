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

package net.fabricmc.discord.bot.command.mod;

import java.util.List;
import java.util.Map;

import net.fabricmc.discord.bot.command.Command;
import net.fabricmc.discord.bot.command.CommandContext;

public final class ResetNickCommand extends Command {
	@Override
	public String name() {
		return "resetNick";
	}

	@Override
	public List<String> aliases() {
		return List.of("resetName", "unnick");
	}

	@Override
	public String usage() {
		return "<user> <reason...>";
	}

	@Override
	public String permission() {
		return "nick";
	}

	@Override
	public boolean run(CommandContext context, Map<String, String> arguments) throws Exception {
		NickCommand.run(context, arguments.get("user"), null, arguments.get("reason"));
		return true;
	}
}
