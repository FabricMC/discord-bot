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

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.CompletionException;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.javacord.api.entity.channel.TextChannel;
import org.javacord.api.entity.message.Message;
import org.javacord.api.entity.message.MessageAttachment;
import org.javacord.api.entity.message.MessageBuilder;
import org.javacord.api.entity.message.MessageSet;
import org.javacord.api.entity.message.embed.Embed;
import org.javacord.api.exception.DiscordException;

import net.fabricmc.discord.bot.UserHandler;
import net.fabricmc.discord.bot.command.Command;
import net.fabricmc.discord.bot.command.CommandContext;
import net.fabricmc.discord.bot.command.CommandException;
import net.fabricmc.discord.bot.util.DiscordUtil;
import net.fabricmc.discord.bot.util.FormatUtil;
import net.fabricmc.discord.bot.util.FormatUtil.OutputType;

public final class ExportChannelCommand extends Command {
	static final int MSG_COUNT_LIMIT = 100;
	static final String MSG_BOUNDARY = "\n-----msgbound-----\n";
	static final String TAG_START = "<<<<<";
	static final String TAG_END = ">>>>>";

	static final Gson GSON = new Gson();

	@Override
	public String name() {
		return "exportChannel";
	}

	@Override
	public String usage() {
		return "<channel> [<markBoundaries>] [--embedData]";
	}

	@Override
	public String permission() {
		return UserHandler.ADMIN_PERMISSION;
	}

	@Override
	public boolean run(CommandContext context, Map<String, String> arguments) throws Exception {
		TextChannel channel = getTextChannel(context, arguments.get("channel"));
		boolean markBoundaries = Boolean.parseBoolean(arguments.getOrDefault("markBoundaries", "false"));

		MessageSet messages = DiscordUtil.join(channel.getMessages(MSG_COUNT_LIMIT));
		if (messages.isEmpty()) throw new CommandException("empty channel");

		boolean embedData = arguments.containsKey("embedData");
		StringBuilder sb = new StringBuilder(FormatUtil.MAX_MESSAGE_LENGTH + MSG_BOUNDARY.length());
		boolean first = true;

		for (Message message : messages) {
			if (first) {
				first = false;
			} else if (markBoundaries) {
				sb.append(MSG_BOUNDARY);
			} else {
				sb.append("\n");
			}

			exportMessage(context, message, embedData, sb);
		}

		uploadExport(context, sb);

		return true;
	}

	static void exportMessage(CommandContext context, Message message, boolean embedData, StringBuilder out) {
		out.append(message.getContent());

		for (MessageAttachment attachment : message.getAttachments()) {
			JsonObject obj = new JsonObject();
			obj.addProperty("type", "attachment");
			obj.addProperty("fileName", attachment.getFileName());
			obj.addProperty("url", attachment.getUrl().toString());

			if (embedData) {
				try {
					obj.addProperty("data", Base64.getEncoder().encodeToString(attachment.downloadAsByteArray().join()));
				} catch (CompletionException e) {
					Throwable cause = e.getCause();

					if (cause != null) {
						cause.printStackTrace();
						context.channel().sendMessage(String.format("Error fetching data from %s: %s",
								FormatUtil.escape(attachment.getUrl().toString(), OutputType.INLINE_CODE, true),
								FormatUtil.escapePlain(attachment.getUrl().toString())));
					} else {
						e.printStackTrace();
					}
				}
			}

			out.append(TAG_START);
			GSON.toJson(obj, out);
			out.append(TAG_END);
		}

		for (Embed embed : message.getEmbeds()) {
			JsonObject obj = new JsonObject();
			obj.addProperty("type", "embed");
			obj.addProperty("embedType", embed.getType());
			if (embed.getTitle().isPresent()) obj.addProperty("title", embed.getTitle().orElseThrow());
			if (embed.getDescription().isPresent()) obj.addProperty("description", embed.getDescription().orElseThrow());
			if (embed.getUrl().isPresent()) obj.addProperty("url", embed.getUrl().orElseThrow().toString());
			if (embed.getTimestamp().isPresent()) obj.addProperty("timestamp", embed.getTimestamp().orElseThrow().toEpochMilli());
			if (embed.getColor().isPresent()) obj.addProperty("color", embed.getColor().orElseThrow().getRGB());
			// TODO: remaining properties

			out.append(TAG_START);
			GSON.toJson(obj, out);
			out.append(TAG_END);
		}
	}

	static void uploadExport(CommandContext context, CharSequence data) throws DiscordException {
		Message msg = DiscordUtil.join(new MessageBuilder()
				.addAttachment(data.toString().getBytes(StandardCharsets.UTF_8), "contents.txt")
				.send(context.channel()));

		msg.edit(msg.getAttachments().get(0).getUrl().toString());
	}
}
