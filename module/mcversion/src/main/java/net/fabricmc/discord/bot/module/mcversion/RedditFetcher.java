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

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import net.fabricmc.discord.bot.DiscordBot;
import net.fabricmc.discord.bot.config.ConfigKey;
import net.fabricmc.discord.bot.config.ValueSerializers;
import net.fabricmc.discord.bot.util.HttpUtil;

// https://www.reddit.com/dev/api
final class RedditFetcher {
	private static final int PAGE_SIZE = 100; // reddit api limit is 100
	private static final int MAX_PAGES = 5;

	private static final String HOST = "www.reddit.com";
	private static final String PATH = "/r/Minecraft/new.json";
	private static final String QUERY_START = "limit="+PAGE_SIZE+"&raw_json=1";
	private static final String QUERY_CONT = "limit="+PAGE_SIZE+"&raw_json=1&after=%s";
	private static final Pattern ARTICLE_PATTERN = Pattern.compile("\\((https://www.minecraft.net/(?:en-us/)?article/.+?)\\)");
	private static final Pattern DOWNLOAD_PATTERN = Pattern.compile("\\((https://launcher.mojang.com/.+?\\.zip)\\)");

	private static final Logger LOGGER = LogManager.getLogger("mcversion/reddit");

	private static final ConfigKey<String> LAST_POST = new ConfigKey<>("mcversion.lastRedditPost", ValueSerializers.STRING);

	private final McVersionModule mcVersionModule;
	private String latestRelease;
	private String latestSnapshot;
	private String latestPending;

	RedditFetcher(McVersionModule mcVersionModule) {
		this.mcVersionModule = mcVersionModule;
	}

	void register(DiscordBot bot) {
		bot.registerConfigEntry(LAST_POST, () -> "");
	}

	String getLatestRelease() {
		return latestRelease;
	}

	String getLatestSnapshot() {
		return latestSnapshot;
	}

	String getLatestPending() {
		return latestPending;
	}

	void update() throws IOException, URISyntaxException, InterruptedException {
		String initialRelease = latestRelease;
		String initialSnapshot = latestSnapshot;
		String initialPending = latestPending;

		String lastPostId = mcVersionModule.getBot().getConfigEntry(LAST_POST);
		String nextLastPostId = null;
		String after = null;

		pageLoop: for (int i = 0; i < MAX_PAGES; i++) {
			HttpResponse<InputStream> response = HttpUtil.makeRequest(HttpUtil.toUri(HOST, PATH, after == null ? QUERY_START : QUERY_CONT.formatted(after)));

			if (response.statusCode() != 200) {
				LOGGER.warn("Request failed: {}", response.statusCode());
				response.body().close();
				return;
			}

			after = null;

			try (JsonReader reader = new JsonReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
				reader.beginObject();

				while (reader.hasNext()) {
					if (!reader.nextName().equals("data")) {
						reader.skipValue();
						continue;
					}

					reader.beginObject();

					while (reader.hasNext()) {
						switch (reader.nextName()) {
						case "after":
							after = reader.nextString();
							break;
						case "children":
							reader.beginArray();
							if (!reader.hasNext()) break pageLoop;

							do {
								String id = processPost(reader, lastPostId, initialRelease, initialSnapshot, initialPending);
								if (id == null) break pageLoop; // at lastPostId
								if (nextLastPostId == null) nextLastPostId = id;
							} while (reader.hasNext());

							reader.endArray();
							break;
						default:
							reader.skipValue();
						}
					}
				}
			}

			if (after == null) break;
		}

		if (nextLastPostId != null) mcVersionModule.getBot().setConfigEntry(LAST_POST, nextLastPostId);
	}

	private String processPost(JsonReader reader, String endId,
			String initialRelease, String initialSnapshot, String initialPending) throws IOException {
		Result result = parsePost(reader, endId);
		if (result == null) return null; // at endId

		if (result.versionId() != null) {
			if (McVersionModule.KIND_RELEASE.equals(result.type()) && latestRelease == initialRelease) {
				latestRelease = result.versionId();
			} else if (McVersionModule.KIND_SNAPSHOT.equals(result.type()) && latestSnapshot == initialSnapshot) {
				latestSnapshot = result.versionId();
			} else if (McVersionModule.KIND_PENDING.equals(result.type()) && latestPending == initialPending) {
				latestPending = result.versionId();
			} else {
				LOGGER.warn("Unknown release type for {}: {}", result.versionId(), result.type());
			}
		}

		if (result.article() != null) {
			try {
				mcVersionModule.newsFetcher.updateNewsByArticlePoll(result.article());
			} catch (Throwable t) {
				LOGGER.warn("Error updating news article from reddit post {} ", result.postId(), t);
			}
		}

		return result.postId();
	}

	private Result parsePost(JsonReader reader, String ignoredId) throws IOException {
		String id = null;
		String author = null;
		boolean hasMojangFlair = false;
		String title = null;
		String selftext = null;

		reader.beginObject();

		while (reader.hasNext()) {
			if (!reader.nextName().equals("data")) {
				reader.skipValue();
				continue;
			}

			reader.beginObject();

			while (reader.hasNext()) {
				switch (reader.nextName()) {
				case "name":
					id = reader.nextString();
					if (id.equals(ignoredId)) return null; // ignored
					break;
				case "selftext":
					selftext = reader.nextString();
					break;
				case "title":
					title = reader.nextString();
					break;
				case "author_flair_text":
				case "author_flair_css_class":
					if (reader.peek() == JsonToken.STRING) {
						if (reader.nextString().toLowerCase(Locale.ENGLISH).contains("mojang")) {
							hasMojangFlair = true;
						}
					} else {
						reader.skipValue();
					}

					break;
				case "author":
					author = reader.nextString();
					break;
				default:
					reader.skipValue();
				}
			}

			reader.endObject();
		}

		reader.endObject();

		if (!hasMojangFlair ||
				title.toLowerCase(Locale.ENGLISH).contains("bedrock")) {
			return new Result(id, null, null, null, null);
		}

		Matcher matcher = ARTICLE_PATTERN.matcher(selftext);
		String article = null;

		while (matcher.find()) {
			if (article == null) {
				article = matcher.group(1);
			} else {
				LOGGER.debug("Skipping {} by {} with multiple articles", id, author);
				return new Result(id, null, null, null, null);
			}
		}

		matcher = DOWNLOAD_PATTERN.matcher(selftext);
		Result result = null;

		while (matcher.find()) {
			String link = matcher.group(1);

			try {
				HttpResponse<InputStream> response = HttpUtil.makeRequest(new URI(link));

				if (response.statusCode() != 200) {
					LOGGER.info("Link {} in {} by {} request failed: {}", link, id, author, response.statusCode());
					response.body().close();
					continue;
				}

				try (ZipInputStream zis = new ZipInputStream(response.body())) {
					ZipEntry entry;

					while ((entry = zis.getNextEntry()) != null) {
						if (!entry.isDirectory() && entry.getName().endsWith(".json")) {
							try {
								Result partialResult = processJson(zis);

								if (partialResult == null) {
									LOGGER.debug("JSON {}:{} in {} by {} doesn't appear to be a MC version JSON", link, entry.getName(), id, author);
								} else if (result != null) {
									LOGGER.debug("Skipping {} by {} with multiple version jsons", id, author);
									return new Result(id, null, null, null, null);
								} else {
									result = new Result(id, article, partialResult.versionId(), partialResult.type(), link);
								}
							} catch (IOException e) {
								LOGGER.info("Json {}:{} in {} by {} processing failed: {}", link, entry.getName(), id, author, e);
							}
						}
					}
				}
			} catch (IOException | InterruptedException | URISyntaxException e) {
				LOGGER.info("Link {} in {} by {} processing failed: {}", link, id, author, e);
			}
		}

		if (result == null) result = new Result(id, article, null, null, null);

		return result;
	}

	private Result processJson(InputStream is) throws IOException {
		String id = null;
		String type = null; // pending, snapshot, release
		Set<String> missingKeys = new HashSet<>(Arrays.asList("downloads", "libraries", "mainClass", "releaseTime"));

		@SuppressWarnings("resource") // zis needs to be kept open
		JsonReader reader = new JsonReader(new InputStreamReader(is, StandardCharsets.UTF_8));
		reader.beginObject();

		while (reader.hasNext()) {
			String name = reader.nextName();

			if (name.equals("id")) {
				id = reader.nextString();
			} else if (name.equals("type")) {
				type = reader.nextString();
			} else {
				missingKeys.remove(name);
				reader.skipValue();
			}
		}

		reader.endObject();

		return id != null && type != null && missingKeys.isEmpty() ? new Result(null, null, id, type, null) : null;
	}

	private record Result(String postId, String article, String versionId, String type, String link) { }
}
