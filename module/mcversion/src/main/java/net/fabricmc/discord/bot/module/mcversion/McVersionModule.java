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
import java.nio.file.Path;
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
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

import com.google.gson.stream.JsonReader;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.javacord.api.DiscordApi;
import org.javacord.api.entity.channel.TextChannel;
import org.javacord.api.entity.message.Message;
import org.javacord.api.entity.server.Server;

import net.fabricmc.discord.bot.DiscordBot;
import net.fabricmc.discord.bot.Module;
import net.fabricmc.discord.bot.config.ConfigKey;
import net.fabricmc.discord.bot.config.ValueSerializers;
import net.fabricmc.discord.bot.util.HttpUtil;

/**
 * Checks for and announces new MC versions
 *
 * This works as follows:
 * <ol>
 * <li>grab latest MC version from the launcher metadata API
 * <li>require that the version is not the same as in the bot DB
 * <li>require that Fabric Meta's intermediary records don't already list the version, indicating a bogus detection
 * <li>require that the version hasn't been announced by the bot's current process
 * <li>announce the version to the configured channel
 * <li>publish that announcement if possible
 * <li>record new version to avoid repeats
 * </ol>
 */
public final class McVersionModule implements Module {
	private static final int UPDATE_DELAY = 30; // in s
	private static final int NEWS_UPDATE_CYCLES = 4; // check news every n updates
	private static final String MC_VERSION_HOST = "launchermeta.mojang.com";
	private static final String MC_VERSION_PATH = "/mc/game/version_manifest_v2.json";

	private static final String MC_NEWS_HOST = "www.minecraft.net";
	private static final String MC_NEWS_PATH = "/content/minecraft-net/_jcr_content.articles.grid";
	private static final String MC_NEWS_QUERY = "tileselection=auto&tagsPath=minecraft:article/news,minecraft:stockholm/news,minecraft:stockholm/minecraft-build,%d&offset=0&count=4&pageSize=4&locale=en-us&lang=/content/minecraft-net/language-masters/en-us";
	private static final DateTimeFormatter MC_NEWS_DATE_FORMATTER = DateTimeFormatter.ofPattern("dd MMMM y HH:mm:ss zzz", Locale.ENGLISH);
	private static final Pattern MC_NEWS_SNAPSHOT_PATTERN = Pattern.compile(" (\\d{2})w(\\d{1,2})([a-z]) ");
	private static final Pattern MC_NEWS_RELEASE_PATTERN = Pattern.compile(" 1\\.\\d{2}[ \\.]");

	private static final String MC_NEWS_ARTICLE_PATH = "/en-us/article/minecraft-snapshot-%dw%02da";
	private static final Pattern MC_NEWS_ARTICLE_DATE_PATTERN = Pattern.compile("<meta\\s+property\\s*=\\s*\"article:published_time\"\\s+content\\s*=\\s*\"(.+?)\"");
	private static final Pattern SNAPSHOT_PATTERN = Pattern.compile("(\\d{2})w(\\d{1,2})[a-z]");

	private static final String FABRIC_VERSION_HOST = "meta.fabricmc.net";
	private static final String FABRIC_VERSION_PATH = "/v2/versions/intermediary/%s";

	private static final String NESSAGE = "A new Minecraft %s is out: %s"; // args: kind, version
	private static final String KIND_RELEASE = "release";
	private static final String KIND_SNAPSHOT = "snapshot";

	private static final Logger LOGGER = LogManager.getLogger(McVersionModule.class);

	// global properties
	private static final ConfigKey<Long> ANNOUNCE_CHANNEL = new ConfigKey<>("mcversion.announceChannel", ValueSerializers.LONG);
	private static final ConfigKey<Long> UPDATE_CHANNEL = new ConfigKey<>("mcversion.updateChannel", ValueSerializers.LONG);
	private static final ConfigKey<String> ANNOUNCED_RELEASE_VERSION = new ConfigKey<>("mcversion.announcedReleaseVersion", ValueSerializers.STRING);
	private static final ConfigKey<String> ANNOUNCED_SNAPSHOT_VERSION = new ConfigKey<>("mcversion.announcedSnapshotVersion", ValueSerializers.STRING);
	private static final ConfigKey<Long> ANNOUNCED_NEWS_DATE = new ConfigKey<>("mcversion.announcedNewsDate", ValueSerializers.LONG);

	// user properties
	static final ConfigKey<String> DEFAULT_VERSION = new ConfigKey<>("mcVersion.defaultVersion", ValueSerializers.STRING);

	private DiscordBot bot;
	private McVersionRepo repo;
	private Future<?> future;
	private volatile TextChannel announceChannel;
	private volatile TextChannel updateChannel;
	private int newsCycleCouter;
	private final Set<String> announcedVersions = Collections.newSetFromMap(new ConcurrentHashMap<>());
	private final Set<String> announcedNews = Collections.newSetFromMap(new ConcurrentHashMap<>());
	private int announcedSnapshotYear; // 2 digit, e.g. 21
	private int announcedSnapshotWeek; // e.g. 18

	@Override
	public String getName() {
		return "mcversion";
	}

	@Override
	public void registerConfigEntries(DiscordBot bot) {
		bot.registerConfigEntry(ANNOUNCE_CHANNEL, () -> -1L);
		bot.registerConfigEntry(UPDATE_CHANNEL, () -> -1L);
		// TODO: subscribe to config changes

		bot.registerConfigEntry(ANNOUNCED_RELEASE_VERSION, () -> "0");
		bot.registerConfigEntry(ANNOUNCED_SNAPSHOT_VERSION, () -> "0");
		bot.registerConfigEntry(ANNOUNCED_NEWS_DATE, () -> System.currentTimeMillis());
	}

	@Override
	public void setup(DiscordBot bot, DiscordApi api, Logger logger, Path dataDir) {
		this.bot = bot;
		this.repo = new McVersionRepo(bot);

		bot.getActiveHandler().registerReadyHandler(this::onReady);
		bot.getActiveHandler().registerGoneHandler(this::onGone);

		bot.registerCommand(new SetMcVersionCommand(repo));
	}

	McVersionRepo getRepo() {
		return repo;
	}

	private void onReady(Server server, long prevActive) {
		long announceChannelId = bot.getConfigEntry(ANNOUNCE_CHANNEL);

		if (announceChannelId > 0) {
			TextChannel channel = server.getTextChannelById(announceChannelId).orElse(null);

			if (channel == null) {
				LOGGER.warn("invalid announce channel: {}", announceChannelId);
				return;
			}

			this.announceChannel = channel;
		}

		long updateChannelId = bot.getConfigEntry(UPDATE_CHANNEL);

		if (updateChannelId > 0) {
			TextChannel channel = server.getTextChannelById(updateChannelId).orElse(null);

			if (channel == null) {
				LOGGER.warn("invalid update channel: {}", updateChannelId);
				return;
			}

			this.updateChannel = channel;
		}

		Matcher matcher = SNAPSHOT_PATTERN.matcher(bot.getConfigEntry(ANNOUNCED_SNAPSHOT_VERSION));
		SnapshotVersion version = matcher.find() ? SnapshotVersion.get(matcher) : SnapshotVersion.getCurrent();
		setAnnouncedSnapshot(version);

		if (announceChannel != null || updateChannel != null) {
			future = bot.getScheduledExecutor().scheduleWithFixedDelay(this::update, 0, UPDATE_DELAY, TimeUnit.SECONDS);
		}
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

	private boolean hasAnnouncedSnapshot(SnapshotVersion version) {
		return version.year < announcedSnapshotYear || version.year == announcedSnapshotYear && version.week <= announcedSnapshotWeek;
	}

	private void setAnnouncedSnapshot(SnapshotVersion version) {
		announcedSnapshotYear = version.year;
		announcedSnapshotWeek = version.week;
	}

	private void onGone(Server server) {
		announceChannel = null;
		updateChannel = null;

		if (future != null) {
			future.cancel(false);
			future = null;
		}
	}

	private void update() {
		try {
			if (announceChannel != null || updateChannel != null) {
				updateLauncherMeta();
			}

			if (updateChannel != null
					&& newsCycleCouter++ % NEWS_UPDATE_CYCLES == 0) {
				updateNewsByQuery();

				SnapshotVersion version = SnapshotVersion.getCurrent();

				if (!hasAnnouncedSnapshot(version) && version.daysDue <= 0) {
					updateNewsByArticlePoll(version);
				}
			}
		} catch (Throwable t) {
			HttpUtil.logError("mc version check failed", t, LOGGER);
		}
	}

	private void updateLauncherMeta() throws IOException, URISyntaxException, InterruptedException {
		HttpResponse<InputStream> response = HttpUtil.makeRequest(HttpUtil.toUri(MC_VERSION_HOST, MC_VERSION_PATH));

		if (response.statusCode() != 200) {
			LOGGER.warn("MC Launcher Meta query request failed: {}", response.statusCode());
			return;
		}

		String latestRelease = null;
		String latestSnapshot = null;

		try (JsonReader reader = new JsonReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
			reader.beginObject();

			while (reader.hasNext()) {
				if (!reader.nextName().equals("latest")) {
					reader.skipValue();
					continue;
				}

				reader.beginObject();

				while (reader.hasNext()) {
					switch (reader.nextName()) {
					case "release" -> latestRelease = reader.nextString();
					case "snapshot" -> latestSnapshot = reader.nextString();
					default -> reader.skipValue();
					}
				}

				break;
			}
		}

		updateSpecific(KIND_RELEASE, latestRelease, ANNOUNCED_RELEASE_VERSION, null);
		updateSpecific(KIND_SNAPSHOT, latestSnapshot, ANNOUNCED_SNAPSHOT_VERSION, ANNOUNCED_RELEASE_VERSION);
	}

	private void updateSpecific(String kind, String latestVersion, ConfigKey<String> announcedVersionKey, ConfigKey<String> altAnnouncedVersionKey) throws IOException, URISyntaxException, InterruptedException {
		String oldVersion;

		if (latestVersion == null // no version specified
				|| latestVersion.equals(oldVersion = bot.getConfigEntry(announcedVersionKey)) // same as last announced
				|| isOldVersion(latestVersion) // already known to Fabric, mcmeta glitch
				|| announcedVersions.contains(latestVersion)) { // already posted by this instance
			return;
		}

		// suppress announcing release as snapshot
		if (altAnnouncedVersionKey != null && latestVersion.equals(bot.getConfigEntry(altAnnouncedVersionKey))) {
			bot.setConfigEntry(announcedVersionKey, latestVersion);
			return;
		}

		LOGGER.info("Announcing MC {} {} -> {}", kind, oldVersion, latestVersion);

		String msgText = NESSAGE.formatted(kind, latestVersion);

		if (sendAnnouncement(announceChannel, msgText)
				| sendAnnouncement(updateChannel, msgText)) { // deliberate | to force eval both
			announcedVersions.add(latestVersion);
			bot.setConfigEntry(announcedVersionKey, latestVersion);
		}
	}

	private void updateNewsByQuery() throws IOException, URISyntaxException, InterruptedException {
		long announcedNewsDate = bot.getConfigEntry(ANNOUNCED_NEWS_DATE);

		HttpResponse<InputStream> response = requestNews(HttpUtil.toUri(MC_NEWS_HOST, MC_NEWS_PATH, MC_NEWS_QUERY.formatted(ThreadLocalRandom.current().nextInt(0x40000000))), true);

		if (response.statusCode() != 200) {
			LOGGER.warn("MC news query request failed: {}", response.statusCode());
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
						case "publish_date" -> date = Instant.from(MC_NEWS_DATE_FORMATTER.parse(reader.nextString()));
						default -> reader.skipValue();
						}
					}

					reader.endObject();

					if (title == null || path == null || date == null) {
						LOGGER.warn("Missing title/path/date in mc news output");
						continue;
					}

					long dateMs = date.toEpochMilli();
					if (dateMs <= announcedNewsDate) break readLoop;
					if (firstDateMs == 0) firstDateMs = dateMs;

					String content = String.format(" %s %s %s ", title, subTitle, path).toLowerCase(Locale.ENGLISH);
					Matcher snapshotMatcher = null;

					if ((content.contains("snapshot") && (snapshotMatcher = MC_NEWS_SNAPSHOT_PATTERN.matcher(content)).find()
							|| content.contains("java") && MC_NEWS_RELEASE_PATTERN.matcher(content).find())
							&& !content.contains("bedrock")
							&& !announcedNews.contains(path)) {
						SnapshotVersion version = snapshotMatcher != null ? SnapshotVersion.get(snapshotMatcher) : null;

						if (version == null
								|| !hasAnnouncedSnapshot(version) && !isOldVersion(version.toString())) {
							LOGGER.info("Announcing MC news {} (regular, version {})", path, version != null ? version.toString() : "(unknown)");

							if (!sendAnnouncement(updateChannel, "https://"+MC_NEWS_HOST+path)) {
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

		if (firstDateMs > announcedNewsDate) bot.setConfigEntry(ANNOUNCED_NEWS_DATE, firstDateMs);
	}

	private void updateNewsByArticlePoll(SnapshotVersion version) throws IOException, URISyntaxException, InterruptedException {
		String path = MC_NEWS_ARTICLE_PATH.formatted(version.year, version.week);
		HttpResponse<InputStream> response = requestNews(HttpUtil.toUri(MC_NEWS_HOST, path), false);

		if (response.statusCode() != 200) {
			if (response.statusCode() != 404) LOGGER.warn("MC news poll request failed: {}", response.statusCode());
			return;
		}

		Instant date = null;

		try (BufferedReader reader = new BufferedReader(new InputStreamReader(getNewsIs(response), StandardCharsets.UTF_8))) {
			String line;

			while ((line = reader.readLine()) != null) {
				Matcher matcher = MC_NEWS_ARTICLE_DATE_PATTERN.matcher(line);

				if (matcher.find()) {
					date = Instant.from(DateTimeFormatter.ISO_INSTANT.parse(matcher.group(1)));
					break;
				}
			}
		}

		if (date == null) throw new IOException("no parseable date");

		long dateMs = date.toEpochMilli();
		long announcedNewsDate = bot.getConfigEntry(ANNOUNCED_NEWS_DATE);

		if (dateMs > announcedNewsDate
				&& !announcedNews.contains(path)
				&& !isOldVersion(version.toString())) {
			LOGGER.info("Announcing MC news {} (url poll, version {})", path, version);

			if (!sendAnnouncement(updateChannel, "https://"+MC_NEWS_HOST+path)) {
				return;
			}
		}

		announcedNews.add(path);
		setAnnouncedSnapshot(version);
		bot.setConfigEntry(ANNOUNCED_NEWS_DATE, dateMs + 20_000); // add 20s in case the time stamp isn't accurate
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

	private static boolean isOldVersion(String version) throws IOException, URISyntaxException, InterruptedException {
		if (version.indexOf('/') >= 0)  throw new IllegalArgumentException("invalid mc version: "+version);

		HttpResponse<InputStream> response = HttpUtil.makeRequest(HttpUtil.toUri(FABRIC_VERSION_HOST, FABRIC_VERSION_PATH.formatted(version)));

		if (response.statusCode() != 200) {
			LOGGER.warn("MC version verification request against Fabric Meta failed: {}", response.statusCode());
			return false;
		}

		try (JsonReader reader = new JsonReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
			reader.beginArray();
			return reader.hasNext();
		}
	}

	private static boolean sendAnnouncement(TextChannel channel, String msg) {
		if (channel == null) return false;

		Message message;

		try {
			message = channel.sendMessage(msg).join();
		} catch (Throwable t) {
			LOGGER.warn("Announcement failed", t);
			return false;
		}

		//if (channel.getType() == ChannelType.SERVER_NEWS_CHANNEL) { TODO: check once javacord exposes this properly
		message.crossPost()
		.exceptionally(exc -> {
			LOGGER.warn("Message crossposting failed: "+exc); // fails with MissingPermissionsException for non-news channel
			return null;
		});
		//}

		return true;
	}
}
