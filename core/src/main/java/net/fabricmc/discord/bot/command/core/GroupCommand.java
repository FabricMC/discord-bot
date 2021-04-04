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
	public boolean run(CommandContext context, Map<String, String> arguments) throws Exception {
		if (arguments.containsKey("user")) { // user-group assignment interaction
			int userId = getUserId(context, arguments.get("user"));

			switch (arguments.get("unnamed_0")) {
			case "list":
				context.channel().sendMessage(String.format("Groups for %s: %s",
						context.bot().getUserHandler().formatUser(userId, context.server()),
						String.join(", ", UserQueries.getDirectGroups(context.bot().getDatabase(), userId))));
				return true;
			case "add":
				if (!UserQueries.addToGroup(context.bot().getDatabase(), userId, arguments.get("group"))) {
					throw new CommandException("The user is already in the group");
				}

				context.channel().sendMessage("User %s added to group".formatted(context.bot().getUserHandler().formatUser(userId, context.server())));
				return true;
			case "remove":
				if (!UserQueries.removeFromGroup(context.bot().getDatabase(), userId, arguments.get("group"))) {
					throw new CommandException("The user wasn't in the group");
				}

				context.channel().sendMessage("User %s removed from group".formatted(context.bot().getUserHandler().formatUser(userId, context.server())));
				return true;
			}
		} else { // group handling itself
			switch (arguments.get("unnamed_0")) {
			case "list":
				context.channel().sendMessage("Groups: "+String.join(", ", UserQueries.getGroups(context.bot().getDatabase())));
				return true;
			case "add":
				if (!UserQueries.addGroup(context.bot().getDatabase(), arguments.get("group"))) {
					throw new CommandException("The group already exists");
				}

				context.channel().sendMessage("Group added");
				return true;
			case "remove":
				if (!UserQueries.removeGroup(context.bot().getDatabase(), arguments.get("group"))) {
					throw new CommandException("No such group");
				}

				context.channel().sendMessage("Group removed");
				return true;
			case "listsub":
				context.channel().sendMessage(String.join(", ", UserQueries.getGroupChildren(context.bot().getDatabase(), arguments.get("group"))));
				return true;
			case "addsub":
				if (!UserQueries.addGroupChild(context.bot().getDatabase(), arguments.get("parent"), arguments.get("child"))) {
					throw new CommandException("The group relation already exists");
				}

				context.channel().sendMessage("Group relation added");
				return true;
			case "removesub":
				if (!UserQueries.removeGroupChild(context.bot().getDatabase(), arguments.get("parent"), arguments.get("child"))) {
					throw new CommandException("The group relation already exists");
				}

				context.channel().sendMessage("Group relation added");
				return true;
			}
		}

		throw new IllegalStateException();
	}
}
