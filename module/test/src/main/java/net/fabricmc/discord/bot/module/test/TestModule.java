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

package net.fabricmc.discord.bot.module.test;

import java.nio.file.Path;
import java.util.List;

import org.apache.logging.log4j.Logger;
import org.javacord.api.DiscordApi;
import org.javacord.api.event.message.MessageCreateEvent;
import org.javacord.api.listener.message.MessageCreateListener;

import net.fabricmc.discord.bot.DiscordBot;
import net.fabricmc.discord.bot.Module;
import net.fabricmc.discord.bot.config.ConfigKey;
import net.fabricmc.discord.bot.message.Paginator;

public final class TestModule implements Module, MessageCreateListener {
	public static final List<String> PAGINATOR_TEXT = List.of(
			"**The first page of the paginator**",
			"*The second page of the paginator*",
			"__The third page of the paginator__"
	);
	private static final boolean LOAD = System.getProperty("fabricBot.test", "false") != null;
	private DiscordBot bot;
	private Logger logger;

	@Override
	public String getName() {
		return "test";
	}

	@Override
	public boolean shouldLoad() {
		return LOAD;
	}

	@Override
	public void registerConfigEntries(DiscordBot bot) {
	}

	@Override
	public void setup(DiscordBot bot, DiscordApi api, Logger logger, Path dataDir) {
		this.bot = bot;
		this.logger = logger;
		api.addMessageCreateListener(this);
	}

	@Override
	public void onMessageCreate(MessageCreateEvent event) {
		if (event.getMessageContent().equals(this.bot.getCommandPrefix() + "paginatorTest")) {
			final Paginator paginator = new Paginator(this.logger, PAGINATOR_TEXT, 30, event.getMessageAuthor().getId());

			paginator.send(event.getChannel());
		} else if (event.getMessageContent().equals(this.bot.getCommandPrefix() + "dumpConfig")) {
			final StringBuilder builder = new StringBuilder();

			for (ConfigKey<?> entry : this.bot.getConfigEntries()) {
				final Object value = this.bot.getConfigEntry(entry);
				builder.append(entry.name())
						.append(" -> ")
						.append(value)
						.append("\n");
			}

			event.getChannel().sendMessage(builder.toString());
		}
	}
}
