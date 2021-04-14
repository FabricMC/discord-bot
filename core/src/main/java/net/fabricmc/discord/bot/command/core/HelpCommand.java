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

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.stream.JsonReader;

import net.fabricmc.discord.bot.command.Command;
import net.fabricmc.discord.bot.command.CommandContext;
import net.fabricmc.discord.bot.command.CommandException;
import net.fabricmc.discord.bot.message.Paginator;

public final class HelpCommand extends Command {
	private static final Map<String, String> shortHelpTexts = new HashMap<>();
	private static final Map<String, String> longHelpTexts = new HashMap<>();

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
			String cmdPrefix = context.bot().getCommandPrefix();
			currentPage.append(String.format("Run a command with `%s<cmdName>` and arguments as required.\nUse `%s%s <cmdName>` for additional information.\n\n",
					cmdPrefix, cmdPrefix, name()));
			int pos = 0;

			for (Command cmd : context.bot().getCommands()) {
				if (!context.bot().checkAccess(context.author(), cmd)) continue;

				if (pos % 10 == 0 && pos != 0) {
					builder.page(currentPage);
					currentPage.setLength(0);
				}

				pos++;
				currentPage.append(String.format("**%s**", cmd.name()));

				String usage = cmd.usage();

				if (!usage.isEmpty()) {
					currentPage.append(String.format(" `%s`", usage));
				}

				currentPage.append('\n');

				String shortHelp = cmd.shortHelp();
				if (shortHelp == null) shortHelp = shortHelpTexts.get(cmd.name());

				if (shortHelp != null) {
					currentPage.append(shortHelp);
					currentPage.append('\n');
				}
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

			StringBuilder sb = new StringBuilder();
			sb.append(String.format("**%s**", cmd.name()));

			String usage = cmd.usage();
			if (!usage.isEmpty()) sb.append(String.format(" `%s`", usage));

			List<String> aliases = cmd.aliases();
			if (!aliases.isEmpty()) sb.append(String.format("\n**Aliases:** %s", String.join(", ", aliases)));

			String shortHelp = cmd.shortHelp();
			if (shortHelp == null) shortHelp = shortHelpTexts.get(cmd.name());

			if (shortHelp != null) {
				sb.append('\n');
				sb.append(shortHelp);
			}

			String longHelp = cmd.longHelp();
			if (longHelp == null) longHelp = longHelpTexts.get(cmd.name());

			if (longHelp != null) {
				sb.append("\n\n");
				sb.append(longHelp);
			}

			context.channel().sendMessage(sb.toString());
		}

		return true;
	}

	private static void loadHelpTexts() {
		Map<String, String> substitutions = new HashMap<>();

		try (JsonReader reader = new JsonReader(new InputStreamReader(HelpCommand.class.getClassLoader().getResourceAsStream("cmdhelp.json"), StandardCharsets.UTF_8))) {
			reader.beginObject();

			while (reader.hasNext()) {
				String section = reader.nextName();
				reader.beginObject();

				while (reader.hasNext()) {
					String name = reader.nextName();

					if (section.equals("commands")) {
						reader.beginObject();

						while (reader.hasNext()) {
							String key = reader.nextName();

							switch (key) {
							case "short" -> shortHelpTexts.put(name, reader.nextString());
							case "long" -> longHelpTexts.put(name, reader.nextString());
							default -> throw new IOException("invalid key in cmdhelp json: "+key);
							}
						}

						reader.endObject();
					} else {
						substitutions.put(name, reader.nextString());
					}
				}

				reader.endObject();
			}

			reader.endObject();
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}

		List<Map<String, String>> maps = List.of(shortHelpTexts, longHelpTexts);

		for (Map.Entry<String, String> substEntry : substitutions.entrySet()) {
			String substKey = substEntry.getKey();
			String substValue = substEntry.getValue();

			for (Map<String, String> map : maps) {
				for (Map.Entry<String, String> entry : map.entrySet()) {
					String text = entry.getValue();
					int pos = text.indexOf(substKey);
					if (pos < 0) continue;

					StringBuilder sb = new StringBuilder(text.length() - substKey.length() + substValue.length());
					int startPos = 0;

					do {
						sb.append(text, 0, pos);
						sb.append(substValue);
						startPos = pos + substKey.length();
					} while ((pos = text.indexOf(substKey, startPos)) >= 0);

					sb.append(text, startPos, text.length());

					entry.setValue(sb.toString());
				}
			}
		}
	}

	static {
		loadHelpTexts();
	}
}
