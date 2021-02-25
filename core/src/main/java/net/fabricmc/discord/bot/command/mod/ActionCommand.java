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

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import org.javacord.api.entity.message.embed.EmbedBuilder;

import net.fabricmc.discord.bot.command.Command;
import net.fabricmc.discord.bot.command.CommandContext;
import net.fabricmc.discord.bot.command.CommandException;
import net.fabricmc.discord.bot.database.query.ActionQueries;
import net.fabricmc.discord.bot.database.query.ActionQueries.ActionEntry;

public final class ActionCommand extends Command {
	@Override
	public String name() {
		return "action";
	}

	@Override
	public String usage() {
		return "list <user> | get <id>";
	}

	@Override
	public String getPermission() {
		return "action";
	}

	@Override
	public boolean run(CommandContext context, Map<String, String> arguments) throws Exception {
		switch (arguments.get("unnamed_0")) {
		case "list": {
			int userId = getUserId(context, arguments.get("user"));
			Collection<ActionEntry> actions = ActionQueries.getActions(context.bot().getDatabase(), userId);

			if (actions.isEmpty()) {
				context.channel().sendMessage(String.format("No actions for user %d", userId));
			} else {
				StringBuilder sb = new StringBuilder(String.format("Actions for user %d:", userId));

				for (ActionEntry action : actions) {
					String duration, reason;

					if (action.expirationTime() < 0) {
						duration = " perm";
					} else if (action.expirationTime() == 0) {
						duration = "";
					} else {
						long durationMs = action.expirationTime() - action.creationTime();

						duration = " "+ActionUtil.formatDuration(durationMs);
					}

					if (action.reason() == null || action.reason().isEmpty()) {
						reason = "";
					} else if (action.reason().length() < 20) {
						reason = ": "+action.reason();
					} else {
						reason = ": "+action.reason().substring(0, 18)+"…";
					}

					sb.append(String.format("\n%d %s: %s%s%s",
							action.id(),
							ActionUtil.dateFormatter.format(Instant.ofEpochMilli(action.creationTime())),
							action.type().id,
							duration,
							reason));
				}

				context.channel().sendMessage(sb.toString());
			}

			return true;
		}
		case "get": {
			int actionId = Integer.parseInt(arguments.get("id"));
			ActionEntry action = ActionQueries.getAction(context.bot().getDatabase(), actionId);
			if (action == null) throw new CommandException("Unknown action");

			long time = System.currentTimeMillis();
			String status, expirationSuffix, durationSuffix, suspensionSuffix, reasonSuffix;

			if (action.expirationTime() == 0) {
				status = "durationless";
			} else if (action.suspensionTime() >= 0) {
				status = "suspended";
			} else if (action.expirationTime() > 0 && action.expirationTime() <= time) {
				status = "expired";
			} else {
				status = "active";
			}

			if (action.expirationTime() < 0) {
				expirationSuffix = "\n**Expiration:** never";
				durationSuffix = "\n**Duration:** permanent";
			} else if (action.expirationTime() == 0) {
				expirationSuffix = "";
				durationSuffix = "";
			} else {
				expirationSuffix = String.format("\n**Expiration:** %s",
						ActionUtil.dateTimeFormatter.format(Instant.ofEpochMilli(action.expirationTime())));
				durationSuffix = String.format("\n**Duration:** %s",
						ActionUtil.formatDuration(action.expirationTime() - action.creationTime()));
			}

			if (action.suspensionTime() < 0) {
				suspensionSuffix = "";
			} else {
				String suspensionReasonSuffix;

				if (action.suspendReason() == null || action.suspendReason().isEmpty()) {
					suspensionReasonSuffix = "";
				} else {
					suspensionReasonSuffix = String.format("\n**Susp. Reason:** %s",
							action.suspendReason());
				}

				suspensionSuffix = String.format("\n**Suspension:** %s\n**Suspender:** %s%s\n**Eff. Duration:** %s",
						ActionUtil.dateTimeFormatter.format(Instant.ofEpochMilli(action.suspensionTime())),
						context.bot().getUserHandler().formatUser(action.suspenderUserId(), context.server()),
						suspensionReasonSuffix,
						ActionUtil.formatDuration(action.suspensionTime() - action.creationTime()));
			}

			if (action.reason() == null || action.reason().isEmpty()) {
				reasonSuffix = "";
			} else {
				reasonSuffix = "\n**Reason:** %s".formatted(action.reason());
			}

			List<Long> targets = context.bot().getUserHandler().getDiscordUserIds(action.targetUserId());

			context.channel().sendMessage(new EmbedBuilder()
					.setTitle("Action %d details".formatted(action.id()))
					.setDescription(String.format("**User %d:**%s\n**Type:** %s\n**Status:** %s\n**Moderator:** %s\n**Creation:** %s%s%s%s%s",
							action.targetUserId(),
							ActionUtil.formatUserList(targets, context),
							action.type().id,
							status,
							context.bot().getUserHandler().formatUser(action.actorUserId(), context.server()),
							ActionUtil.dateTimeFormatter.format(Instant.ofEpochMilli(action.creationTime())),
							expirationSuffix, durationSuffix, suspensionSuffix, reasonSuffix)));

			return true;
		}
		}

		throw new IllegalStateException();
	}
}
