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

package net.fabricmc.discord.bot.filter;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import com.google.gson.stream.JsonReader;
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
import net.fabricmc.discord.bot.database.query.FilterQueries.FilterListEntry;
import net.fabricmc.discord.bot.database.query.FilterQueries.FilterListExceptionEntry;
import net.fabricmc.discord.bot.database.query.FilterQueries.GlobalFilterListExceptionEntry;
import net.fabricmc.discord.bot.filter.FilterType.MessageMatcher;
import net.fabricmc.discord.bot.util.DiscordUtil;
import net.fabricmc.discord.bot.util.HttpUtil;

public final class FilterHandler implements MessageCreateListener, MessageEditListener {
	private static final int filterListUpdatePeriodMin = 60;

	private static final Logger LOGGER = LogManager.getLogger(FilterHandler.class);
	private static final ConfigKey<Long> ALERT_CHANNEL = new ConfigKey<>("alertChannel", ValueSerializers.LONG);

	private final DiscordBot bot;
	private volatile List<CompiledFilter> filters = Collections.emptyList();
	private volatile TextChannel alertChannel;
	private final Map<FilterType, Set<String>> knownInvalidPatterns = new EnumMap<>(FilterType.class);

	public FilterHandler(DiscordBot bot) {
		this.bot = bot;

		reloadFilters();
		reloadFilterLists();

		bot.registerConfigEntry(ALERT_CHANNEL, () -> -1L);
		// TODO: subscribe to config changes

		bot.getActiveHandler().registerReadyHandler(this::onReady);
		bot.getActiveHandler().registerGoneHandler(this::onGone);

		bot.getScheduledExecutor().scheduleWithFixedDelay(this::reloadFilterLists, 0, filterListUpdatePeriodMin, TimeUnit.MINUTES);
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

	public synchronized void reloadFilterLists() {
		try {
			Collection<GlobalFilterListExceptionEntry> globalExceptions = FilterQueries.getGlobalFilterListExceptions(bot.getDatabase());

			Set<String> excluded = new HashSet<>();
			StringBuilder sb = new StringBuilder(1200);
			char[] buf = new char[8192];
			boolean importedAny = false;

			for (FilterListEntry list : FilterQueries.getFilterLists(bot.getDatabase())) {
				int listId = list.id();
				Collection<FilterListExceptionEntry> exceptions = FilterQueries.getFilterListExceptions(bot.getDatabase(), listId);
				excluded.clear();

				for (GlobalFilterListExceptionEntry exception : globalExceptions) {
					if (exception.type() == list.type()) {
						excluded.add(exception.pattern());
					}
				}

				for (FilterListExceptionEntry exception : exceptions) {
					excluded.add(exception.pattern());
				}

				String content;

				try {
					HttpResponse<InputStream> response = HttpUtil.makeRequest(list.url());

					if (response.statusCode() != 200) {
						response.body().close();
						LOGGER.warn("Filter list {} request failed with status: {}", listId, response.statusCode());
						continue;
					}

					sb.setLength(0);

					try (InputStreamReader reader = new InputStreamReader(response.body(), StandardCharsets.UTF_8)) {
						int len;

						while ((len = reader.read(buf)) >= 0) {
							sb.append(buf, 0, len);
						}
					}

					content = sb.toString();
				} catch (IOException | InterruptedException e) {
					LOGGER.warn("Filter list {} download failed: {}", listId, e.toString());
					continue;
				}

				FilterImportResult result = importFilters(content, list.type(), list.group(), excluded);

				if (result.newPatterns() > 0) {
					LOGGER.info("Imported {} / {} new filters for list {}, {} excluded, {} invalid", result.newPatterns(), result.totalPatterns(), listId, result.excludedPatterns(), result.invalidPatterns().size());
					importedAny = true;
				}

				if (!result.invalidPatterns().isEmpty()) {
					List<String> newInvalid = new ArrayList<>(result.invalidPatterns().size());
					Set<String> knownInvalidPatterns = this.knownInvalidPatterns.computeIfAbsent(list.type(), ignore -> new HashSet<>());

					for (String pattern :  result.invalidPatterns()) {
						if (knownInvalidPatterns.add(pattern)) newInvalid.add(pattern);
					}

					if (!newInvalid.isEmpty()) LOGGER.warn("New invalid filter patterns for list {}: {}", list.id(), newInvalid);
				}
			}

			if (importedAny) reloadFilters();
		} catch (Throwable t) {
			LOGGER.warn("Filter list update failed", t);
		}
	}

	public FilterImportResult importFilters(String content, FilterType type, String group, Set<String> excludedPatterns) throws IOException, SQLException {
		List<String> rawPatterns = new ArrayList<>();

		content = content.trim().replace('\'', '"');

		while (content.startsWith("/*")) {
			int end = content.indexOf("*/");
			if (end < 0) throw new IOException("invalid file, non-closed /*");
			content = content.substring(end + 2).trim();
		}

		if (content.startsWith("[")) {
			try (JsonReader reader = new JsonReader(new StringReader(content))) {
				reader.setLenient(true);
				reader.beginArray();

				while (reader.hasNext()) {
					rawPatterns.add(reader.nextString());
				}

				reader.endArray();
			}
		} else {
			for (String pattern : content.split("\\R")) {
				rawPatterns.add(pattern);
			}
		}

		Set<String> patterns = new LinkedHashSet<>(rawPatterns.size());
		List<String> invalid = new ArrayList<>();

		for (String pattern : rawPatterns) {
			try {
				pattern = type.normalizePattern(pattern);

				if (pattern.isBlank()) {
					invalid.add(pattern);
					continue;
				}

				type.compile(pattern); // test-compile to catch errors before storing the pattern
				patterns.add(pattern);
			} catch (Throwable t) {
				LOGGER.debug("Pattern check/compile failed for {} {}: {}", type.id, pattern, t);
				invalid.add(pattern);
				continue;
			}
		}

		int added = 0;
		int excluded = 0;

		for (String pattern : patterns) {
			if (excludedPatterns.contains(pattern)) {
				excluded++;
				continue;
			}

			if (FilterQueries.addFilter(bot.getDatabase(), type, pattern, group) >= 0) {
				added++;
			}
		}

		if (added > 0) reloadFilters();

		return new FilterImportResult(rawPatterns.size(), added, invalid, excluded);
	}

	public record FilterImportResult(int totalPatterns, int newPatterns, Collection<String> invalidPatterns, int excludedPatterns) { }

	private void onReady(Server server, long prevActive) {
		long channelId = bot.getConfigEntry(ALERT_CHANNEL);

		if (channelId >= 0) {
			TextChannel channel = DiscordUtil.getTextChannel(server, channelId);

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

		handleMessage(event.getMessage(), true);
	}

	private void handleMessage(Message message, boolean isEdit) {
		if (!message.getAuthor().isUser()) return;
		if (!DiscordUtil.canDeleteMessages(message.getChannel())) return;
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
		} catch (Throwable t) {
			LOGGER.warn("Filter matching failed", t);
			return;
		}

		if (bestFilter == null) return;

		try {
			bestFilterData.action().apply(message, bestFilter, bestFilterData, this);
		} catch (Throwable t) {
			LOGGER.warn("Filter {} application failed", bestFilter.id(), t);
		}
	}

	private record CompiledFilter(MessageMatcher matcher, FilterEntry filter) { }
}
