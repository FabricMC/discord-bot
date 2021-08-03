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

package net.fabricmc.discord.bot.command.util;

import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import org.javacord.api.entity.channel.ServerTextChannel;
import org.javacord.api.entity.message.embed.EmbedBuilder;

import net.fabricmc.discord.bot.CachedMessage;
import net.fabricmc.discord.bot.MessageIndex;
import net.fabricmc.discord.bot.command.Command;
import net.fabricmc.discord.bot.command.CommandContext;
import net.fabricmc.discord.bot.command.CommandException;
import net.fabricmc.discord.bot.message.Paginator;
import net.fabricmc.discord.bot.util.DiscordUtil;
import net.fabricmc.discord.bot.util.FormatUtil;
import net.fabricmc.discord.bot.util.FormatUtil.OutputType;

public final class MessageCacheCommand extends Command {
	private static final int LIST_PAGE_ENTRIES = 10;
	private static final int CONTENT_PREVIEW_MAXLEN = 68;

	private static final DateTimeFormatter listDateTimeFormatter = DateTimeFormatter.ofPattern("'[`'yyyy-MM-dd'`][`'HH:mm:ss.SSS'`]'", Locale.ENGLISH).withZone(ZoneOffset.UTC);

	@Override
	public String name() {
		return "messageCache";
	}

	@Override
	public String usage() {
		return "list <user> | (get|show) <id> | stats";
	}

	@Override
	public String permission() {
		return "messageCache";
	}

	@Override
	public boolean run(CommandContext context, Map<String, String> arguments) throws Exception {
		switch (arguments.get("unnamed_0")) {
		case "list": {
			int userId = getUserId(context, arguments.get("user"));
			List<Long> discordUserIds = context.bot().getUserHandler().getDiscordUserIds(userId);
			List<CachedMessage> messages = new ArrayList<>(context.bot().getMessageIndex().getAllByAuthors(new LongOpenHashSet(discordUserIds), true));

			if (messages.isEmpty()) {
				context.channel().sendMessage(String.format("No messages for user %d", userId));
			} else {
				messages.sort(Comparator.comparing(CachedMessage::getCreationTime));

				Paginator.Builder builder = new Paginator.Builder(context.user()).title("User %d Messages".formatted(userId));
				StringBuilder sb = new StringBuilder();
				int count = 0;

				for (CachedMessage message : messages) {
					if (count % LIST_PAGE_ENTRIES == 0 && count > 0) {
						builder.page(sb);
						sb.setLength(0);
					}

					count++;

					String content = message.getContent().replaceAll("\\s+", " ").trim();

					if (content.length() > CONTENT_PREVIEW_MAXLEN) {
						content = content.substring(0, CONTENT_PREVIEW_MAXLEN - 2).concat("…");
					}

					if (sb.length() > 0) sb.append('\n');
					sb.append(String.format("%s[`%d`][<#%d>][%s]\n%s",
							listDateTimeFormatter.format(message.getCreationTime()),
							message.getId(),
							message.getChannelId(),
							(message.isDeleted() ? "deleted" : "[link](%s)".formatted(DiscordUtil.getMessageLink(context.server(), message.getChannelId(), message.getId()))),
							FormatUtil.escape(content, OutputType.INLINE_CODE, true)));
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
			long messageId = Long.parseLong(arguments.get("id"));
			CachedMessage message = context.bot().getMessageIndex().get(messageId);
			if (message == null) throw new CommandException("Unknown message");

			message.getAuthorDiscordId();

			context.channel().sendMessage(new EmbedBuilder()
					.setTitle("Message %d details".formatted(message.getId()))
					.setDescription(String.format("**User %d:** %s\n**Channel:** <#%d>\n**Creation:** %s\n**Status:** %s\n**Content:**%s\n**Attachments:** %d",
							context.bot().getUserHandler().getUserId(message.getAuthorDiscordId()),
							context.bot().getUserHandler().formatDiscordUser(message.getAuthorDiscordId(), context.server()),
							message.getChannelId(),
							FormatUtil.dateTimeFormatter.format(message.getCreationTime()),
							(message.isDeleted() ? "deleted" : "normal"),
							FormatUtil.escape(FormatUtil.truncateMessage(message.getContent(), 600), OutputType.CODE, true),
							message.getAttachments().length)));

			return true;
		}
		case "stats": {
			MessageIndex messageIndex = context.bot().getMessageIndex();
			List<ServerTextChannel> channels = new ArrayList<>(messageIndex.getCachedChannels());
			channels.sort(Comparator.comparing(ServerTextChannel::getName));

			StringBuilder sb = new StringBuilder();
			int total = 0;

			for (ServerTextChannel channel : channels) {
				if (!channel.canSee(context.user()) || !channel.canReadMessageHistory(context.user())) continue;

				int size = messageIndex.getSize(channel);
				if (size == 0) continue;

				if (sb.length() > 0) sb.append('\n');
				sb.append(String.format("<#%d>: %d", channel.getId(), size));
				total += size;
			}

			if (sb.length() > 0) sb.append('\n');
			sb.append(String.format("Total: %d", total));

			context.channel().sendMessage(sb.toString());

			return true;
		}
		}

		throw new IllegalStateException();
	}
}
