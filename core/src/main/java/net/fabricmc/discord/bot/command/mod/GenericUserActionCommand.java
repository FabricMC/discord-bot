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
import net.fabricmc.discord.bot.command.mod.ActionUtil.UserMessageAction;

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
			return "<user/message> <duration> <reason...> ([--keep] | [--clean] | [--cleanLocal]) [--silent]";
		} else {
			return "<user/message> <reason...> ([--keep] | [--clean] | [--cleanLocal]) [--silent]";
		}
	}

	@Override
	public String permission() {
		return type.id;
	}

	@Override
	public boolean run(CommandContext context, Map<String, String> arguments) throws Exception {
		UserTarget target = getUserTarget(context, arguments.get("user/message"));
		String reason = arguments.get("reason");
		UserMessageAction targetMessageAction;

		if (arguments.containsKey("clean")) {
			targetMessageAction = UserMessageAction.CLEAN;
		} else if (arguments.containsKey("cleanLocal")) {
			targetMessageAction = UserMessageAction.CLEAN_LOCAL;
		} else if (target.message() == null || arguments.containsKey("keep")) {
			targetMessageAction = UserMessageAction.NONE;
		} else {
			targetMessageAction = UserMessageAction.DELETE;
		}

		boolean notifyTarget = !arguments.containsKey("silent");

		if (activate) {
			checkSelfTarget(context, target.userId());
			checkImmunity(context, target.userId(), false);

			String duration = type.hasDuration ? arguments.get("duration") : null;

			ActionUtil.applyUserAction(type, 0, target.userId(), duration, reason, target.message(), targetMessageAction, notifyTarget, context);
		} else {
			ActionUtil.suspendAction(type, target.userId(), reason, notifyTarget, context);
		}

		return true;
	}

	private final UserActionType type;
	private final boolean activate;
}
