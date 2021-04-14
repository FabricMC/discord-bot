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

package net.fabricmc.discord.bot.command.filter;

import java.util.Collection;
import java.util.Map;

import net.fabricmc.discord.bot.command.Command;
import net.fabricmc.discord.bot.command.CommandContext;
import net.fabricmc.discord.bot.command.CommandException;
import net.fabricmc.discord.bot.database.query.FilterQueries;
import net.fabricmc.discord.bot.database.query.FilterQueries.FilterActionEntry;
import net.fabricmc.discord.bot.filter.FilterAction;

public final class FilterActionCommand extends Command {
	@Override
	public String name() {
		return "filteraction";
	}

	@Override
	public String usage() {
		return "list | add <name> <description> <action> [<actionData...>] | remove <name> | setaction <name> <action> [<actionData...>] | setdescription <name> <description...>";
	}

	@Override
	public String permission() {
		return "filter";
	}

	@Override
	public boolean run(CommandContext context, Map<String, String> arguments) throws Exception {
		switch (arguments.get("unnamed_0")) {
		case "list": {
			Collection<FilterActionEntry> actions = FilterQueries.getActions(context.bot().getDatabase());

			if (actions.isEmpty()) {
				context.channel().sendMessage("No actions");
			} else {
				StringBuilder sb = new StringBuilder("Actions:");

				for (FilterActionEntry action : actions) {
					sb.append(String.format("\n%d %s: %s -> %s %s",
							action.id(), action.name(), action.description(), action.action(), action.actionData()));
				}

				context.channel().sendMessage(sb.toString());
			}

			return true;
		}
		case "add": {
			if (!FilterQueries.addAction(context.bot().getDatabase(), arguments.get("name"), arguments.get("description"), FilterAction.get(arguments.get("action")), arguments.get("actionData"))) {
				throw new CommandException("Filter action addition failed, conflicting name");
			}

			context.channel().sendMessage("Filter action added");

			return true;
		}
		case "remove":
			if (!FilterQueries.removeAction(context.bot().getDatabase(), arguments.get("name"))) {
				throw new CommandException("Filter action removal failed, unknown name");
			}

			context.channel().sendMessage("Filter action removed");

			return true;
		case "setaction":
			if (!FilterQueries.setActionAction(context.bot().getDatabase(), arguments.get("name"), FilterAction.get(arguments.get("action")), arguments.get("actionData"))) {
				throw new CommandException("Filter action action update failed, unknown name");
			}

			context.channel().sendMessage("Filter action action updated");

			return true;
		case "setdescription":
			if (!FilterQueries.setActionDescription(context.bot().getDatabase(), arguments.get("name"), arguments.get("description"))) {
				throw new CommandException("Action description update failed, unknown name");
			}

			context.channel().sendMessage("Filter action description updated");

			return true;
		}

		throw new IllegalStateException();
	}
}
