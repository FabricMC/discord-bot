/*
 * Copyright (c) 2024 FabricMC
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
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Predicate;
import java.util.regex.Pattern;

import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.javacord.api.entity.message.embed.EmbedBuilder;
import org.jetbrains.annotations.Nullable;

import net.fabricmc.discord.bot.util.HttpUtil;

final class LauncherNewsFetcher {
	private static final String HOST = "launchercontent.mojang.com";
	private static final String PATH = "/v2/javaPatchNotes.json";
	private static final Logger LOGGER = LogManager.getLogger("mcversion/launcher");
	private final McVersionModule mcVersionModule;
	private Version latestRelease;
	private Version latestSnapshot;
	private long lastUpdateTimeMs = System.currentTimeMillis();
	private ZonedDateTime lastAnnounceTime = ZonedDateTime.now(ZoneId.of("UTC"));

	LauncherNewsFetcher(McVersionModule module) {
		this.mcVersionModule = module;
	}

	Version getLatestRelease() {
		return latestRelease;
	}

	Version getLatestSnapshot() {
		return latestSnapshot;
	}

	long getLastUpdateTimeMs() {
        return lastUpdateTimeMs;
    }

	void update() throws IOException, URISyntaxException, InterruptedException, DateTimeParseException {
		fetchLatest();
		Version latest = latestRelease.date.isAfter(latestSnapshot.date) ? latestRelease : latestSnapshot;

		if (latest.date.isAfter(lastAnnounceTime) && !McVersionModule.isOldVersion(latest.name) && announce(latest)) {
			lastAnnounceTime = latest.date;
		}
	}

	void fetchLatest() throws IOException, URISyntaxException, InterruptedException, DateTimeParseException {
		HttpResponse<InputStream> response = HttpUtil.makeRequest(HttpUtil.toUri(HOST, PATH));

		if (response.statusCode() != 200) {
			LOGGER.warn("Request failed: {}", response.statusCode());
			response.body().close();
			return;
		}

		try (JsonReader reader = new JsonReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
			reader.beginObject();

			while (reader.hasNext()) {
				if (reader.nextName().equals("entries")) {
					reader.beginArray();

					// Note: the first item is often not the latest!
					while (reader.peek() == JsonToken.BEGIN_OBJECT) {
						reader.beginObject();

						String type = null;
						String title = null;
						String version = null;
						String date = null;
						String imagePath = null;
						String shortText = null;
						boolean readSnapshot = false;
						boolean readRelease = false;

						ZonedDateTime releaseDateTime = null;
						boolean skipTheRest = false;

						while (reader.peek() == JsonToken.NAME) {
							if (skipTheRest) {
								reader.nextName();
								reader.skipValue();
								continue;
							}

							switch (reader.nextName()) {
								case "title" -> {
									title = reader.nextString();
								}
								case "version" -> {
									version = reader.nextString();
								}
								case "date" -> {
									date = reader.nextString();
									releaseDateTime = ZonedDateTime.parse(date);

									if (
											(readSnapshot && latestSnapshot != null && releaseDateTime.isBefore(latestSnapshot.date)) ||
													(readRelease && latestRelease != null && releaseDateTime.isBefore(latestRelease.date))) {
										skipTheRest = true;
									}
								}
								case "image" -> {
									reader.beginObject();
									while (reader.peek() == JsonToken.NAME) {
                                        if (reader.nextName().equals("url")) {
                                            imagePath = reader.nextString();
                                        } else {
                                            reader.skipValue();
                                        }
									}
									reader.endObject();
								}
								case "shortText" -> {
									shortText = reader.nextString();
								}
								case "type" -> {
									type = reader.nextString();
									readSnapshot = type.equals("snapshot");
									readRelease = type.equals("release");

				        			if (
                                            releaseDateTime != null &&
								        	(
                                                    (readSnapshot && latestSnapshot != null && releaseDateTime.isBefore(latestSnapshot.date)) ||
                                                            (readRelease && latestRelease != null && releaseDateTime.isBefore(latestRelease.date)))
                                    ) {
										skipTheRest = true;
									}
								}
								default -> reader.skipValue();
							}
						}

						reader.endObject();

						if (skipTheRest || version == null || date == null || !(readRelease || readSnapshot)) {
							continue;
						}

						URI imageUri = null;

						if (imagePath != null) {
							try {
								imageUri = HttpUtil.toUri(HOST, imagePath);
							} catch (URISyntaxException ignored) {
							}
						}

						Version latest = new Version(
								type,
								version,
								Objects.requireNonNull(title, "Minecraft %s".formatted(version)),
								imageUri,
								Objects.requireNonNull(shortText, "A new update was released!"),
								releaseDateTime
						);

						if (readRelease) {
							latestRelease = latest;
						} else if (readSnapshot) {
							latestSnapshot = latest;
						}

						lastUpdateTimeMs = System.currentTimeMillis();
					}

					reader.endArray();
				} else {
					reader.skipValue();
				}
			}
		}
	}

	private boolean announce(Version version) {
		// Note: at this point URL is not available (yet).
		// However, this is *very* quick from my observation.
		LOGGER.info("Announcing MCLauncher-News {} version: {}", version.type, version.name);
		return this.mcVersionModule.sendAnnouncement(mcVersionModule.getUpdateChannel(), version.toEmbed());
	}

	record Version(String type, String name, String title, @Nullable URI image, String shortText, ZonedDateTime date) {
		private static final String URL_PREFIX = "https://www.minecraft.net/en-us/article/minecraft";
		private static final Predicate<String> SNAPSHOT_PREDICATE = Pattern.compile("^\\d+w\\d+[a-z]+$").asMatchPredicate();
		private static final Pattern NON_ALPHANUMERIC = Pattern.compile("[^a-z0-9]");

        EmbedBuilder toEmbed() {
			EmbedBuilder builder = new EmbedBuilder();
			builder.setTitle(title);
			builder.setDescription(shortText + "...");
			if (image != null) builder.setThumbnail(image.toString());
			builder.setTimestamp(date.toInstant());
			builder.setUrl(getUrl());
			builder.setFooter("URL is automatically generated and might not be valid.");
			return builder;
		}

		String getUrl() {
			if ("release".equals(type)) {
				return "%s-java-edition-%s".formatted(URL_PREFIX, name.replace('.', '-'));
			} else if (SNAPSHOT_PREDICATE.test(name)) {
				return "%s-snapshot-%s".formatted(URL_PREFIX, name);
			} else {
				return "%s-%s".formatted(URL_PREFIX, NON_ALPHANUMERIC.matcher(name.toLowerCase(Locale.ROOT)).replaceAll("-"));
			}
		}
	}
}
