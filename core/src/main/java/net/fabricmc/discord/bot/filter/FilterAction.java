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

package net.fabricmc.discord.bot.filter;

import java.io.IOException;
import java.io.StringReader;
import java.util.Locale;

import com.google.gson.stream.JsonReader;
import org.javacord.api.entity.channel.TextChannel;
import org.javacord.api.entity.message.Message;
import org.javacord.api.entity.message.MessageBuilder;
import org.javacord.api.entity.message.embed.EmbedBuilder;
import org.javacord.api.entity.server.Server;

import net.fabricmc.discord.bot.DiscordBot;
import net.fabricmc.discord.bot.UserHandler;
import net.fabricmc.discord.bot.command.mod.ActionUtil;
import net.fabricmc.discord.bot.command.mod.ActionUtil.UserMessageAction;
import net.fabricmc.discord.bot.command.mod.UserActionType;
import net.fabricmc.discord.bot.database.query.FilterQueries.FilterData;
import net.fabricmc.discord.bot.database.query.FilterQueries.FilterEntry;
import net.fabricmc.discord.bot.util.DiscordUtil;

public enum FilterAction {
	ALERT("alert") {
		@Override
		public void apply(Message message, FilterEntry filter, FilterData filterData, FilterHandler filterHandler) {
			TextChannel alertChannel = filterHandler.getAlertChannel();
			if (alertChannel == null) return;

			new MessageBuilder()
			.setContent("@here")
			.setEmbed(new EmbedBuilder()
					.setTitle("%s filter triggered: %s".formatted(filter.type().id, filterData.groupName()))
					.setDescription(String.format("**User:** %s\n**Channel:** <#%d>\n**[Message](%s):**\n\n%s\n\n**Filter pattern:** `%s`",
							UserHandler.formatDiscordUser(message.getAuthor()),
							message.getChannel().getId(),
							message.getLink(), message.getContent(),
							filter.pattern()))
					.setFooter("Filter ID: %d".formatted(filter.id()))
					.setTimestamp(message.getLastEditTimestamp().orElse(message.getCreationTimestamp())))
			.setAllowedMentions(DiscordUtil.NO_MENTIONS)
			.send(alertChannel)
			.exceptionally(exc -> {
				exc.printStackTrace();
				return null;
			});
		}
	},

	DELETE("delete") {
		@Override
		public void apply(Message message, FilterEntry filter, FilterData filterData, FilterHandler filterHandler) {
			deleteAndLog(message, filter, filterData, filterHandler.getBot());
		}
	},

	ACTION("action") {
		@Override
		public void apply(Message message, FilterEntry filter, FilterData filterData, FilterHandler filterHandler) throws Exception {
			DiscordBot bot = filterHandler.getBot();
			Server server = message.getServer().orElseThrow();
			int userId = bot.getUserHandler().getUserId(message.getAuthor().asUser().orElseThrow());

			String data = filterData.actionData();
			UserActionType actionType = UserActionType.BAN;
			int actionData = 0;
			String duration = "perm";
			UserMessageAction messageAction = UserMessageAction.DELETE;

			if (data != null && !data.isEmpty()) {
				try (JsonReader reader = new JsonReader(new StringReader(data))) {
					reader.beginObject();

					while (reader.hasNext()) {
						String key = reader.nextName().toLowerCase(Locale.ENGLISH);

						switch (key) {
						case "type" -> actionType = UserActionType.parse(reader.nextString());
						case "data" -> actionData = reader.nextInt();
						case "duration" -> duration = reader.nextString();
						case "messageaction" -> messageAction = UserMessageAction.parse(reader.nextString());
						default -> throw new IOException("invalid action filter data key: "+key);
						}
					}

					reader.endObject();
				} catch (Throwable t) {
					throw new RuntimeException("parsing data for filter "+filter.id()+" failed", t);
				}
			}

			ActionUtil.applyUserAction(actionType, actionData, userId, duration, "auto-mod filter %d hit".formatted(filter.id()),
					bot.getMessageIndex().get(message), messageAction,
					true, "(group %s, type %s, pattern `%s`)".formatted(filterData.groupName(), filter.type().id, filter.pattern()),
					bot, server, null, bot.getUserHandler().getBotDiscordUser(server), bot.getUserHandler().getBotUserId());
		}

		@Override
		protected int compare(String dataA, String dataB) {
			// TODO: implement action severity comparison (type, duration)
			return 0;
		}
	};

	public final String id;

	public static FilterAction parse(String id) {
		for (FilterAction action : values()) {
			if (action.id.equals(id)) {
				return action;
			}
		}

		throw new IllegalArgumentException("invalid filter action: "+id);
	}

	/**
	 * Compare the priority of two actions
	 *
	 * @return 0 for same priority, <0 if a is lower priority, >0 if a is higher priority
	 */
	public static int compare(FilterAction a, String dataA, FilterAction b, String dataB) {
		int ret = a.ordinal() - b.ordinal();
		if (ret != 0) return ret;

		return a.compare(dataA, dataB);
	}

	private static void deleteAndLog(Message message, FilterEntry filter, FilterData filterData, DiscordBot bot) {
		TextChannel logChannel = bot.getLogHandler().getLogChannel();

		if (logChannel != null) {
			logChannel.sendMessage(new EmbedBuilder()
					.setTitle("Message deleted: %s (%s filter)".formatted(filterData.groupName(), filter.type().id))
					.setDescription(String.format("**User:** %s\n**Channel:** <#%d>\n**Message ID:** `%d`\n**Message:**\n\n%s\n\n**Filter pattern:** `%s`",
							UserHandler.formatDiscordUser(message.getAuthor()),
							message.getChannel().getId(),
							message.getId(),
							message.getContent(),
							filter.pattern()))
					.setFooter("Filter ID: %d".formatted(filter.id()))
					.setTimestampToNow());
		}

		message.delete("filter action (filter %d, group %s)".formatted(filter.id(), filterData.groupName()));
	}

	FilterAction(String id) {
		this.id = id;
	}

	public abstract void apply(Message message, FilterEntry filter, FilterData filterData, FilterHandler filterHandler) throws Exception;

	protected int compare(String dataA, String dataB) {
		return 0;
	}
}