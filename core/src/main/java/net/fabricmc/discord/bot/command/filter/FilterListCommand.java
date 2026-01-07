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

import java.net.URI;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import net.fabricmc.discord.bot.command.Command;
import net.fabricmc.discord.bot.command.CommandContext;
import net.fabricmc.discord.bot.command.CommandException;
import net.fabricmc.discord.bot.database.query.FilterQueries;
import net.fabricmc.discord.bot.database.query.FilterQueries.FilterListEntry;
import net.fabricmc.discord.bot.database.query.FilterQueries.FilterListExceptionEntry;
import net.fabricmc.discord.bot.database.query.FilterQueries.GlobalFilterListExceptionEntry;
import net.fabricmc.discord.bot.filter.FilterType;
import net.fabricmc.discord.bot.message.Paginator;
import net.fabricmc.discord.bot.util.FormatUtil;
import net.fabricmc.discord.bot.util.FormatUtil.OutputType;

public final class FilterListCommand extends Command {
	private static final int LIST_PAGE_ENTRIES = 20;

	@Override
	public String name() {
		return "filterlist";
	}

	@Override
	public String usage() {
		return "list | add <group> <type> <url> | remove <listId> | "
				+ "(listexceptions|listex) <listId> | (addexception|addex) <listId> <pattern> <reason...> | (removeexception|remex) <exceptionId> | "
				+ "(listglobalexceptions|listgex) | (addglobalexception|addgex) <type> <pattern> <reason...> | (removeglobalexception|remgex) <exceptionId>";
	}

	@Override
	public String permission() {
		return "filter";
	}

	@Override
	public boolean run(CommandContext context, Map<String, String> arguments) throws Exception {
		switch (arguments.get("unnamed_0")) {
		case "list": {
			List<FilterListEntry> filterLists = new ArrayList<>(FilterQueries.getFilterLists(context.bot().getDatabase()));

			if (filterLists.isEmpty()) {
				context.channel().send("No filter lists");
			} else {
				filterLists.sort(Comparator.comparing(FilterListEntry::group).thenComparing(FilterListEntry::type).thenComparing(FilterListEntry::url));

				Paginator.Builder builder = new Paginator.Builder(context.user()).title("Filter Lists");
				StringBuilder sb = new StringBuilder();
				int count = 0;

				for (FilterListEntry list : filterLists) {
					if (count % LIST_PAGE_ENTRIES == 0 && count > 0) {
						builder.page(sb);
						sb.setLength(0);
					}

					count++;

					if (sb.length() > 0) sb.append('\n');
					sb.append(String.format("`%d`: %s %s %s",
							list.id(),
							list.group(),
							list.type().id,
							FormatUtil.escape(list.url().toString(), OutputType.INLINE_CODE, true)));
				}

				if (sb.length() > 0) {
					builder.page(sb);
				}

				builder.buildAndSend(context.channel());
			}

			return true;
		}
		case "add": {
			int id = FilterQueries.addFilterList(context.bot().getDatabase(), FilterType.get(arguments.get("type")), new URI(arguments.get("url")), arguments.get("group"));
			if (id < 0) throw new CommandException("Filter list addition failed, invalid group or url already added");

			context.bot().getFilterHandler().reloadFilterLists();
			context.channel().send("Filter list %d added".formatted(id));

			return true;
		}
		case "remove":
			if (!FilterQueries.removeFilterList(context.bot().getDatabase(), Integer.parseInt(arguments.get("listId")))) {
				throw new CommandException("Filter list removal failed, unknown id");
			}

			context.channel().send("Filter list removed");

			return true;
		case "listexceptions":
		case "listex": {
			int listId = Integer.parseInt(arguments.get("listId"));
			List<FilterListExceptionEntry> exceptions = new ArrayList<>(FilterQueries.getFilterListExceptions(context.bot().getDatabase(), listId));

			if (exceptions.isEmpty()) {
				context.channel().send("No filter list exceptions");
			} else {
				exceptions.sort(Comparator.comparing(FilterListExceptionEntry::pattern));

				Paginator.Builder builder = new Paginator.Builder(context.user()).title("Filter List %d Exceptions".formatted(listId));
				StringBuilder sb = new StringBuilder();
				int count = 0;

				for (FilterListExceptionEntry exception : exceptions) {
					if (count % LIST_PAGE_ENTRIES == 0 && count > 0) {
						builder.page(sb);
						sb.setLength(0);
					}

					count++;

					if (sb.length() > 0) sb.append('\n');
					sb.append(String.format("`%d`: %s (%s)",
							exception.id(),
							FormatUtil.escape(exception.pattern(), OutputType.INLINE_CODE, true),
							FormatUtil.escapePlain(exception.reason())));
				}

				if (sb.length() > 0) {
					builder.page(sb);
				}

				builder.buildAndSend(context.channel());
			}

			return true;
		}
		case "addexception":
		case "addex": {
			int listId = Integer.parseInt(arguments.get("listId"));
			int id;

			synchronized (context.bot().getFilterHandler()) {
				id = FilterQueries.addFilterListException(context.bot().getDatabase(), listId, arguments.get("pattern"), arguments.get("reason"));
			}

			if (id < 0) throw new CommandException("Filter list exception addition failed, invalid list id or exception already added");

			context.bot().getFilterHandler().reloadFilters();
			context.channel().send("Filter list exception %d added to list %d".formatted(id, listId));

			return true;
		}
		case "removeexception":
		case "remex":
			if (!FilterQueries.removeFilterListException(context.bot().getDatabase(), Integer.parseInt(arguments.get("exceptionId")))) {
				throw new CommandException("Filter list exception removal failed, unknown id");
			}

			context.bot().getFilterHandler().reloadFilterLists();
			context.channel().send("Filter list exception removed");

			return true;
		case "listglobalexceptions":
		case "listgex": {
			List<GlobalFilterListExceptionEntry> exceptions = new ArrayList<>(FilterQueries.getGlobalFilterListExceptions(context.bot().getDatabase()));

			if (exceptions.isEmpty()) {
				context.channel().send("No global filter list exceptions");
			} else {
				exceptions.sort(Comparator.comparing(GlobalFilterListExceptionEntry::type).thenComparing(GlobalFilterListExceptionEntry::pattern));

				Paginator.Builder builder = new Paginator.Builder(context.user()).title("Global Filter List Exceptions");
				StringBuilder sb = new StringBuilder();
				int count = 0;

				for (GlobalFilterListExceptionEntry exception : exceptions) {
					if (count % LIST_PAGE_ENTRIES == 0 && count > 0) {
						builder.page(sb);
						sb.setLength(0);
					}

					count++;

					if (sb.length() > 0) sb.append('\n');
					sb.append(String.format("`%d`: %s %s (%s)",
							exception.id(),
							exception.type().id,
							FormatUtil.escape(exception.pattern(), OutputType.INLINE_CODE, true),
							FormatUtil.escapePlain(exception.reason())));
				}

				if (sb.length() > 0) {
					builder.page(sb);
				}

				builder.buildAndSend(context.channel());
			}

			return true;
		}
		case "addglobalexception":
		case "addgex": {
			FilterType type = FilterType.get(arguments.get("type"));
			String pattern = type.normalizePattern(arguments.get("pattern"));
			if (pattern.isBlank()) throw new CommandException("blank pattern");
			type.compile(pattern); // test-compile to catch errors before storing the pattern
			int id;

			synchronized (context.bot().getFilterHandler()) {
				id = FilterQueries.addGlobalFilterListException(context.bot().getDatabase(), type, pattern, arguments.get("reason"));
			}

			if (id < 0) throw new CommandException("Filter list global exception addition failed, invalid list id or exception already added");

			context.bot().getFilterHandler().reloadFilters();
			context.channel().send("Global filter list exception %d added".formatted(id));

			return true;
		}
		case "removeglobalexception":
		case "remgex":
			if (!FilterQueries.removeGlobalFilterListException(context.bot().getDatabase(), Integer.parseInt(arguments.get("exceptionId")))) {
				throw new CommandException("Global filter list exception removal failed, unknown id");
			}

			context.bot().getFilterHandler().reloadFilterLists();
			context.channel().send("Global filter list exception removed");

			return true;
		}

		throw new IllegalStateException();
	}
}
