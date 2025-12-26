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

package net.fabricmc.discord.bot.command.mod;

import java.util.Map;

import net.fabricmc.discord.bot.CachedMessage;
import net.fabricmc.discord.bot.command.Command;
import net.fabricmc.discord.bot.command.CommandContext;
import net.fabricmc.discord.bot.command.CommandException;
import net.fabricmc.discord.bot.command.mod.ActionUtil.UserMessageAction;
import net.fabricmc.discord.io.Channel;
import net.fabricmc.discord.io.Message;

public final class DeleteCommand extends Command {
	@Override
	public String name() {
		return "delete";
	}

	@Override
	public String usage() {
		return "<message> <reason...> [--silent]";
	}

	@Override
	public String permission() {
		return "delete";
	}

	@Override
	public boolean run(CommandContext context, Map<String, String> arguments) throws Exception {
		CachedMessage cachedMessage = getMessage(context, arguments.get("message"), false);

		int userId = context.bot().getUserHandler().getUserId(cachedMessage.getAuthorDiscordId());
		if (userId < 0) throw new CommandException("Message from unknown user");

		Message message = cachedMessage.toMessage(context.server());
		if (message == null) throw new CommandException("Can't resolve message");

		Channel channel = message.getChannel();
		checkMessageDeleteAccess(context, channel);

		String reason = arguments.get("reason");

		message.delete(reason);

		if (userId != context.bot().getUserHandler().getBotUserId()) {
			ActionUtil.applyUserAction(UserActionType.DELETE_MESSAGE, 0, userId, null, reason, cachedMessage, UserMessageAction.NONE,
					!arguments.containsKey("silent"), null,
					context.bot(), context.server(), context.channel(), context.user(), context.userId());
		} else {
			context.channel().send("Message deleted");
		}

		return true;
	}
}
