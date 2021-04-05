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

package net.fabricmc.discord.bot.module.mapping;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URISyntaxException;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import com.google.gson.stream.JsonReader;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

import net.fabricmc.discord.bot.DiscordBot;
import net.fabricmc.discord.bot.module.mapping.mappinglib.MappingTree;
import net.fabricmc.discord.bot.module.mapping.mappinglib.MemoryMappingTree;
import net.fabricmc.discord.bot.module.mapping.mappinglib.Tiny1Reader;
import net.fabricmc.discord.bot.module.mapping.mappinglib.Tiny2Reader;
import net.fabricmc.discord.bot.util.HttpUtil;

public final class MappingRepository {
	private static final int updatePeriodSec = 120;
	private static final String metaHost = "meta.fabricmc.net";
	private static final String mavenHost = "maven.fabricmc.net";
	private static final String KIND_INTERMEDIARY = "intermediary";
	private static final String KIND_YARN = "yarn";

	private static final Logger LOGGER = LogManager.getLogger(MappingRepository.class);

	private final DiscordBot bot;

	private volatile String latestMcVersion;
	private volatile String latestStableMcVersion;
	private final Map<String, MappingData> mcVersionToMappingMap = new ConcurrentHashMap<>();

	MappingRepository(DiscordBot bot) {
		this.bot = bot;

		bot.getScheduledExecutor().scheduleWithFixedDelay(this::updateVersion, 0, updatePeriodSec, TimeUnit.SECONDS);
	}

	private void updateVersion() {
		try {
			updateLatestMcVersions();

			// remove outdated mapping data
			mcVersionToMappingMap.entrySet().removeIf(entry -> {
				try {
					return !Objects.equals(entry.getValue().intermediaryMavenId, getMavenId(entry.getKey(), KIND_INTERMEDIARY))
							|| !Objects.equals(entry.getValue().yarnMavenId, getMavenId(entry.getKey(), KIND_YARN));
				} catch (IOException | InterruptedException | URISyntaxException e) {
					throw new RuntimeException(e);
				}
			});
		} catch (Exception e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}

	private void updateLatestMcVersions() throws IOException, InterruptedException, URISyntaxException {
		HttpResponse<InputStream> response = HttpUtil.makeRequest(metaHost, "/v2/versions/game");
		if (response.statusCode() != 200) throw new IOException("request failed with code "+response.statusCode());

		String latestMcVersion = null;
		String latestStableMcVersion = null;

		try (JsonReader reader = new JsonReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
			boolean detectedLatest = false;

			reader.beginArray();

			while (reader.hasNext()) {
				Boolean stable = null;
				String version = null;

				reader.beginObject();

				while (reader.hasNext()) {
					switch (reader.nextName()) {
					case "version" -> version = reader.nextString();
					case "stable" -> stable = reader.nextBoolean();
					default -> reader.skipValue();
					}
				}

				reader.endObject();

				if (stable == null || version == null) {
					throw new IOException("malformed version entry, missing version or stable attribute");
				}

				if (!detectedLatest) {
					latestMcVersion = version;
					detectedLatest = true;
				}

				if (stable) {
					latestStableMcVersion = version;
					break;
				}
			}
		}

		if (latestMcVersion == null || latestStableMcVersion == null) {
			throw new IOException("no mc version entries");
		}

		this.latestMcVersion = latestMcVersion;
		this.latestStableMcVersion = latestStableMcVersion;
	}

	private static @Nullable String getMavenId(String mcVersion, String kind) throws IOException, InterruptedException, URISyntaxException {
		if (mcVersion.indexOf('/') >= 0)  throw new IllegalArgumentException("invalid mc version: "+mcVersion);

		HttpResponse<InputStream> response = HttpUtil.makeRequest(metaHost, "/v2/versions/%s/%s".formatted(kind, mcVersion), "limit=1");
		if (response.statusCode() != 200) throw new IOException("request failed with code "+response.statusCode());

		String mavenId = null;

		try (JsonReader reader = new JsonReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
			reader.beginArray();

			if (reader.hasNext()) {
				reader.beginObject();

				while (reader.hasNext()) {
					switch (reader.nextName()) {
					case "maven" -> mavenId = reader.nextString();
					default -> reader.skipValue();
					}
				}

				reader.endObject();
			}
		}

		return mavenId;
	}

	public static void main(String[] args) throws Exception {
		//MappingData data = createMappingData("21w12a");
		MappingData data = createMappingData("1.16.5");
		if (data == null) throw new RuntimeException("no data");

		System.out.println(data.findClasses("1337"));
		System.out.println(data.findFields("field_6393"));
		System.out.println(data.findFields("6393"));
		System.out.println(data.findFields("net/minecraft/class_1338/field_6393"));
		System.out.println(data.findFields("class_1338/field_6393"));
	}

	public MappingData getLatestMcMappingData() {
		String mcVersion = latestMcVersion;
		if (mcVersion == null) throw new IllegalStateException("latest mc version is unknown");

		return getMappingData(mcVersion);
	}

	public MappingData getLatestStableMcMappingData() {
		String mcVersion = latestStableMcVersion;
		if (mcVersion == null) throw new IllegalStateException("latest stable mc version is unknown");

		return getMappingData(mcVersion);
	}

	public MappingData getMappingData(String mcVersion) {
		return mcVersionToMappingMap.computeIfAbsent(mcVersion, MappingRepository::createMappingData);
	}

	private static MappingData createMappingData(String mcVersion) {
		try {
			String yarnMavenId = getMavenId(mcVersion, KIND_YARN);
			if (yarnMavenId == null) return null;

			String intermediaryMavenId = getMavenId(mcVersion, KIND_INTERMEDIARY);
			MappingTree yarnMappingTree = retrieveMappings(yarnMavenId, "mergedv2", true);
			if (yarnMappingTree == null) yarnMappingTree = retrieveMappings(yarnMavenId, null, false);
			if (yarnMappingTree == null) return null;

			return new MappingData(mcVersion, intermediaryMavenId, yarnMavenId, yarnMappingTree);
		} catch (IOException | InterruptedException | URISyntaxException e) {
			throw new RuntimeException(e);
		}
	}

	private static MappingTree retrieveMappings(String yarnMavenId, String classifier, boolean isTinyV2) throws URISyntaxException, IOException, InterruptedException {
		HttpResponse<InputStream> response = HttpUtil.makeRequest(mavenHost, getMavenPath(yarnMavenId, classifier, "jar"));

		if (response.statusCode() != 200) {
			response.body().close();
			return null;
		}

		try (ZipInputStream zis = new ZipInputStream(response.body())) {
			ZipEntry entry;

			while ((entry = zis.getNextEntry()) != null) {
				if (entry.getName().equals("mappings/mappings.tiny")) {
					InputStreamReader reader = new InputStreamReader(zis, StandardCharsets.UTF_8);
					MemoryMappingTree ret = new MemoryMappingTree(true);

					if (isTinyV2) {
						Tiny2Reader.read(reader, ret);
					} else {
						Tiny1Reader.read(reader, ret);
					}

					return ret;
				}
			}
		}

		return null; // no mappings/mappings.tiny
	}

	private static String getMavenPath(String id, String classifier, String extension) {
		int groupEnd = id.indexOf(':');
		int artifactEnd = id.indexOf(':', groupEnd + 1);
		if (groupEnd < 0 || artifactEnd < 0) throw new IllegalArgumentException("invalid id: "+id);

		String group = id.substring(0, groupEnd);
		String artifact = id.substring(groupEnd + 1, artifactEnd);
		String version = id.substring(artifactEnd + 1);

		if (classifier == null || classifier.isEmpty()) {
			classifier = "";
		} else {
			classifier = "-"+classifier;
		}

		return String.format("/%s/%s/%s/%s-%s%s.%s", group.replace('.', '/'), artifact, version, artifact, version, classifier, extension);
	}
}
