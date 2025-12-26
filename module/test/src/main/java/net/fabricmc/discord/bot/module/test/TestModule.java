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

import net.fabricmc.discord.bot.DiscordBot;
import net.fabricmc.discord.bot.Module;
import net.fabricmc.discord.bot.message.Paginator;
import net.fabricmc.discord.io.Discord;
import net.fabricmc.discord.io.GlobalEventHolder.MessageCreateHandler;
import net.fabricmc.discord.io.Message;

public final class TestModule implements Module, MessageCreateHandler {
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
	public void setup(DiscordBot bot, Discord discord, Logger logger, Path dataDir) {
		this.bot = bot;
		this.logger = logger;
		discord.getGlobalEvents().registerMessageCreate(this);;
	}

	@Override
	public void onMessageCreate(Message message) {
		if (message.getContent().equals(this.bot.getCommandPrefix() + "paginatorTest")) {
			Paginator paginator = new Paginator.Builder(message.getAuthor())
					.logger(logger)
					.title("some Title")
					.plainPages(PAGINATOR_TEXT)
					.timeoutSec(30)
					.build();

			paginator.send(message.getChannel());
		}
	}
}
