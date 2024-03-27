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
import java.net.URISyntaxException;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

import com.google.gson.stream.JsonReader;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import net.fabricmc.discord.bot.util.HttpUtil;

final class MetaFetcher {
	private static final String HOST = "piston-meta.mojang.com";
	private static final String PATH = "/mc/game/version_manifest_v2.json";

	private static final Logger LOGGER = LogManager.getLogger("mcversion/meta");

	private String latestRelease;
	private String latestSnapshot;
	private long lastUpdateTimeMs;

	MetaFetcher(McVersionModule mcVersionModule) { }

	String getLatestRelease() {
		return latestRelease;
	}

	String getLatestSnapshot() {
		return latestSnapshot;
	}

	long getLastUpdateTimeMs() {
		return lastUpdateTimeMs;
	}

	void update() throws IOException, URISyntaxException, InterruptedException {
		HttpResponse<InputStream> response = HttpUtil.makeRequest(HttpUtil.toUri(HOST, PATH));

		if (response.statusCode() != 200) {
			LOGGER.warn("Request failed: {}", response.statusCode());
			response.body().close();
			return;
		}

		try (JsonReader reader = new JsonReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
			reader.beginObject();
			boolean readRelease = false;
			boolean readSnapshot = false;

			while (reader.hasNext()) {
				if (!reader.nextName().equals("latest")) {
					reader.skipValue();
					continue;
				}

				reader.beginObject();

				while (reader.hasNext()) {
					switch (reader.nextName()) {
					case "release" -> {
						latestRelease = reader.nextString();
						readRelease = true;
					}
					case "snapshot" -> {
						latestSnapshot = reader.nextString();
						readSnapshot = true;
					}
					default -> reader.skipValue();
					}
				}

				break;
			}

			if (!readRelease || !readSnapshot) {
				LOGGER.warn("Missing data: release={} snapshot={}", readRelease ? latestRelease : "(missing)", readSnapshot ? latestSnapshot : "(missing)");
			}
		}

		lastUpdateTimeMs = System.currentTimeMillis();
	}
}
