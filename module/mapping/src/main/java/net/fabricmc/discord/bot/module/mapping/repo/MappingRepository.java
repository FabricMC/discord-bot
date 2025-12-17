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

package net.fabricmc.discord.bot.module.mapping.repo;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URISyntaxException;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import com.google.gson.stream.JsonReader;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

import net.fabricmc.discord.bot.DiscordBot;
import net.fabricmc.discord.bot.module.mapping.MappingCommandUtil;
import net.fabricmc.discord.bot.util.HttpUtil;
import net.fabricmc.mappingio.tree.MemoryMappingTree;

public final class MappingRepository {
	private static final int updatePeriodSec = 120;
	private static final String metaHost = "meta.fabricmc.net";
	private static final String KIND_INTERMEDIARY = "intermediary";
	private static final String KIND_YARN = "yarn";

	private static final Logger LOGGER = LogManager.getLogger(MappingRepository.class);

	private final DiscordBot bot;
	private final Path mappingsDir;

	private final Map<String, MappingData> mcVersionToMappingMap = new ConcurrentHashMap<>();

	public MappingRepository(DiscordBot bot, Path dataDir) {
		this.bot = bot;
		this.mappingsDir = dataDir.resolve("mappings");

		bot.getScheduledExecutor().scheduleWithFixedDelay(this::updateVersion, 0, updatePeriodSec, TimeUnit.SECONDS);
	}

	private void updateVersion() {
		try {
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
			LOGGER.warn("stale mapping removal failed", e);
		}
	}

	private static @Nullable String getMavenId(String mcVersion, String kind) throws IOException, InterruptedException, URISyntaxException {
		if (mcVersion.indexOf('/') >= 0) throw new IllegalArgumentException("invalid mc version: "+mcVersion);
		if (MappingCommandUtil.isRipYarn(mcVersion)) return null; // RIP yarn

		HttpResponse<InputStream> response = HttpUtil.makeRequest(HttpUtil.toUri(metaHost, "/v2/versions/%s/%s".formatted(kind, mcVersion), "limit=1"));

		if (response.statusCode() != 200) {
			response.body().close();
			throw new IOException("request failed with code "+response.statusCode());
		}

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
		MappingData data = createMappingData("1.16.5", Paths.get("data", "mappings"));
		if (data == null) throw new RuntimeException("no data");

		System.out.println(data.findClasses("1337", data.getAllNamespaces()));
		System.out.println(data.findFields("field_6393", data.getAllNamespaces()));
		System.out.println(data.findFields("6393", data.getAllNamespaces()));
		System.out.println(data.findFields("net/minecraft/class_1338/field_6393", data.getAllNamespaces()));
		System.out.println(data.findFields("class_1338/field_6393", data.getAllNamespaces()));
	}

	public Collection<String> getLoadedVersions() {
		return mcVersionToMappingMap.keySet();
	}

	public MappingData getMappingData(String mcVersion) {
		return mcVersionToMappingMap.computeIfAbsent(mcVersion, v -> createMappingData(v, mappingsDir));
	}

	private static MappingData createMappingData(String mcVersion, Path mappingsDir) {
		try {
			String yarnMavenId = getMavenId(mcVersion, KIND_YARN);
			if (yarnMavenId == null) return null;

			String intermediaryMavenId = getMavenId(mcVersion, KIND_INTERMEDIARY);
			MemoryMappingTree mappingTree = new MemoryMappingTree(true);
			String mcpVersion;

			if ((!FabricHandler.retrieveYarnMappings(yarnMavenId, "mergedv2", true, mappingTree)
					&& !FabricHandler.retrieveYarnMappings(yarnMavenId, null, false, mappingTree))
					|| !McHandler.retrieveMcMappings(mcVersion, mappingsDir, mappingTree)
					|| !ForgeHandler.retrieveSrgMappings(mcVersion, mappingsDir, mappingTree)
					|| (mcpVersion = ForgeHandler.retrieveMcpMappings(mcVersion, mappingsDir, mappingTree)) == null) {
				return null;
			}

			if (mcpVersion.isEmpty()) mcpVersion = null;

			return new MappingData(mcVersion, intermediaryMavenId, yarnMavenId, mcpVersion, mappingTree, FabricHandler.hasYarnJavadoc(yarnMavenId));
		} catch (IOException | InterruptedException | URISyntaxException e) {
			throw new RuntimeException(e);
		}
	}

	static String getMavenPath(String id, String classifier, String extension) {
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
