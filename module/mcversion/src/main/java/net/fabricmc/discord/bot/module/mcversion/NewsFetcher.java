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

package net.fabricmc.discord.bot.module.mcversion;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoField;
import java.time.temporal.IsoFields;
import java.time.temporal.TemporalAccessor;
import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

import com.google.gson.stream.JsonReader;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import net.fabricmc.discord.bot.DiscordBot;
import net.fabricmc.discord.bot.config.ConfigKey;
import net.fabricmc.discord.bot.config.ValueSerializers;
import net.fabricmc.discord.bot.util.HttpUtil;

final class NewsFetcher {
	private static final String HOST = "www.minecraft.net";
	private static final String PATH = "/content/minecraft-net/_jcr_content.articles.grid";
	private static final String QUERY = "tileselection=auto&tagsPath=minecraft:article/news,minecraft:stockholm/news,minecraft:stockholm/minecraft-build,%d&offset=0&count=4&pageSize=4&locale=en-us&lang=/content/minecraft-net/language-masters/en-us";
	private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("dd MMMM y HH:mm:ss zzz", Locale.ENGLISH);
	private static final Pattern TEXT_SNAPSHOT_PATTERN = Pattern.compile(" (\\d{2})w(\\d{1,2})([a-z]) ");
	private static final Pattern TEXT_RELEASE_PATTERN = Pattern.compile(" 1\\.\\d{2}[ \\.]");
	private static final Pattern SNAPSHOT_PATTERN = Pattern.compile("(\\d{2})w(\\d{1,2})[a-z]");

	private static final String SNAPSHOT_ARTICLE_PATH = "/en-us/article/minecraft-snapshot-%dw%02da";
	private static final Pattern ARTICLE_DATE_PATTERN = Pattern.compile("<meta\\s+property\\s*=\\s*\"article:published_time\"\\s+content\\s*=\\s*\"(.+?)\"");

	private static final ConfigKey<Long> ANNOUNCED_NEWS_DATE = new ConfigKey<>("mcversion.announcedNewsDate", ValueSerializers.LONG);

	private static final Logger LOGGER = LogManager.getLogger("mcversion/news");

	private final McVersionModule mcVersionModule;
	private final Set<String> announcedNews = Collections.newSetFromMap(new ConcurrentHashMap<>());
	private int announcedSnapshotYear; // 2 digit, e.g. 21
	private int announcedSnapshotWeek; // e.g. 18

	NewsFetcher(McVersionModule mcVersionModule) {
		this.mcVersionModule = mcVersionModule;
	}

	void register(DiscordBot bot) {
		bot.registerConfigEntry(ANNOUNCED_NEWS_DATE, () -> System.currentTimeMillis());
	}

	void init(String announcedSnapshotVersion) {
		Matcher matcher = SNAPSHOT_PATTERN.matcher(announcedSnapshotVersion);
		SnapshotVersion version = matcher.find() ? SnapshotVersion.get(matcher) : SnapshotVersion.getCurrent();
		setAnnouncedSnapshot(version);
	}

	void update() throws IOException, URISyntaxException, InterruptedException {
		updateNewsByQuery();

		SnapshotVersion version = SnapshotVersion.getCurrent();

		if (!hasAnnouncedSnapshot(version) && version.daysDue() <= 0) {
			updateNewsByArticlePoll(version);
		}
	}

	private boolean hasAnnouncedSnapshot(SnapshotVersion version) {
		return version.year() < announcedSnapshotYear || version.year() == announcedSnapshotYear && version.week() <= announcedSnapshotWeek;
	}

	private void setAnnouncedSnapshot(SnapshotVersion version) {
		announcedSnapshotYear = version.year();
		announcedSnapshotWeek = version.week();
	}

	private void updateNewsByQuery() throws IOException, URISyntaxException, InterruptedException {
		long announcedNewsDate = mcVersionModule.getBot().getConfigEntry(ANNOUNCED_NEWS_DATE);

		HttpResponse<InputStream> response = requestNews(HttpUtil.toUri(HOST, PATH, QUERY.formatted(ThreadLocalRandom.current().nextInt(0x40000000))), true);

		if (response.statusCode() != 200) {
			LOGGER.warn("Query request failed: {}", response.statusCode());
			response.body().close();
			return;
		}

		long firstDateMs = 0;

		try (JsonReader reader = new JsonReader(new InputStreamReader(getNewsIs(response), StandardCharsets.UTF_8))) {
			reader.beginObject();

			readLoop: while (reader.hasNext()) {
				if (!reader.nextName().equals("article_grid")) {
					reader.skipValue();
				}

				reader.beginArray();

				while (reader.hasNext()) {
					reader.beginObject();

					String title = null;
					String subTitle = "";
					String path = null;
					Instant date = null;

					while (reader.hasNext()) {
						switch (reader.nextName()) {
						case "default_tile" -> {
							reader.beginObject();

							while (reader.hasNext()) {
								switch (reader.nextName()) {
								case "title" -> title = reader.nextString();
								case "sub_header" -> subTitle = reader.nextString();
								default -> reader.skipValue();
								}
							}

							reader.endObject();
						}
						case "article_url" -> path = reader.nextString();
						case "publish_date" -> date = Instant.from(DATE_FORMATTER.parse(reader.nextString()));
						default -> reader.skipValue();
						}
					}

					reader.endObject();

					if (title == null || path == null || date == null) {
						LOGGER.warn("Missing title/path/date in article listing");
						continue;
					}

					long dateMs = date.toEpochMilli();
					if (dateMs <= announcedNewsDate) break readLoop;
					if (firstDateMs == 0) firstDateMs = dateMs;

					String content = String.format(" %s %s %s ", title, subTitle, path).toLowerCase(Locale.ENGLISH);
					Matcher snapshotMatcher = null;

					if ((content.contains("snapshot") && (snapshotMatcher = TEXT_SNAPSHOT_PATTERN.matcher(content)).find()
							|| content.contains("java") && TEXT_RELEASE_PATTERN.matcher(content).find())
							&& !content.contains("bedrock")
							&& !announcedNews.contains(path)) {
						SnapshotVersion version = snapshotMatcher != null ? SnapshotVersion.get(snapshotMatcher) : null;

						if (version == null
								|| !hasAnnouncedSnapshot(version) && !McVersionModule.isOldVersion(version.toString())) {
							LOGGER.info("Announcing {} (regular, version {})", path, version != null ? version.toString() : "(unknown)");

							if (!McVersionModule.sendAnnouncement(mcVersionModule.getUpdateChannel(), "https://"+HOST+path)) {
								return; // avoid updating ANNOUNCED_NEWS_DATE
							}
						}

						announcedNews.add(path);

						if (version != null) {
							setAnnouncedSnapshot(version);
						}
					}
				}
			}
		}

		if (firstDateMs > announcedNewsDate) mcVersionModule.getBot().setConfigEntry(ANNOUNCED_NEWS_DATE, firstDateMs);
	}

	private void updateNewsByArticlePoll(SnapshotVersion version) throws IOException, URISyntaxException, InterruptedException {
		String path = SNAPSHOT_ARTICLE_PATH.formatted(version.year(), version.week());
		HttpResponse<InputStream> response = requestNews(HttpUtil.toUri(HOST, path), false);

		if (response.statusCode() != 200) {
			if (response.statusCode() != 404) LOGGER.warn("Poll request failed: {}", response.statusCode());
			response.body().close();
			return;
		}

		Instant date = null;

		try (BufferedReader reader = new BufferedReader(new InputStreamReader(getNewsIs(response), StandardCharsets.UTF_8))) {
			String line;

			while ((line = reader.readLine()) != null) {
				Matcher matcher = ARTICLE_DATE_PATTERN.matcher(line);

				if (matcher.find()) {
					date = Instant.from(DateTimeFormatter.ISO_INSTANT.parse(matcher.group(1)));
					break;
				}
			}
		}

		if (date == null) throw new IOException("no parseable date");

		long dateMs = date.toEpochMilli();
		long announcedNewsDate = mcVersionModule.getBot().getConfigEntry(ANNOUNCED_NEWS_DATE);

		if (dateMs > announcedNewsDate
				&& !announcedNews.contains(path)
				&& !McVersionModule.isOldVersion(version.toString())) {
			LOGGER.info("Announcing {} (url poll, version {})", path, version);

			if (!McVersionModule.sendAnnouncement(mcVersionModule.getUpdateChannel(), "https://"+HOST+path)) {
				return;
			}
		}

		announcedNews.add(path);
		setAnnouncedSnapshot(version);
		mcVersionModule.getBot().setConfigEntry(ANNOUNCED_NEWS_DATE, dateMs + 20_000); // add 20s in case the time stamp isn't accurate
	}

	private static HttpResponse<InputStream> requestNews(URI uri, boolean json) throws IOException, InterruptedException {
		return HttpUtil.makeRequest(uri, Map.of("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.114 Safari/537.36",
				"Accept", (json ? "application/json" : "text/html"),
				"Accept-Language", "en-US",
				"Accept-Encoding", "gzip"));
	}

	private static InputStream getNewsIs(HttpResponse<InputStream> response) throws IOException {
		InputStream ret = response.body();

		if (response.headers().firstValue("Content-Encoding").orElse("").equals("gzip")) {
			ret = new GZIPInputStream(ret);
		}

		return ret;
	}

	private record SnapshotVersion(int year, int week, int daysDue) {
		public static SnapshotVersion getCurrent() {
			TemporalAccessor now = ZonedDateTime.now(ZoneOffset.UTC);

			return new SnapshotVersion(now.get(ChronoField.YEAR) % 100, now.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR), 3 - now.get(ChronoField.DAY_OF_WEEK));
		}

		public static SnapshotVersion get(Matcher matcher) {
			return new SnapshotVersion(Integer.parseUnsignedInt(matcher.group(1)), Integer.parseUnsignedInt(matcher.group(2)), 0);
		}

		@Override
		public String toString() {
			return "%dw%02da".formatted(year, week);
		}
	}
}
