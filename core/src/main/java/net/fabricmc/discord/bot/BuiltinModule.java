/*
 * Copyright (c) 2020, 2021 FabricMC
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

package net.fabricmc.discord.bot;

import java.nio.file.Path;

import org.apache.logging.log4j.Logger;
import org.javacord.api.DiscordApi;
import org.javacord.api.event.message.MessageCreateEvent;
import org.javacord.api.listener.message.MessageCreateListener;

import net.fabricmc.discord.bot.command.CommandContext;
import net.fabricmc.discord.bot.command.CommandResponder;

/**
 * The builtin module of the discord bot.
 * This is intentionally hardcoded in to NOT be created via a ServiceLoader.
 * This module will always load first and always be present.
 *
 * <p>The builtin module handles the dispatch of commands.
 */
final class BuiltinModule implements Module, MessageCreateListener {
	private DiscordBot bot;
	private DiscordApi api;

	@Override
	public String getName() {
		return "builtin";
	}

	@Override
	public boolean setup(DiscordBot bot, DiscordApi api, Logger logger, Path dataDir) {
		this.bot = bot;
		this.api = api;

		api.addMessageCreateListener(this);

		return true;
	}

	@Override
	public void onMessageCreate(MessageCreateEvent event) {
		if (!event.getMessageContent().startsWith(this.bot.getCommandPrefix())) {
			return;
		}

		// We intentionally don't pass the event since we may want to support editing the original message to execute the command again such as if an error was made in syntax
		final CommandContext context = new CommandContext(
				new CommandResponder(event),
				this.bot,
				event.getServer().orElse(null),
				event.getMessageLink(),
				event.getMessageAuthor(),
				event.getChannel(),
				event.getMessageContent(),
				event.getMessageId()
		);

		this.bot.tryHandleCommand(context);
	}
}
