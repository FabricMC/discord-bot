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

import org.jetbrains.annotations.Nullable;

import net.fabricmc.discord.bot.DiscordBot.UserConfigEntry;
import net.fabricmc.discord.bot.UserHandler;
import net.fabricmc.discord.bot.command.Command;
import net.fabricmc.discord.bot.command.CommandContext;
import net.fabricmc.discord.bot.config.ConfigKey;
import net.fabricmc.discord.bot.message.Paginator;
import net.fabricmc.discord.io.DiscordException;

public final class UserConfigCommand extends Command {
	@Override
	public String name() {
		return "userConfig";
	}

	@Override
	public String usage() {
		return "list <user> | get <user> <configKey> | set <user> <configKey> <value>";
	}

	@Override
	public String permission() {
		return UserHandler.ADMIN_PERMISSION;
	}

	@Override
	public boolean run(CommandContext context, Map<String, String> arguments) throws Exception {
		int targetUserId = getUserId(context, arguments.get("user"));

		return switch (arguments.get("unnamed_0")) {
		case "get" -> this.runGet(context, arguments, targetUserId);
		case "set" -> this.runSet(context, arguments, targetUserId);
		case "list" -> this.runList(context, arguments, targetUserId);
		default -> throw new IllegalStateException("Impossible command state reached!");
		};
	}

	private boolean runList(CommandContext context, Map<String, String> arguments, int targetUserId) throws DiscordException {
		int pos = 0;
		Paginator.Builder builder = new Paginator.Builder(context.user()).title("User %d Config Entries".formatted(targetUserId));
		StringBuilder currentPage = new StringBuilder();

		for (UserConfigEntry<?> configEntry : context.bot().getUserConfigs(targetUserId)) {
			if (pos % 10 == 0 && pos != 0) {
				builder.page(currentPage);
				currentPage.setLength(0);
			}

			pos++;
			currentPage.append(String.format("`%s`: `%s`%s\n",
					configEntry.rawKey(),
					configEntry.rawValue(), //  TODO: escape value
					(configEntry.key() == null ? " (invalid key)" : "")));
		}

		if (currentPage.length() > 0) {
			builder.page(currentPage);
		}

		builder.buildAndSend(context.channel());

		return true;
	}

	private boolean runSet(CommandContext context, Map<String, String> arguments, int targetUserId) {
		final String key = arguments.get("configKey");
		@SuppressWarnings("unchecked")
		@Nullable final ConfigKey<Object> configKey = (ConfigKey<Object>) context.bot().getUserConfigKey(key);

		if (configKey == null) {
			context.channel().send("%s\nInvalid user config entry key `%s`".formatted(context.user().getNickMentionTag(), key));
			return false;
		}

		final String value = arguments.get("value");
		final Object deserializedValue;

		try {
			deserializedValue = configKey.valueSerializer().deserialize(value);
		} catch (IllegalArgumentException e) {
			// FIXME: Big-ass exceptions could exceed message limit?
			context.channel().send("Failed to set `%s` to `%s`:\n`%s`".formatted(key, value, e));
			return false;
		}

		if (!context.bot().setUserConfig(targetUserId, configKey, deserializedValue)) {
			context.channel().send("%s\nInvalid value: cannot set user config entry %s to %s".formatted(context.user().getNickMentionTag(), key, value));
			return false;
		}

		context.channel().send("Set user config entry `%s` to `%s` for %d".formatted(key, value, targetUserId));

		return true;
	}

	private boolean runGet(CommandContext context, Map<String, String> arguments, int targetUserId) {
		final String key = arguments.get("configKey");
		@SuppressWarnings("unchecked")
		@Nullable final ConfigKey<Object> configKey = (ConfigKey<Object>) context.bot().getUserConfigKey(key);

		if (configKey == null) {
			context.channel().send("%s: Invalid user config entry key %s".formatted(context.user().getNickMentionTag(), key));
			return false;
		}

		final Object configEntry = context.bot().getUserConfig(targetUserId, configKey);
		final String value = configKey.valueSerializer().serialize(configEntry);
		context.channel().send("User config entry `%s` is `%s`  for %d".formatted(key, value, targetUserId));

		return true;
	}
}
