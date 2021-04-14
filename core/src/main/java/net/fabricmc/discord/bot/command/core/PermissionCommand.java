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

import net.fabricmc.discord.bot.UserHandler;
import net.fabricmc.discord.bot.command.Command;
import net.fabricmc.discord.bot.command.CommandContext;
import net.fabricmc.discord.bot.command.CommandException;
import net.fabricmc.discord.bot.database.query.UserQueries;

public final class PermissionCommand extends Command {
	@Override
	public String name() {
		return "permission";
	}

	@Override
	public String usage() {
		return "list <group> | (add|remove) <group> <permission>";
	}

	@Override
	public String permission() {
		return UserHandler.ADMIN_PERMISSION;
	}

	@Override
	public boolean run(CommandContext context, Map<String, String> arguments) throws Exception {
		switch (arguments.get("unnamed_0")) {
		case "list":
			context.channel().sendMessage("Entries: "+String.join(", ", UserQueries.getDirectGroupPermissions(context.bot().getDatabase(), arguments.get("group"))));
			return true;
		case "add":
			if (!UserQueries.addGroupPermission(context.bot().getDatabase(), arguments.get("group"), arguments.get("permission"))) {
				throw new CommandException("The entry already exists");
			}

			context.channel().sendMessage("Entry added");
			return true;
		case "remove":
			if (!UserQueries.removeGroupPermission(context.bot().getDatabase(), arguments.get("group"), arguments.get("permission"))) {
				throw new CommandException("No such entry");
			}

			context.channel().sendMessage("Entry removed");
			return true;
		default:
			throw new IllegalStateException();
		}
	}
}
