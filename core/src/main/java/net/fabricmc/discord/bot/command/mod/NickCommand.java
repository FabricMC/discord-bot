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

import java.util.List;
import java.util.Map;

import org.javacord.api.entity.user.User;

import net.fabricmc.discord.bot.UserHandler;
import net.fabricmc.discord.bot.command.Command;
import net.fabricmc.discord.bot.command.CommandContext;
import net.fabricmc.discord.bot.command.CommandException;
import net.fabricmc.discord.bot.database.query.ActionQueries;
import net.fabricmc.discord.bot.database.query.ActionQueries.ActionEntry;
import net.fabricmc.discord.bot.database.query.UserQueries.DiscordUserData;

public final class NickCommand extends Command {
	@Override
	public String name() {
		return "nick";
	}

	@Override
	public List<String> aliases() {
		return List.of("rename", "name");
	}

	@Override
	public String usage() {
		return "<user> <nick> <reason...>";
	}

	@Override
	public String permission() {
		return "nick";
	}

	@Override
	public boolean run(CommandContext context, Map<String, String> arguments) throws Exception {
		run(context, arguments.get("user"), arguments.get("nick"), arguments.get("reason"));
		return true;
	}

	static void run(CommandContext context, String user, String newNick, String reason) throws Exception {
		long targetDiscordUserId = getDiscordUserId(context, user);
		checkImmunity(context, targetDiscordUserId, false);

		User target = context.server().getMemberById(targetDiscordUserId).orElse(null);

		if (target == null && ActionQueries.getLockedNick(context.bot().getDatabase(), targetDiscordUserId) == null) {
			throw new CommandException("Target user is absent and not nicklocked");
		}

		UserHandler userHandler = context.bot().getUserHandler();
		String oldNick;

		if (target != null) {
			oldNick = target.getDisplayName(context.server());
			if (newNick == null) newNick = target.getName();
		} else {
			DiscordUserData data = userHandler.getDiscordUserData(targetDiscordUserId, false, false);
			oldNick = data.nickname() != null ? data.nickname() : data.username();
			if (newNick == null) newNick = data.username();
		}

		if (newNick.equals(oldNick)) {
			throw new CommandException("Name unchanged");
		}

		// create db record

		int targetUserId = userHandler.getUserId(targetDiscordUserId);
		ActionEntry entry = ActionQueries.createAction(context.bot().getDatabase(), UserActionType.RENAME, null,
				targetUserId, context.userId(), 0, System.currentTimeMillis(), 0, reason,
				null, 0);

		// announce action

		ActionUtil.announceAction(entry.type(), false, "", "from %s to %s".formatted(oldNick, newNick),
				targetUserId, entry.creationTime(), entry.expirationTime(), entry.reason(),
				entry.id(), null,
				context.channel(), context.user(),
				context.bot(), context.server(), true);

		// update nick lock entries

		ActionQueries.updateLockedNick(context.bot().getDatabase(), targetDiscordUserId, newNick);

		// apply discord action

		if (target != null) {
			if (target.getName().equals(newNick)) {
				context.server().resetNickname(target, reason);
			} else {
				context.server().updateNickname(target, newNick, reason);
			}
		}
	}
}
