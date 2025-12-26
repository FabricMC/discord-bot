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

import net.fabricmc.discord.bot.UserHandler;
import net.fabricmc.discord.bot.command.Command;
import net.fabricmc.discord.bot.command.CommandContext;
import net.fabricmc.discord.bot.command.CommandException;
import net.fabricmc.discord.bot.database.query.NoteQueries;
import net.fabricmc.discord.bot.database.query.NoteQueries.NoteEntry;
import net.fabricmc.discord.bot.util.FormatUtil;
import net.fabricmc.discord.io.Channel;
import net.fabricmc.discord.io.MessageEmbed;

public final class NoteCommand extends Command {
	@Override
	public String name() {
		return "note";
	}

	@Override
	public String usage() {
		return "add <user> <note...> | list <user> | get <id> | set <id> <note...> | remove <id>";
	}

	@Override
	public String permission() {
		return "note";
	}

	@Override
	public boolean run(CommandContext context, Map<String, String> arguments) throws Exception {
		switch (arguments.get("unnamed_0")) {
		case "add": {
			int targetUserId = getUserId(context, arguments.get("user"));
			NoteEntry entry = NoteQueries.create(context.bot().getDatabase(), targetUserId, context.userId(), arguments.get("note"));

			LongList targets = context.bot().getUserHandler().getDiscordUserIds(targetUserId);
			Instant creationTime = Instant.ofEpochMilli(entry.creationTime());
			String description = "User %d has been noted:%s\n\n**Note:** %s".formatted(targetUserId, FormatUtil.formatUserList(targets, context), entry.content());
			Channel logChannel = context.bot().getLogHandler().getLogChannel();

			MessageEmbed.Builder msgBuilder = new MessageEmbed.Builder()
					.title("User noted")
					.description(description)
					.footer("Note ID: %d".formatted(entry.id()))
					.time(creationTime);

			if (context.channel() != logChannel) {
				context.channel().send(msgBuilder.build());
			}

			if (logChannel != null) {
				// include executing moderator info
				msgBuilder.description("%s\n**Moderator:** %s".formatted(description, UserHandler.formatDiscordUser(context.user())));

				logChannel.send(msgBuilder.build());
			}

			return true;
		}
		case "list": {
			int targetUserId = getUserId(context, arguments.get("user"));
			Collection<NoteEntry> notes = NoteQueries.getAll(context.bot().getDatabase(), targetUserId);

			if (notes.isEmpty()) {
				context.channel().send(String.format("No notes for user %d", targetUserId));
			} else {
				StringBuilder sb = new StringBuilder(String.format("Notes for user %d:", targetUserId));

				for (NoteEntry note : notes) {
					String content;

					if (note.content().length() < 20) {
						content = note.content();
					} else {
						content = note.content().substring(0, 18)+"…";
					}

					sb.append(String.format("\n%d %s: %s",
							note.id(),
							FormatUtil.dateFormatter.format(Instant.ofEpochMilli(note.creationTime())),
							content));
				}

				context.channel().send(sb.toString());
			}

			return true;
		}
		case "get": {
			NoteEntry note = NoteQueries.get(context.bot().getDatabase(), Integer.parseInt(arguments.get("id")));
			if (note == null) throw new CommandException("Unknown note");

			LongList targets = context.bot().getUserHandler().getDiscordUserIds(note.targetUserId());

			context.channel().send(new MessageEmbed.Builder()
					.title("Note %d details".formatted(note.id()))
					.description(String.format("**User %d:**%s\n**Moderator:** %s\n**Creation:** %s\n**Note:** %s",
							note.targetUserId(),
							FormatUtil.formatUserList(targets, context),
							context.bot().getUserHandler().formatUser(note.actorUserId(), context.server()),
							FormatUtil.dateTimeFormatter.format(Instant.ofEpochMilli(note.creationTime())),
							note.content()))
					.build());

			return true;
		}
		case "set":
			if (!NoteQueries.setContent(context.bot().getDatabase(), Integer.parseInt(arguments.get("id")), arguments.get("note"))) {
				throw new CommandException("Unknown note");
			}

			context.channel().send("Note updated");
			return true;
		case "remove":
			if (!NoteQueries.remove(context.bot().getDatabase(), Integer.parseInt(arguments.get("id")))) {
				throw new CommandException("Unknown note");
			}

			context.channel().send("Note removed");
			return true;
		}

		throw new IllegalStateException();
	}
}
