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
import net.fabricmc.discord.bot.database.query.FilterQueries.FilterGroupEntry;

public final class FilterGroupCommand extends Command {
	@Override
	public String name() {
		return "filtergroup";
	}

	@Override
	public String usage() {
		return "list | add <name> <action> <description...> | remove <name> | setaction <name> <action> | setdescription <name> <description...>";
	}

	@Override
	public String permission() {
		return "filter";
	}

	@Override
	public boolean run(CommandContext context, Map<String, String> arguments) throws Exception {
		switch (arguments.get("unnamed_0")) {
		case "list": {
			Collection<FilterGroupEntry> groups = FilterQueries.getGroups(context.bot().getDatabase());

			if (groups.isEmpty()) {
				context.channel().send("No groups");
			} else {
				StringBuilder sb = new StringBuilder("Groups:");

				for (FilterGroupEntry group : groups) {
					sb.append(String.format("\n%d %s: %s -> %s",
							group.id(), group.name(), group.description(), group.action()));
				}

				context.channel().send(sb.toString());
			}

			return true;
		}
		case "add":
			if (!FilterQueries.addGroup(context.bot().getDatabase(), arguments.get("name"), arguments.get("description"), arguments.get("action"))) {
				throw new CommandException("Filter group addition failed, conflicting name or unknown action");
			}

			context.channel().send("Filter group added");

			return true;
		case "remove":
			if (!FilterQueries.removeGroup(context.bot().getDatabase(), arguments.get("name"))) {
				throw new CommandException("Filter group removal failed, unknown name");
			}

			context.channel().send("Filter group removed");

			return true;
		case "setaction":
			if (!FilterQueries.setGroupAction(context.bot().getDatabase(), arguments.get("name"), arguments.get("action"))) {
				throw new CommandException("Filter group action update failed, unknown name or unknown action");
			}

			context.channel().send("Filter group action updated");

			return true;
		case "setdescription":
			if (!FilterQueries.setGroupDescription(context.bot().getDatabase(), arguments.get("name"), arguments.get("description"))) {
				throw new CommandException("Group description update failed, unknown name");
			}

			context.channel().send("Filter group description updated");

			return true;
		}

		throw new IllegalStateException();
	}
}
