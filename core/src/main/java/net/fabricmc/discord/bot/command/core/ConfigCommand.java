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

import java.util.Map;

import org.javacord.api.exception.DiscordException;
import org.jetbrains.annotations.Nullable;

import net.fabricmc.discord.bot.UserHandler;
import net.fabricmc.discord.bot.command.Command;
import net.fabricmc.discord.bot.command.CommandContext;
import net.fabricmc.discord.bot.config.ConfigKey;
import net.fabricmc.discord.bot.message.Mentions;
import net.fabricmc.discord.bot.message.Paginator;

public final class ConfigCommand extends Command {
	@Override
	public String name() {
		return "config";
	}

	@Override
	public String usage() {
		return "list | get <configKey> | set <configKey> <value>";
	}

	@Override
	public String getPermission() {
		return UserHandler.ADMIN_PERMISSION;
	}

	@Override
	public boolean run(CommandContext context, Map<String, String> arguments) throws Exception {
		return switch (arguments.get("unnamed_0")) {
		case "get" -> this.runGet(context, arguments);
		case "set" -> this.runSet(context, arguments);
		case "list" -> this.runList(context, arguments);
		default -> throw new IllegalStateException("Impossible command state reached!");
		};
	}

	private boolean runList(CommandContext context, Map<String, String> arguments) throws DiscordException {
		int pos = 0;
		Paginator.Builder builder = new Paginator.Builder(context.author()).title("Config Entries");
		StringBuilder currentPage = new StringBuilder();

		for (ConfigKey<?> configEntry : context.bot().getConfigEntries()) {
			if (pos % 10 == 0 && pos != 0) {
				builder.page(currentPage);
				currentPage.setLength(0);;
			}

			pos++;
			currentPage.append("%s\n".formatted(configEntry.name()));
		}

		if (currentPage.length() > 0) {
			builder.page(currentPage);
		}

		builder.buildAndSend(context.channel());

		return true;
	}

	private boolean runSet(CommandContext context, Map<String, String> arguments) {
		final String key = arguments.get("configKey");
		@SuppressWarnings("unchecked")
		@Nullable final ConfigKey<Object> configKey = (ConfigKey<Object>) context.bot().getConfigKey(key);

		if (configKey == null) {
			context.channel().sendMessage("%s\nInvalid config entry key `%s`".formatted(Mentions.createUserMention(context.author()), key));
			return false;
		}

		final String value = arguments.get("value");
		final Object deserializedValue;

		try {
			deserializedValue = configKey.valueSerializer().deserialize(value);
		} catch (IllegalArgumentException e) {
			// FIXME: Big-ass exceptions could exceed message limit?
			context.channel().sendMessage("Failed to set `%s` to `%s`:\n`%s`".formatted(key, value, e));
			return false;
		}

		if (!context.bot().setConfigEntry(configKey, deserializedValue)) {
			context.channel().sendMessage("%s\nInvalid value: cannot set config entry %s to %s".formatted(Mentions.createUserMention(context.author()), key, value));
			return false;
		}

		context.channel().sendMessage("Set config entry `%s` to `%s`".formatted(key, value));

		return true;
	}

	private boolean runGet(CommandContext context, Map<String, String> arguments) {
		final String key = arguments.get("configKey");
		@SuppressWarnings("unchecked")
		@Nullable final ConfigKey<Object> configKey = (ConfigKey<Object>) context.bot().getConfigKey(key);

		if (configKey == null) {
			context.channel().sendMessage("%s: Invalid config entry key %s".formatted(Mentions.createUserMention(context.author()), key));
			return false;
		}

		final Object configEntry = context.bot().getConfigEntry(configKey);
		final String value = configKey.valueSerializer().serialize(configEntry);
		context.channel().sendMessage("Config entry `%s` is `%s`".formatted(key, value));

		return true;
	}
}
