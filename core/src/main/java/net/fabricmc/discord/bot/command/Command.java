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

package net.fabricmc.discord.bot.command;

import java.util.Map;

public abstract class Command {
	/**
	 * @return the name of the command
	 */
	public abstract String name();

	/**
	 * Gets the usage of the command.
	 * The command usage will be used to parse arguments and determine whether an argument is required or not.
	 *
	 * @return the command usage
	 * @see CommandParser#parse(CharSequence, UsageParser.Node, Map)
	 */
	public abstract String usage();

	/**
	 * Called when a command should run.
	 *
	 * @param context the command context
	 * @param arguments the arguments the command was run with
	 * @return whether the command was successfully executed
	 */
	public abstract boolean run(CommandContext context, Map<String, String> arguments);
}
