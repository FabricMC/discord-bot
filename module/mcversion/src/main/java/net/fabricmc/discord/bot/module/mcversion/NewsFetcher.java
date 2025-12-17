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

package net.fabricmc.discord.bot.module.mcversion;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
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
	private static final String PATH = "/content/minecraftnet/language-masters/en-us/jcr:content/root/container/image_grid_a_copy_64.articles.page-1.json";
	private static final String QUERY = "cache=%d";
	private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("dd MMMM y HH:mm:ss zzz", Locale.ENGLISH);
	private static final Pattern TEXT_SNAPSHOT_PATTERN = Pattern.compile(" (\\d{2}).(\\d+)(?:.(\\d+))?(?: (snapshot|pre-?release|release candidate) (\\d+))? ");
	private static final Pattern SNAPSHOT_PATTERN = Pattern.compile("(\\d{2}).(\\d+)(?:.(\\d+))?(?:-(snapshot|pre|rc).(\\d+))?");

	private static final ConfigKey<Long> ANNOUNCED_NEWS_DATE = new ConfigKey<>("mcversion.announcedNewsDate", ValueSerializers.LONG);

	private static final Logger LOGGER = LogManager.getLogger("mcversion/news");

	private final McVersionModule mcVersionModule;
	private final Set<String> announcedNews = Collections.newSetFromMap(new ConcurrentHashMap<>());
	private Version announced;

	NewsFetcher(McVersionModule mcVersionModule) {
		this.mcVersionModule = mcVersionModule;
	}

	void register(DiscordBot bot) {
		bot.registerConfigEntry(ANNOUNCED_NEWS_DATE, () -> System.currentTimeMillis());
	}

	void init(String announcedSnapshotVersion) {
		Matcher matcher = SNAPSHOT_PATTERN.matcher(announcedSnapshotVersion);
		Version version;

		if (matcher.find()) {
			version = Version.get(matcher);
		} else {
			LOGGER.warn("Unable to parse version {}", announcedSnapshotVersion);
			version = Version.DEFAULT;
		}

		setAnnouncedSnapshot(version);
	}

	void update() throws IOException, URISyntaxException, InterruptedException {
		updateNewsByQuery();
	}

	private boolean hasAnnouncedSnapshot(Version version) {
		return announced.compareTo(version) >= 0;
	}

	private void setAnnouncedSnapshot(Version version) {
		announced = version;
	}

	private void updateNewsByQuery() throws IOException, URISyntaxException, InterruptedException {
		HttpResponse<InputStream> response = requestNews(HttpUtil.toUri(HOST, PATH, QUERY.formatted(ThreadLocalRandom.current().nextInt(0x40000000))), true);

		if (response.statusCode() != 200) {
			LOGGER.warn("Query request failed: {}", response.statusCode());
			response.body().close();
			return;
		}

		try (JsonReader reader = new JsonReader(new InputStreamReader(getNewsIs(response), StandardCharsets.UTF_8))) {
			reader.beginObject();

			while (reader.hasNext()) {
				if (!reader.nextName().equals("article_grid")) {
					reader.skipValue();
				}

				reader.beginArray();

				while (reader.hasNext()) {
					reader.beginObject();

					String title = null;
					String subTitle = "";
					String path = null;

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
						default -> reader.skipValue();
						}
					}

					reader.endObject();

					if (title == null || path == null) {
						LOGGER.warn("Missing title/path/date in article listing");
						continue;
					}

					checkArticle(title, subTitle, path);
				}
			}
		}
	}

	private void checkArticle(String title, String subTitle, String path) throws IOException, URISyntaxException, InterruptedException {
		long dateMs = Instant.now().toEpochMilli();
		long announcedNewsDate = mcVersionModule.getBot().getConfigEntry(ANNOUNCED_NEWS_DATE);
		if (dateMs <= announcedNewsDate) return;

		String content = String.format(" %s %s %s ", title, subTitle, path).toLowerCase(Locale.ENGLISH);
		Matcher matcher = null;

		if (content.contains("java") && (matcher = TEXT_SNAPSHOT_PATTERN.matcher(content)).find()
				&& !announcedNews.contains(path)) {
			Version version = Version.get(matcher);

			if (!hasAnnouncedSnapshot(version) && !McVersionModule.isOldVersion(version.toString())) {
				LOGGER.info("Announcing MC-News {} (regular, version {})", path, version.toString());

				if (!mcVersionModule.sendAnnouncement(mcVersionModule.getUpdateChannel(), "https://"+HOST+path)) {
					return; // skip ANNOUNCED_NEWS_DATE record update
				}
			}

			announcedNews.add(path);
			setAnnouncedSnapshot(version);
			mcVersionModule.getBot().setConfigEntry(ANNOUNCED_NEWS_DATE, dateMs);
		}
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

	private record NewsData(String title, String subTitle, String path, Instant date) { }

	private enum VersionType {
		SNAPSHOT(1, "snapshot"),
		PRE_RELEASE(3, "pre"),
		RELEASE_CANDIDATE(5, "rc"),
		FULL_RELEASE(7, "");

		public final int index;
		public final String name;

		VersionType(int index, String name) {
			this.index = index;
			this.name = name;
		}

		static VersionType fromName(String name) {
			switch (name.toLowerCase(Locale.ROOT)) {
				case "snapshot": return SNAPSHOT;
				case "pre":
				case "pre-release":
				case "prerelease": return PRE_RELEASE;
				case "rc":
				case "release candidate": return RELEASE_CANDIDATE;
				default: return FULL_RELEASE;
			}
		}
	}

	private record Version(int year, int drop, int hotfix, VersionType type, int build) implements Comparable<Version> {
		public static final Version DEFAULT = new Version(26, 1, 0, VersionType.SNAPSHOT, 1);

		public static Version get(Matcher matcher) {
			int yearN = Integer.parseUnsignedInt(matcher.group(1));
			int dropN = Integer.parseUnsignedInt(matcher.group(2));
			String hotfix = matcher.group(3);
			int hotfixN = hotfix == null ? 0 : Integer.parseUnsignedInt(hotfix);
			if (matcher.group(4) == null) {
				return new Version(yearN, dropN, hotfixN, VersionType.FULL_RELEASE, 0);
			}
			return new Version(yearN, dropN, hotfixN, VersionType.fromName(matcher.group(4)), Integer.parseUnsignedInt(matcher.group(5)));
		}

		@Override
		public String toString() {
			if (this.hotfix == 0) {
				if (this.type == VersionType.FULL_RELEASE) {
					return "%d.%d".formatted(this.year, this.drop);
				} else {
					return "%d.%d-%s-%d".formatted(this.year, this.drop, this.type.name, this.build);
				}
			} else {
				if (this.type == VersionType.FULL_RELEASE) {
					return "%d.%d.%d".formatted(this.year, this.drop, this.hotfix);
				} else {
					return "%d.%d.%d-%s-%d".formatted(this.year, this.drop, this.hotfix, this.type.name, this.build);
				}
			}
		}

		@Override
		public int compareTo(NewsFetcher.Version o) {
			if (this.year != o.year) {
				return this.year - o.year;
			} else if (this.drop != o.drop) {
				return this.drop - o.drop;
			} else if (this.type != o.type) {
				return this.type.compareTo(o.type);
			} else {
				return this.build - o.build;
			}
		}
	}
}
