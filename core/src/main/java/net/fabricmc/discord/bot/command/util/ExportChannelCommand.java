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
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionException;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import net.fabricmc.discord.bot.UserHandler;
import net.fabricmc.discord.bot.command.Command;
import net.fabricmc.discord.bot.command.CommandContext;
import net.fabricmc.discord.bot.command.CommandException;
import net.fabricmc.discord.bot.util.FormatUtil;
import net.fabricmc.discord.bot.util.FormatUtil.OutputType;
import net.fabricmc.discord.io.Channel;
import net.fabricmc.discord.io.DiscordException;
import net.fabricmc.discord.io.Message;
import net.fabricmc.discord.io.MessageAttachment;
import net.fabricmc.discord.io.MessageEmbed;
import net.fabricmc.discord.io.MessageEmbed.Type;

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
		Channel channel = getTextChannel(context, arguments.get("channel"));
		boolean markBoundaries = Boolean.parseBoolean(arguments.getOrDefault("markBoundaries", "false"));

		List<? extends Message> messages = channel.getMessages(MSG_COUNT_LIMIT);
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
			if (attachment.getDescription() != null) obj.addProperty("description", attachment.getDescription());
			obj.addProperty("url", attachment.getUrl().toString());

			if (embedData) {
				try {
					obj.addProperty("data", Base64.getEncoder().encodeToString(attachment.getBytes()));
				} catch (CompletionException e) {
					Throwable cause = e.getCause();

					if (cause != null) {
						cause.printStackTrace();
						context.channel().send(String.format("Error fetching data from %s: %s",
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

		for (MessageEmbed embed : message.getEmbeds()) {
			if (embed.getType() == Type.OTHER) continue; // unsupported

			JsonObject obj = new JsonObject();
			obj.addProperty("type", "embed");
			obj.addProperty("embedType", embed.getType().name);
			if (embed.getTitle() != null) obj.addProperty("title", embed.getTitle());
			if (embed.getTitleUrl() != null) obj.addProperty("url", embed.getTitleUrl().toString());
			if (embed.getDescription() != null) obj.addProperty("description", embed.getDescription());
			if (embed.getTime() != null) obj.addProperty("timestamp", embed.getTime().toEpochMilli());
			if (embed.getColor() != null) obj.addProperty("color", embed.getColor().getRGB());
			// TODO: remaining properties

			out.append(TAG_START);
			GSON.toJson(obj, out);
			out.append(TAG_END);
		}
	}

	static void uploadExport(CommandContext context, CharSequence data) throws DiscordException {
		Message msg = context.channel().send(new Message.Builder()
				.attachment(new MessageAttachment.Builder().data(data.toString().getBytes(StandardCharsets.UTF_8)).name("contents.txt").build())
				.build());

		msg.edit(msg.getAttachments().get(0).getUrl().toString());
	}
}
