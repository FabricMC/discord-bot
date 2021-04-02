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

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.javacord.api.entity.channel.TextChannel;
import org.javacord.api.entity.message.Message;
import org.javacord.api.entity.server.Server;
import org.javacord.api.event.message.MessageCreateEvent;
import org.javacord.api.event.message.MessageEditEvent;
import org.javacord.api.listener.ChainableGloballyAttachableListenerManager;
import org.javacord.api.listener.message.MessageCreateListener;
import org.javacord.api.listener.message.MessageEditListener;

import net.fabricmc.discord.bot.DiscordBot;
import net.fabricmc.discord.bot.config.ConfigKey;
import net.fabricmc.discord.bot.config.ValueSerializers;
import net.fabricmc.discord.bot.database.query.FilterQueries;
import net.fabricmc.discord.bot.database.query.FilterQueries.FilterData;
import net.fabricmc.discord.bot.database.query.FilterQueries.FilterEntry;
import net.fabricmc.discord.bot.filter.FilterType.MessageMatcher;

public final class FilterHandler implements MessageCreateListener, MessageEditListener {
	private static final Logger LOGGER = LogManager.getLogger(FilterHandler.class);
	private static final ConfigKey<Long> ALERT_CHANNEL = new ConfigKey<>("alertChannel", ValueSerializers.LONG);

	private final DiscordBot bot;
	private volatile List<CompiledFilter> filters = Collections.emptyList();
	private volatile TextChannel alertChannel;

	public FilterHandler(DiscordBot bot) {
		this.bot = bot;

		reloadFilters();

		bot.registerConfigEntry(ALERT_CHANNEL, () -> -1L);
		// TODO: subscribe to config changes

		bot.getActiveHandler().registerReadyHandler(this::onReady);
		bot.getActiveHandler().registerGoneHandler(this::onGone);
	}

	public void reloadFilters() {
		try {
			Collection<FilterEntry> filterEntries = FilterQueries.getFilters(bot.getDatabase());
			List<CompiledFilter> compiledFilters = new ArrayList<>(filterEntries.size());

			for (FilterEntry filter : filterEntries) {
				compiledFilters.add(new CompiledFilter(filter.type().compile(filter.pattern()), filter));
			}

			filters = compiledFilters;
		} catch (SQLException e) {
			throw new RuntimeException(e);
		}
	}

	private void onReady(Server server, long prevActive) {
		long channelId = bot.getConfigEntry(ALERT_CHANNEL);

		if (channelId >= 0) {
			TextChannel channel = server.getTextChannelById(channelId).orElse(null);

			if (channel == null) {
				LOGGER.warn("invalid alert channel: {}", channelId);
			} else {
				alertChannel = channel;
			}
		}
	}

	private void onGone(Server server) {
		alertChannel = null;
	}

	public DiscordBot getBot() {
		return bot;
	}

	public TextChannel getAlertChannel() {
		return alertChannel;
	}

	public void registerEarlyHandlers(ChainableGloballyAttachableListenerManager src) {
		src.addMessageCreateListener(this);
		src.addMessageEditListener(this);
	}

	@Override
	public void onMessageCreate(MessageCreateEvent event) {
		Server server = event.getServer().orElse(null);
		if (server == null || server.getId() != bot.getServerId()) return;

		handleMessage(event.getMessage(), false);
	}

	@Override
	public void onMessageEdit(MessageEditEvent event) {
		Server server = event.getServer().orElse(null);
		if (server == null || server.getId() != bot.getServerId()) return;

		handleMessage(event.requestMessage().join(), true);
	}

	private void handleMessage(Message message, boolean isEdit) {
		if (!message.getChannel().canYouManageMessages()) return;
		if (bot.getUserHandler().hasImmunity(message.getAuthor(), bot.getUserHandler().getBotUserId(), false)) return;

		String lcContent = message.getContent().toLowerCase(Locale.ENGLISH);
		List<CompiledFilter> filters = this.filters;

		FilterEntry bestFilter = null;
		FilterData bestFilterData = null;

		try {
			for (CompiledFilter compiledFilter : filters) {
				if (compiledFilter.matcher.matches(message, lcContent)) {
					FilterData data = FilterQueries.handleFilterHit(bot.getDatabase(), compiledFilter.filter);

					if (data != null
							&& (bestFilterData == null || FilterAction.compare(bestFilterData.action(), bestFilterData.actionData(), data.action(), data.actionData()) < 0)) {
						bestFilter = compiledFilter.filter;
						bestFilterData = data;
					}
				}
			}
		} catch (SQLException e) {
			throw new RuntimeException(e);
		}

		if (bestFilter == null) return;

		bestFilterData.action().apply(message, bestFilter, bestFilterData, this);
	}

	private record CompiledFilter(MessageMatcher matcher, FilterEntry filter) { }
}
