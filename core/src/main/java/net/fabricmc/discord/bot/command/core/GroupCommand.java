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

import java.sql.SQLException;
import java.util.Map;

import net.fabricmc.discord.bot.UserHandler;
import net.fabricmc.discord.bot.command.Command;
import net.fabricmc.discord.bot.command.CommandContext;
import net.fabricmc.discord.bot.database.query.UserQueries;

public final class GroupCommand extends Command {
	@Override
	public String name() {
		return "group";
	}

	@Override
	public String usage() {
		return "list [<user>] | (add|remove) [<user>] <group> | listsub <group> | (addsub|removesub) <parent> <child>";
	}

	@Override
	public String getPermission() {
		return UserHandler.ADMIN_PERMISSION;
	}

	@Override
	public boolean run(CommandContext context, Map<String, String> arguments) {
		try {
			if (arguments.containsKey("user")) { // user-group assignment interaction
				int userId = context.bot().getUserHandler().getUserId(arguments.get("user"), context.server(), true);

				if (userId < 0) {
					context.channel().sendMessage("Unknown or ambiguous user");
					return false;
				}

				switch (arguments.get("unnamed_0")) {
				case "list":
					context.channel().sendMessage("Groups: "+String.join(", ", UserQueries.getDirectGroups(context.bot().getDatabase(), userId)));
					return true;
				case "add":
					if (UserQueries.addToGroup(context.bot().getDatabase(), userId, arguments.get("group"))) {
						context.channel().sendMessage("User added to group");
						return true;
					} else {
						context.channel().sendMessage("The user is already in the group");
						return false;
					}
				case "remove":
					if (UserQueries.removeFromGroup(context.bot().getDatabase(), userId, arguments.get("group"))) {
						context.channel().sendMessage("User removed from group");
						return true;
					} else {
						context.channel().sendMessage("The user wasn't in the group");
						return false;
					}
				}
			} else { // group handling itself
				switch (arguments.get("unnamed_0")) {
				case "list":
					context.channel().sendMessage("Groups: "+String.join(", ", UserQueries.getGroups(context.bot().getDatabase())));
					return true;
				case "add":
					if (UserQueries.addGroup(context.bot().getDatabase(), arguments.get("group"))) {
						context.channel().sendMessage("Group added");
						return true;
					} else {
						context.channel().sendMessage("The group already exists");
						return false;
					}
				case "remove":
					if (UserQueries.removeGroup(context.bot().getDatabase(), arguments.get("group"))) {
						context.channel().sendMessage("Group removed");
						return true;
					} else {
						context.channel().sendMessage("No such group");
						return false;
					}
				case "listsub":
					context.channel().sendMessage(String.join(", ", UserQueries.getGroupChildren(context.bot().getDatabase(), arguments.get("group"))));
					return true;
				case "addsub":
					if (UserQueries.addGroupChild(context.bot().getDatabase(), arguments.get("parent"), arguments.get("child"))) {
						context.channel().sendMessage("Group relation added");
						return true;
					} else {
						context.channel().sendMessage("The group relation already exists");
						return false;
					}
				case "removesub":
					if (UserQueries.removeGroupChild(context.bot().getDatabase(), arguments.get("parent"), arguments.get("child"))) {
						context.channel().sendMessage("Group relation added");
						return true;
					} else {
						context.channel().sendMessage("The group relation already exists");
						return false;
					}
				}
			}

			throw new IllegalStateException();
		} catch (SQLException e) {
			context.channel().sendMessage("Query failed:\n`%s`".formatted(e));
			return false;
		}
	}
}
