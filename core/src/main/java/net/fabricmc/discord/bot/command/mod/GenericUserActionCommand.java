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

public final class GenericUserActionCommand extends Command {
	public GenericUserActionCommand(UserActionType type, boolean activate) {
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
			return "<user> <duration> <reason...>";
		} else {
			return "<user> <reason...>";
		}
	}

	@Override
	public String permission() {
		return type.id;
	}

	@Override
	public boolean run(CommandContext context, Map<String, String> arguments) throws Exception {
		int targetUserId = getUserId(context, arguments.get("user"));
		String reason = arguments.get("reason");

		if (activate) {
			checkSelfTarget(context, targetUserId);
			checkImmunity(context, targetUserId, false);

			String duration = type.hasDuration ? arguments.get("duration") : null;

			ActionUtil.applyAction(type, 0, targetUserId, duration, reason, null, context);
		} else {
			ActionUtil.suspendAction(type, targetUserId, reason, context);
		}

		return true;
	}

	private final UserActionType type;
	private final boolean activate;
}
