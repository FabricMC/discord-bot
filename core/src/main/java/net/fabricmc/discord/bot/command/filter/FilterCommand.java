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
import net.fabricmc.discord.bot.database.query.FilterQueries.FilterEntry;
import net.fabricmc.discord.bot.filter.FilterType;

public final class FilterCommand extends Command {
	@Override
	public String name() {
		return "filter";
	}

	@Override
	public String usage() {
		return "list <group> | add <group> <type> <pattern> | remove <id> | setpattern <id> <pattern> | setgroup <id> <group>";
	}

	@Override
	public String permission() {
		return "filter";
	}

	@Override
	public boolean run(CommandContext context, Map<String, String> arguments) throws Exception {
		switch (arguments.get("unnamed_0")) {
		case "list": {
			String group = arguments.get("group");
			Collection<FilterEntry> filters = FilterQueries.getFilters(context.bot().getDatabase(), group);

			if (filters.isEmpty()) {
				context.channel().sendMessage(String.format("No filters in group %s", group));
			} else {
				StringBuilder sb = new StringBuilder(String.format("Filters in group %s:", group));

				for (FilterEntry filter : filters) {
					sb.append(String.format("\n%d: %s %s",
							filter.id(), filter.type().id, filter.pattern()));
				}

				context.channel().sendMessage(sb.toString());
			}

			return true;
		}
		case "add": {
			FilterType type = FilterType.get(arguments.get("type"));
			String pattern = arguments.get("pattern");
			type.compile(pattern); // test-compile to catch errors before storing the pattern

			if (!FilterQueries.addFilter(context.bot().getDatabase(), type, pattern, arguments.get("group"))) {
				throw new CommandException("Filter addition failed, invalid group or conflicting with another filter");
			}

			context.bot().getFilterHandler().reloadFilters();
			context.channel().sendMessage("Filter added");

			return true;
		}
		case "remove":
			if (!FilterQueries.removeFilter(context.bot().getDatabase(), Integer.parseInt(arguments.get("id")))) {
				throw new CommandException("Filter removal failed, unknown id");
			}

			context.bot().getFilterHandler().reloadFilters();
			context.channel().sendMessage("Filter removed");

			return true;
		case "setpattern": {
			int id = Integer.parseInt(arguments.get("id"));
			FilterEntry filter = FilterQueries.getFilter(context.bot().getDatabase(), id);
			if (filter == null) throw new CommandException("unknown filter id");

			String pattern = arguments.get("pattern");
			filter.type().compile(pattern); // test-compile to catch errors before storing the pattern

			if (!FilterQueries.setFilterPattern(context.bot().getDatabase(), id, pattern)) {
				throw new CommandException("Pattern update failed, unknown filter id");
			}

			context.bot().getFilterHandler().reloadFilters();
			context.channel().sendMessage("Filter pattern updated");

			return true;
		}
		case "setgroup":
			if (!FilterQueries.setFilterGroup(context.bot().getDatabase(), Integer.parseInt(arguments.get("id")), arguments.get("group"))) {
				throw new CommandException("Group update failed, unknown filter id or group name");
			}

			context.bot().getFilterHandler().reloadFilters();
			context.channel().sendMessage("Filter group updated");

			return true;
		}

		throw new IllegalStateException();
	}
}
