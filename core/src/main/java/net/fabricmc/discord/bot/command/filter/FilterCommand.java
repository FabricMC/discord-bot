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

import java.io.StringReader;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import com.google.gson.stream.JsonReader;

import net.fabricmc.discord.bot.command.Command;
import net.fabricmc.discord.bot.command.CommandContext;
import net.fabricmc.discord.bot.command.CommandException;
import net.fabricmc.discord.bot.database.query.FilterQueries;
import net.fabricmc.discord.bot.database.query.FilterQueries.FilterEntry;
import net.fabricmc.discord.bot.filter.FilterType;
import net.fabricmc.discord.bot.message.Paginator;
import net.fabricmc.discord.bot.util.FormatUtil;
import net.fabricmc.discord.bot.util.FormatUtil.OutputType;

public final class FilterCommand extends Command {
	private static final int LIST_PAGE_ENTRIES = 20;

	@Override
	public String name() {
		return "filter";
	}

	@Override
	public String usage() {
		return "list <group> | add <group> <type> <pattern> | import <group> <type> [<contentUrl>] | remove <id> | clear <group> | setpattern <id> <pattern> | setgroup <id> <group>";
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
			List<FilterEntry> filters = new ArrayList<>(FilterQueries.getFilters(context.bot().getDatabase(), group));

			if (filters.isEmpty()) {
				context.channel().sendMessage(String.format("No filters in group %s", group));
			} else {
				filters.sort(Comparator.comparing(FilterEntry::type).thenComparing(FilterEntry::pattern));

				Paginator.Builder builder = new Paginator.Builder(context.user()).title("Group %s Filters".formatted(group));
				StringBuilder sb = new StringBuilder();
				int count = 0;

				for (FilterEntry filter : filters) {
					if (count % LIST_PAGE_ENTRIES == 0 && count > 0) {
						builder.page(sb);
						sb.setLength(0);
					}

					count++;

					if (sb.length() > 0) sb.append('\n');
					sb.append(String.format("`%d`: %s %s",
							filter.id(), filter.type().id, FormatUtil.escape(filter.pattern(), OutputType.INLINE_CODE, true)));
				}

				if (sb.length() > 0) {
					builder.page(sb);
				}

				builder.buildAndSend(context.channel());
			}

			return true;
		}
		case "add": {
			FilterType type = FilterType.get(arguments.get("type"));
			String pattern = arguments.get("pattern");
			if (pattern.isBlank()) throw new CommandException("blank pattern");
			type.compile(pattern); // test-compile to catch errors before storing the pattern

			if (!FilterQueries.addFilter(context.bot().getDatabase(), type, pattern, arguments.get("group"))) {
				throw new CommandException("Filter addition failed, invalid group or conflicting with another filter");
			}

			context.bot().getFilterHandler().reloadFilters();
			context.channel().sendMessage("Filter added");

			return true;
		}
		case "import": {
			FilterType type = FilterType.get(arguments.get("type"));
			String content = retrieveContent(context, arguments.get("contentUrl")).trim();
			List<String> patterns = new ArrayList<>();

			if (content.startsWith("[")) {
				try (JsonReader reader = new JsonReader(new StringReader(content))) {
					reader.beginArray();

					while (reader.hasNext()) {
						String pattern = reader.nextString();
						if (pattern.isBlank()) throw new CommandException("blank pattern");
						type.compile(pattern); // test-compile to catch errors before storing the pattern
						patterns.add(pattern);
					}

					reader.endArray();
				}
			} else {
				for (String pattern : content.split("\\R")) {
					if (pattern.isBlank()) throw new CommandException("blank pattern");
					type.compile(pattern); // test-compile to catch errors before storing the pattern
					patterns.add(pattern);
				}
			}

			if (patterns.isEmpty()) throw new CommandException("no patterns");

			String group = arguments.get("group");
			int added = 0;

			for (String pattern : patterns) {
				if (FilterQueries.addFilter(context.bot().getDatabase(), type, pattern, group)) {
					added++;
				}
			}

			if (added == 0) {
				throw new CommandException("Filter addition failed, invalid group or all conflicting with existing filters");
			}

			context.bot().getFilterHandler().reloadFilters();
			context.channel().sendMessage("%d / %d filters added".formatted(added, patterns.size()));

			return true;
		}
		case "remove":
			if (!FilterQueries.removeFilter(context.bot().getDatabase(), Integer.parseInt(arguments.get("id")))) {
				throw new CommandException("Filter removal failed, unknown id");
			}

			context.bot().getFilterHandler().reloadFilters();
			context.channel().sendMessage("Filter removed");

			return true;
		case "clear": {
			int count = FilterQueries.removeFilters(context.bot().getDatabase(), arguments.get("group"));
			if (count == 0) throw new CommandException("Filter removal failed, unknown/empty group");

			context.bot().getFilterHandler().reloadFilters();
			context.channel().sendMessage("%d filters removed".formatted(count));

			return true;
		}
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
