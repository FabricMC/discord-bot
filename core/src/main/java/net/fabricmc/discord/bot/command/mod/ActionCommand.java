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
import java.util.Map;

import it.unimi.dsi.fastutil.longs.LongList;

import net.fabricmc.discord.bot.command.Command;
import net.fabricmc.discord.bot.command.CommandContext;
import net.fabricmc.discord.bot.command.CommandException;
import net.fabricmc.discord.bot.database.query.ActionQueries;
import net.fabricmc.discord.bot.database.query.ActionQueries.ActionEntry;
import net.fabricmc.discord.bot.message.Paginator;
import net.fabricmc.discord.bot.util.FormatUtil;
import net.fabricmc.discord.io.MessageEmbed;

public final class ActionCommand extends Command {
	private static final int LIST_PAGE_ENTRIES = 20;
	private static final int REASON_PREVIEW_MAXLEN = 40;

	@Override
	public String name() {
		return "action";
	}

	@Override
	public String usage() {
		return "list <user> | (get|show) <id> | (setreason|updatereason) <id> <reason...>";
	}

	@Override
	public String permission() {
		return "action";
	}

	@Override
	public boolean run(CommandContext context, Map<String, String> arguments) throws Exception {
		switch (arguments.get("unnamed_0")) {
		case "list": {
			int userId = getUserId(context, arguments.get("user"));
			Collection<ActionEntry> actions = ActionQueries.getActions(context.bot().getDatabase(), ActionType.Kind.USER, userId);

			if (actions.isEmpty()) {
				context.channel().send(String.format("No actions for user %d", userId));
			} else {
				Paginator.Builder builder = new Paginator.Builder(context.user()).title("User %d Actions".formatted(userId));
				StringBuilder sb = new StringBuilder();
				int count = 0;

				for (ActionEntry action : actions) {
					if (count % LIST_PAGE_ENTRIES == 0 && count > 0) {
						builder.page(sb);
						sb.setLength(0);
					}

					count++;
					String duration, reason;

					if (action.expirationTime() < 0) {
						duration = " perm";
					} else if (action.expirationTime() == 0) {
						duration = "";
					} else {
						long durationMs = action.expirationTime() - action.creationTime();

						duration = " "+FormatUtil.formatDuration(durationMs, 2);
					}

					if (action.reason() == null || action.reason().isEmpty()) {
						reason = "";
					} else {
						reason = action.reason().replaceAll("\\s+", " ").trim();

						if (reason.length() > REASON_PREVIEW_MAXLEN) {
							reason = reason.substring(0, REASON_PREVIEW_MAXLEN - 2).concat("…");
						}

						reason = ": ".concat(reason);
					}

					if (sb.length() > 0) sb.append('\n');
					sb.append(String.format("`%d` %s: **%s%s**%s",
							action.id(),
							FormatUtil.dateFormatter.format(Instant.ofEpochMilli(action.creationTime())),
							action.type().getId(),
							duration,
							reason));
				}

				if (sb.length() > 0) {
					builder.page(sb);
				}

				builder.buildAndSend(context.channel());
			}

			return true;
		}
		case "get":
		case "show": {
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
						FormatUtil.dateTimeFormatter.format(Instant.ofEpochMilli(action.expirationTime())));
				durationSuffix = String.format("\n**Duration:** %s",
						FormatUtil.formatDuration(action.expirationTime() - action.creationTime()));
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
						FormatUtil.dateTimeFormatter.format(Instant.ofEpochMilli(action.suspensionTime())),
						context.bot().getUserHandler().formatUser(action.suspenderUserId(), context.server()),
						suspensionReasonSuffix,
						FormatUtil.formatDuration(action.suspensionTime() - action.creationTime()));
			}

			if (action.reason() == null || action.reason().isEmpty()) {
				reasonSuffix = "";
			} else {
				reasonSuffix = "\n**Reason:** %s".formatted(action.reason());
			}

			LongList targets = context.bot().getUserHandler().getDiscordUserIds((int) action.targetId());

			context.channel().send(new MessageEmbed.Builder()
					.title("Action %d details".formatted(action.id()))
					.description(String.format("**User %d:**%s\n**Type:** %s\n**Status:** %s\n**Moderator:** %s\n**Creation:** %s%s%s%s%s",
							action.targetId(),
							FormatUtil.formatUserList(targets, context),
							action.type().getId(),
							status,
							context.bot().getUserHandler().formatUser(action.actorUserId(), context.server()),
							FormatUtil.dateTimeFormatter.format(Instant.ofEpochMilli(action.creationTime())),
							expirationSuffix, durationSuffix, suspensionSuffix, reasonSuffix))
					.build());

			return true;
		}
		case "setreason":
		case "updatereason":
			if (!ActionQueries.setReason(context.bot().getDatabase(), Integer.parseInt(arguments.get("id")), arguments.get("reason"))) {
				throw new CommandException("Unknown action");
			} else {
				context.channel().send("Action reason updated");
			}

			return true;
		}

		throw new IllegalStateException();
	}
}
