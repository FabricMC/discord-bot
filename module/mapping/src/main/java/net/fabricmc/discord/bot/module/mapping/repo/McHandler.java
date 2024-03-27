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
import java.io.Reader;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

import com.google.gson.stream.JsonReader;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import net.fabricmc.discord.bot.util.HttpUtil;
import net.fabricmc.mappingio.adapter.MappingSourceNsSwitch;
import net.fabricmc.mappingio.format.proguard.ProGuardFileReader;
import net.fabricmc.mappingio.tree.MemoryMappingTree;

final class McHandler {
	private static final Logger LOGGER = LogManager.getLogger(McHandler.class);

	static boolean retrieveMcMappings(String mcVersion, Path mappingsDir, MemoryMappingTree visitor) throws URISyntaxException, IOException, InterruptedException {
		if (mcVersion.indexOf('/') >= 0) throw new IllegalArgumentException("invalid mc version: "+mcVersion);

		Path file = mappingsDir.resolve("mc").resolve("mojmap-%s.map".formatted(mcVersion));

		if (!Files.exists(file)) {
			if (!downloadMcMappings(mcVersion, file)) {
				return false;
			}
		}

		if (Files.size(file) > 0) {
			try (Reader reader = Files.newBufferedReader(file)) {
				ProGuardFileReader.read(reader, "mojmap", "official", new MappingSourceNsSwitch(new NewElementBlocker(visitor, visitor), "official"));
			}
		}

		return true;
	}

	private static boolean downloadMcMappings(String mcVersion, Path out) throws URISyntaxException, IOException, InterruptedException {
		URI jsonUrl = queryVersionJsonUrl(mcVersion, "launchermeta.mojang.com", "/mc/game/version_manifest_v2.json");
		if (jsonUrl == null) jsonUrl = queryVersionJsonUrl(mcVersion, "maven.fabricmc.net", "/net/minecraft/experimental_versions.json");
		if (jsonUrl == null) return false;

		URI mappingsUrl = null;

		try {
			HttpResponse<InputStream> response = HttpUtil.makeRequest(jsonUrl);

			if (response.statusCode() != 200) {
				response.body().close();
				return false;
			}

			try (JsonReader reader = new JsonReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
				reader.beginObject();

				while (reader.hasNext()) {
					if (!reader.nextName().equals("downloads")) {
						reader.skipValue();
						continue;
					}

					reader.beginObject();

					while (reader.hasNext()) {
						if (!reader.nextName().equals("client_mappings")) {
							reader.skipValue();
							continue;
						}

						reader.beginObject();

						while (reader.hasNext()) {
							if (reader.nextName().equals("url")) {
								mappingsUrl = new URI(reader.nextString());
								break;
							} else {
								reader.skipValue();
							}
						}

						break;
					}

					break;
				}
			}
		} catch (IOException e) {
			HttpUtil.logError("fetching/parsing mc version json %s failed".formatted(jsonUrl), e, LOGGER);
			return false;
		}

		if (mappingsUrl == null) { // no mappings for the version
			Files.deleteIfExists(out);
			Files.createFile(out);
			return true;
		}

		try {
			HttpResponse<InputStream> response = HttpUtil.makeRequest(mappingsUrl);

			if (response.statusCode() != 200) {
				response.body().close();
				return false;
			}

			Files.createDirectories(out.toAbsolutePath().getParent());
			Files.copy(response.body(), out, StandardCopyOption.REPLACE_EXISTING);
		} catch (IOException e) {
			HttpUtil.logError("fetching/saving mc mappings %s failed".formatted(mappingsUrl), e, LOGGER);
			return false;
		}

		return true;
	}

	private static URI queryVersionJsonUrl(String mcVersion, String host, String path) throws InterruptedException, URISyntaxException {
		try {
			HttpResponse<InputStream> response = HttpUtil.makeRequest(HttpUtil.toUri(host, path));

			if (response.statusCode() != 200) {
				response.body().close();
				return null;
			}

			URI jsonUrl = null;

			try (JsonReader reader = new JsonReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
				reader.beginObject();

				while (reader.hasNext()) {
					if (!reader.nextName().equals("versions")) {
						reader.skipValue();
						continue;
					}

					reader.beginArray();

					while (reader.hasNext()) {
						reader.beginObject();

						String id = null;
						String url = null;

						while (reader.hasNext()) {
							switch (reader.nextName()) {
							case "id" -> id = reader.nextString();
							case "url" -> url = reader.nextString();
							default -> reader.skipValue();
							}
						}

						reader.endObject();

						if (id == null) throw new IOException("missing id");
						if (url == null) throw new IOException("missing url");

						if (id.equals(mcVersion)) {
							jsonUrl = new URI(url);
							break;
						}
					}

					break;
				}
			}

			return jsonUrl;
		} catch (IOException e) {
			HttpUtil.logError("fetching/parsing version json url from "+host+" failed", e, LOGGER);
			return null;
		}
	}
}
