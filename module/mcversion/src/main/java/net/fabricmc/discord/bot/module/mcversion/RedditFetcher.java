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
	private static final String QUERY = "limit="+PAGE_SIZE+"&raw_json=1&before=%s";
	private static final Pattern ARTICLE_PATTERN = Pattern.compile("\\((https://www.minecraft.net/article/.+?)\\)");
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
		String before = mcVersionModule.getBot().getConfigEntry(LAST_POST);

		pageLoop: for (int i = 0; i < MAX_PAGES; i++) {
			HttpResponse<InputStream> response = HttpUtil.makeRequest(HttpUtil.toUri(HOST, PATH, QUERY.formatted(before)));
			if (i == 0) before = null;

			if (response.statusCode() != 200) {
				LOGGER.warn("Request failed: {}", response.statusCode());
				return;
			}

			try (JsonReader reader = new JsonReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
				reader.beginObject();

				while (reader.hasNext()) {
					if (!reader.nextName().equals("data")) {
						reader.skipValue();
						continue;
					}

					reader.beginObject();

					while (reader.hasNext()) {
						if (!reader.nextName().equals("children")) {
							reader.skipValue();
							continue;
						}

						reader.beginArray();

						if (!reader.hasNext()) {
							break pageLoop;
						}

						before = null;
						String lastRelease = latestRelease;
						String lastSnapshot = latestSnapshot;
						String lastPending = latestPending;

						do {
							Result result = parsePost(reader);
							if (before == null) before = result.postName();

							if (result.id() != null) {
								if (McVersionModule.KIND_RELEASE.equals(result.type()) && latestRelease == lastRelease) {
									latestRelease = result.id();
								} else if (McVersionModule.KIND_SNAPSHOT.equals(result.type()) && latestSnapshot == lastSnapshot) {
									latestSnapshot = result.id();
								} else if (McVersionModule.KIND_PENDING.equals(result.type()) && latestPending == lastPending) {
									latestPending = result.id();
								} else {
									LOGGER.warn("Unknown release type for {}: {}", result.id(), result.type());
								}
							}

							if (result.article() != null) {
								// TODO: send to NewsFetcher
							}
						} while (reader.hasNext());

						break;
					}

					break;
				}
			}
		}

		if (before != null) mcVersionModule.getBot().setConfigEntry(LAST_POST, before);
	}

	private Result parsePost(JsonReader reader) throws IOException {
		String name = null;
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
					name = reader.nextString();
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
			return new Result(name, null, null, null, null);
		}

		Matcher matcher = ARTICLE_PATTERN.matcher(selftext);
		String article = null;

		while (matcher.find()) {
			if (article == null) {
				article = matcher.group(1);
			} else {
				LOGGER.debug("Skipping {} by {} with multiple articles", name, author);
				return new Result(name, null, null, null, null);
			}
		}

		matcher = DOWNLOAD_PATTERN.matcher(selftext);
		Result result = null;

		while (matcher.find()) {
			String link = matcher.group(1);

			try {
				HttpResponse<InputStream> response = HttpUtil.makeRequest(new URI(link));

				if (response.statusCode() != 200) {
					LOGGER.info("Link {} in {} by {} request failed: {}", link, name, author, response.statusCode());
					continue;
				}

				try (ZipInputStream zis = new ZipInputStream(response.body())) {
					ZipEntry entry;

					while ((entry = zis.getNextEntry()) != null) {
						if (!entry.isDirectory() && entry.getName().endsWith(".json")) {
							try {
								Result partialResult = processJson(zis);

								if (partialResult == null) {
									LOGGER.debug("JSON {}:{} in {} by {} doesn't appear to be a MC version JSON", link, entry.getName(), name, author);
								} else if (result != null) {
									LOGGER.debug("Skipping {} by {} with multiple version jsons", name, author);
									return new Result(name, null, null, null, null);
								} else {
									result = new Result(name, article, partialResult.id, partialResult.type, link);
								}
							} catch (IOException e) {
								LOGGER.info("Json {}:{} in {} by {} processing failed: {}", link, entry.getName(), name, author, e);
							}
						}
					}
				}
			} catch (IOException | InterruptedException | URISyntaxException e) {
				LOGGER.info("Link {} in {} by {} processing failed: {}", link, name, author, e);
			}
		}

		if (result == null) result = new Result(name, article, null, null, null);

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

	private record Result(String postName, String article, String id, String type, String link) { }
}
