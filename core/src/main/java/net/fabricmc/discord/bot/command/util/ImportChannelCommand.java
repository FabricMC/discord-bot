/*
 * Copyright (c) 2021, 2022 FabricMC
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

package net.fabricmc.discord.bot.command.util;

import static net.fabricmc.discord.bot.command.util.ExportChannelCommand.MSG_BOUNDARY;
import static net.fabricmc.discord.bot.command.util.ExportChannelCommand.TAG_END;
import static net.fabricmc.discord.bot.command.util.ExportChannelCommand.TAG_START;
import static net.fabricmc.discord.bot.util.FormatUtil.MAX_MESSAGE_LENGTH;

import java.awt.Color;
import java.io.IOException;
import java.io.StringReader;
import java.net.URL;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.fabricmc.discord.bot.UserHandler;
import net.fabricmc.discord.bot.command.Command;
import net.fabricmc.discord.bot.command.CommandContext;
import net.fabricmc.discord.io.Channel;
import net.fabricmc.discord.io.Message;
import net.fabricmc.discord.io.MessageAttachment;
import net.fabricmc.discord.io.MessageEmbed;

public final class ImportChannelCommand extends Command {
	@Override
	public String name() {
		return "importChannel";
	}

	@Override
	public String usage() {
		return "<channel> [<contentUrl>]";
	}

	@Override
	public String permission() {
		return UserHandler.ADMIN_PERMISSION;
	}

	@Override
	public boolean run(CommandContext context, Map<String, String> arguments) throws Exception {
		Channel channel = getTextChannel(context, arguments.get("channel"));
		String content = retrieveContent(context, arguments.get("contentUrl"));

		List<Tag> tags = new ArrayList<>();
		content = extractTags(content, tags);

		boolean hasBoundaries = content.contains(MSG_BOUNDARY);
		int msgCount = 0;
		int firstTagIdx = 0;
		int startPos = 0;

		while (startPos < content.length() || firstTagIdx < tags.size() && tags.get(firstTagIdx).offset <= startPos) {
			int delimiterSize = MSG_BOUNDARY.length();
			int endPos = hasBoundaries ? content.indexOf(MSG_BOUNDARY, startPos) : -1;

			if (endPos < 0) {
				endPos = content.length();
				delimiterSize = 0;
			}

			if (endPos - startPos > MAX_MESSAGE_LENGTH) { // oversized -> try to split at line break
				delimiterSize = 1;
				endPos = content.lastIndexOf('\n', startPos + MAX_MESSAGE_LENGTH); // FIXME: this should skip over markdown blocks

				if (endPos <= startPos) {  // not found (-1), before start or at start -> try to split at space
					endPos = content.lastIndexOf(' ', startPos + MAX_MESSAGE_LENGTH);

					if (endPos <= startPos) {  // not found (-1), before start or at start -> split at size limit
						delimiterSize = 0;
						endPos = startPos + MAX_MESSAGE_LENGTH;
					}
				}
			}

			Message.Builder mb = new Message.Builder()
					.content(content.substring(startPos, endPos))
					.noAllowedMentions();

			for (int i = firstTagIdx; i < tags.size(); i++) {
				Tag tag = tags.get(i);
				if (tag.offset > endPos) break;

				String type = tag.data.get("type").getAsString();

				switch (type) {
				case "attachment": {
					MessageAttachment.Builder builder = new MessageAttachment.Builder()
							.name(tag.data.get("fileName").getAsString())
							.description(tag.data.has("description") ? tag.data.get("description").getAsString() : null);

					if (tag.data.has("data")) {
						builder.data(Base64.getDecoder().decode(tag.data.get("data").getAsString()));
					} else {
						builder.data(new URL(tag.data.get("url").getAsString()));
					}

					mb.attachment(builder.build());

					break;
				}
				case"embed":
					if (tag.data.get("embedType").getAsString().equals("rich")) {
						mb.embed(new MessageEmbed.Builder()
								.title(tag.data.has("title") ? tag.data.get("title").getAsString() : null, tag.data.has("url") ? tag.data.get("url").getAsString() : null)
								.description(tag.data.has("description") ? tag.data.get("description").getAsString() : null)
								.time(tag.data.has("timestamp") ? Instant.ofEpochMilli(tag.data.get("timestamp").getAsLong()) : null)
								.color(tag.data.has("color") ? new Color(tag.data.get("color").getAsInt(), true) : null)
								.build());
					}

					break;
				default:
					throw new IOException("Invalid type: "+type);
				}

				firstTagIdx = i + 1;
			}

			channel.send(mb.build());
			msgCount++;

			startPos = endPos + delimiterSize;
		}

		context.channel().send("Imported %d messages".formatted(msgCount));

		return true;
	}

	private static String extractTags(String content, List<Tag> tags) {
		int shifted = 0;
		int pos = 0;

		while ((pos = content.indexOf(TAG_START, pos)) >= 0) {
			int end = content.indexOf(TAG_END, pos + TAG_START.length());
			if (end < 0) throw new IllegalArgumentException("unclosed tag at pos "+(pos + shifted));

			JsonObject obj = JsonParser.parseReader(new StringReader(content.substring(pos + TAG_START.length(), end))).getAsJsonObject();
			if (!obj.has("type")) throw new IllegalArgumentException("missing type at pos "+(pos + shifted));

			tags.add(new Tag(pos, obj));

			end += TAG_END.length();
			shifted += end - pos;
			content = content.substring(0, pos).concat(content.substring(end));
		}

		return content;
	}

	private record Tag(int offset, JsonObject data) { }
}
