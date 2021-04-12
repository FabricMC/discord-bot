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

package net.fabricmc.discord.bot.command.core;

import java.util.List;
import java.util.Map;

import net.fabricmc.discord.bot.command.Command;
import net.fabricmc.discord.bot.command.CommandContext;
import net.fabricmc.discord.bot.command.CommandException;
import net.fabricmc.discord.bot.message.Paginator;

public final class HelpCommand extends Command {
	@Override
	public String name() {
		return "help";
	}

	@Override
	public String usage() {
		return "|<command>";
	}

	@Override
	public boolean run(CommandContext context, Map<String, String> arguments) throws Exception {
		if (!arguments.containsKey("command")) {
			Paginator.Builder builder = new Paginator.Builder(context.author()).title("Bot Usage Help");
			StringBuilder currentPage = new StringBuilder();
			int pos = 0;

			for (Command cmd : context.bot().getCommands()) {
				if (!context.bot().checkAccess(context.author(), cmd)) continue;

				if (pos % 10 == 0 && pos != 0) {
					builder.page(currentPage);
					currentPage.setLength(0);
				}

				pos++;
				currentPage.append(cmd.name());

				String usage = cmd.usage();

				if (!usage.isEmpty()) {
					currentPage.append(String.format(" `%s`", usage));
				}

				currentPage.append('\n');
			}

			if (currentPage.length() > 0) {
				builder.page(currentPage);
			}

			builder.buildAndSend(context.channel());
		} else {
			Command cmd = context.bot().getCommand(arguments.get("command"));

			if (cmd == null || !context.bot().checkAccess(context.author(), cmd)) {
				throw new CommandException("Unknown command");
			}

			List<String> aliases = cmd.aliases();
			String aliasesSuffix = aliases.isEmpty() ? "" : String.format("\nAliases: %s", String.join(", ", aliases));

			context.channel().sendMessage(String.format("%s `%s`%s",
					cmd.name(), cmd.usage(),
					aliasesSuffix));
		}

		return true;
	}
}
