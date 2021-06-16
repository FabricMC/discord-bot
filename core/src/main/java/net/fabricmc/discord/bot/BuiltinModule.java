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
import org.javacord.api.entity.message.MessageAuthor;
import org.javacord.api.entity.user.User;
import org.javacord.api.event.message.MessageCreateEvent;
import org.javacord.api.listener.message.MessageCreateListener;

import net.fabricmc.discord.bot.command.CommandContext;
import net.fabricmc.discord.bot.command.CommandResponder;
import net.fabricmc.discord.bot.command.core.ConfigCommand;
import net.fabricmc.discord.bot.command.core.GroupCommand;
import net.fabricmc.discord.bot.command.core.HelpCommand;
import net.fabricmc.discord.bot.command.core.PermissionCommand;
import net.fabricmc.discord.bot.command.filter.FilterActionCommand;
import net.fabricmc.discord.bot.command.filter.FilterCommand;
import net.fabricmc.discord.bot.command.filter.FilterGroupCommand;
import net.fabricmc.discord.bot.command.mod.ActionCommand;
import net.fabricmc.discord.bot.command.mod.CleanCommand;
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
	public boolean shouldLoad() {
		return true; // well yes
	}

	@Override
	public void registerConfigEntries(DiscordBot bot) {
	}

	@Override
	public void setup(DiscordBot bot, DiscordApi api, Logger logger, Path dataDir) {
		this.bot = bot;
		this.api = api;

		bot.registerCommand(new HelpCommand());

		bot.registerCommand(new ConfigCommand());
		bot.registerCommand(new GroupCommand());
		bot.registerCommand(new PermissionCommand());

		// mod/action
		bot.registerCommand(new ActionCommand());
		bot.registerCommand(new CleanCommand());
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

		// util
		bot.registerCommand(new DbCommand());
		bot.registerCommand(new ExportChannelCommand());
		bot.registerCommand(new ImportChannelCommand());
		bot.registerCommand(new ExportMessageCommand());

		api.addMessageCreateListener(this);
	}

	@Override
	public void onMessageCreate(MessageCreateEvent event) {
		if (!event.getMessageContent().startsWith(this.bot.getCommandPrefix())) {
			return;
		}

		MessageAuthor author = event.getMessageAuthor();
		User user = author.asUser().orElse(null);
		if (user == null) return; // should only happen for webhook messages

		// We intentionally don't pass the event since we may want to support editing the original message to execute the command again such as if an error was made in syntax
		final CommandContext context = new CommandContext(
				new CommandResponder(event),
				this.bot,
				event.getServer().orElse(null),
				event.getChannel(),
				event.getMessage(),
				user,
				bot.getUserHandler().getUserId(user),
				event.getMessageContent());

		this.bot.tryHandleCommand(context);
	}
}
