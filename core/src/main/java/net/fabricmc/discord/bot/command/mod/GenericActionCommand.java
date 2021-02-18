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

import net.fabricmc.discord.bot.command.Command;
import net.fabricmc.discord.bot.command.CommandContext;

public final class GenericActionCommand extends Command {
	public GenericActionCommand(ActionType type, boolean activate) {
		this.type = type;
		this.activate = activate;
	}

	@Override
	public String name() {
		if (activate) {
			return type.id;
		} else {
			return "un"+type.id;
		}
	}

	@Override
	public String usage() {
		if (type.hasDuration && activate) {
			return "<user> <duration> <reason>";
		} else {
			return "<user> <reason>";
		}
	}

	@Override
	public String getPermission() {
		return type.id;
	}

	@Override
	public boolean run(CommandContext context, Map<String, String> arguments) {
		String target = arguments.get("user");
		String reason = arguments.get("reason");

		if (activate) {
			String duration = type.hasDuration ? arguments.get("duration") : null;

			return ActionUtil.applyAction(type, target, duration, reason, 0, context);
		} else {
			return ActionUtil.suspendAction(type, target, reason, context);
		}
	}

	private final ActionType type;
	private final boolean activate;
}
