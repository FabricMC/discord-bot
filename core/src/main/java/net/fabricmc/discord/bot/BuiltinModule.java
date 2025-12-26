/*
 * Copyright (c) 2020, 2022 FabricMC
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

import net.fabricmc.discord.bot.command.CommandContext;
import net.fabricmc.discord.bot.command.CommandResponder;
import net.fabricmc.discord.bot.command.core.ConfigCommand;
import net.fabricmc.discord.bot.command.core.GroupCommand;
import net.fabricmc.discord.bot.command.core.HelpCommand;
import net.fabricmc.discord.bot.command.core.PermissionCommand;
import net.fabricmc.discord.bot.command.filter.FilterActionCommand;
import net.fabricmc.discord.bot.command.filter.FilterCommand;
import net.fabricmc.discord.bot.command.filter.FilterGroupCommand;
import net.fabricmc.discord.bot.command.filter.FilterListCommand;
import net.fabricmc.discord.bot.command.mod.ActionCommand;
import net.fabricmc.discord.bot.command.mod.CleanCommand;
import net.fabricmc.discord.bot.command.mod.DeleteCommand;
import net.fabricmc.discord.bot.command.mod.DeleteRangeCommand;
import net.fabricmc.discord.bot.command.mod.GenericUserActionCommand;
import net.fabricmc.discord.bot.command.mod.LockCommand;
import net.fabricmc.discord.bot.command.mod.NickCommand;
import net.fabricmc.discord.bot.command.mod.NoteCommand;
import net.fabricmc.discord.bot.command.mod.ResetNickCommand;
import net.fabricmc.discord.bot.command.mod.SlowmodeCommand;
import net.fabricmc.discord.bot.command.mod.UnlockCommand;
import net.fabricmc.discord.bot.command.mod.UserActionType;
import net.fabricmc.discord.bot.command.mod.UserCommand;
import net.fabricmc.discord.bot.command.util.DbCommand;
import net.fabricmc.discord.bot.command.util.ExportChannelCommand;
import net.fabricmc.discord.bot.command.util.ExportMessageCommand;
import net.fabricmc.discord.bot.command.util.ImportChannelCommand;
import net.fabricmc.discord.bot.command.util.MessageCacheCommand;
import net.fabricmc.discord.io.Discord;
import net.fabricmc.discord.io.GlobalEventHolder.MessageCreateHandler;
import net.fabricmc.discord.io.Message;
import net.fabricmc.discord.io.User;

/**
 * The builtin module of the discord bot.
 * This is intentionally hardcoded in to NOT be created via a ServiceLoader.
 * This module will always load first and always be present.
 *
 * <p>The builtin module handles the dispatch of commands.
 */
final class BuiltinModule implements Module, MessageCreateHandler {
	private DiscordBot bot;
	private Discord discord;

	@Override
	public String getName() {
		return "builtin";
	}

	@Override
	public boolean shouldLoad() {
		return true; // well yes
	}

	@Override
	public void registerConfigEntries(DiscordBot bot) {
		HelpCommand.registerConfigEntries(bot);
	}

	@Override
	public void setup(DiscordBot bot, Discord discord, Logger logger, Path dataDir) {
		this.bot = bot;
		this.discord = discord;

		bot.registerCommand(new HelpCommand());

		bot.registerCommand(new ConfigCommand());
		bot.registerCommand(new GroupCommand());
		bot.registerCommand(new PermissionCommand());

		// mod/action
		bot.registerCommand(new ActionCommand());
		bot.registerCommand(new CleanCommand());
		bot.registerCommand(new DeleteCommand());
		bot.registerCommand(new DeleteRangeCommand());
		bot.registerCommand(new LockCommand());
		bot.registerCommand(new UnlockCommand());
		bot.registerCommand(new NickCommand());
		bot.registerCommand(new NoteCommand());
		bot.registerCommand(new ResetNickCommand());
		bot.registerCommand(new SlowmodeCommand());
		bot.registerCommand(new UserCommand());

		for (UserActionType type : UserActionType.values()) {
			if (type.hasDedicatedCommand) continue;

			bot.registerCommand(new GenericUserActionCommand(type, true));

			if (type.hasDeactivation()) {
				bot.registerCommand(new GenericUserActionCommand(type, false));
			}
		}

		// filter
		bot.registerCommand(new FilterCommand());
		bot.registerCommand(new FilterGroupCommand());
		bot.registerCommand(new FilterActionCommand());
		bot.registerCommand(new FilterListCommand());

		// util
		bot.registerCommand(new DbCommand());
		bot.registerCommand(new ExportChannelCommand());
		bot.registerCommand(new ImportChannelCommand());
		bot.registerCommand(new ExportMessageCommand());
		bot.registerCommand(new MessageCacheCommand());

		discord.getGlobalEvents().registerMessageCreate(this);
	}

	@Override
	public void onMessageCreate(Message message) {
		if (message.isFromWebhook()) return;

		String content = message.getContent();
		String prefix = bot.getCommandPrefix();

		if (content.length() <= prefix.length() || !content.startsWith(prefix)) {
			return;
		}

		User user = message.getAuthor();
		assert user != null; // webhooks are already filtered above

		// We intentionally don't pass the event since we may want to support editing the original message to execute the command again such as if an error was made in syntax
		final CommandContext context = new CommandContext(
				new CommandResponder(message),
				this.bot,
				message.getChannel().getServer(),
				message.getChannel(),
				message,
				user,
				bot.getUserHandler().getUserId(user),
				content);

		this.bot.tryHandleCommand(context);
	}
}
