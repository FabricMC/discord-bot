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

import org.javacord.api.entity.channel.TextChannel;
import org.javacord.api.entity.message.Message;
import org.javacord.api.entity.message.MessageBuilder;
import org.javacord.api.entity.message.embed.EmbedBuilder;

import net.fabricmc.discord.bot.DiscordBot;
import net.fabricmc.discord.bot.UserHandler;
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
	};

	public final String id;

	public static FilterAction get(String id) {
		for (FilterAction type : values()) {
			if (type.id.equals(id)) {
				return type;
			}
		}

		throw new IllegalArgumentException("invalid type: "+id);
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

	public abstract void apply(Message message, FilterEntry filter, FilterData filterData, FilterHandler filterHandler);

	protected int compare(String dataA, String dataB) {
		return 0;
	}
}