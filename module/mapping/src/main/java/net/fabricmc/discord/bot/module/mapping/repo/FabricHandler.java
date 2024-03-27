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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URISyntaxException;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import net.fabricmc.discord.bot.util.HttpUtil;
import net.fabricmc.mappingio.MappingVisitor;
import net.fabricmc.mappingio.adapter.MappingNsRenamer;
import net.fabricmc.mappingio.format.tiny.Tiny1FileReader;
import net.fabricmc.mappingio.format.tiny.Tiny2FileReader;

final class FabricHandler {
	private static final String mavenHost = "maven.fabricmc.net";

	private static final Logger LOGGER = LogManager.getLogger(FabricHandler.class);

	static boolean retrieveYarnMappings(String yarnMavenId, String classifier, boolean isTinyV2, MappingVisitor visitor) throws URISyntaxException, InterruptedException {
		try {
			HttpResponse<InputStream> response = HttpUtil.makeRequest(HttpUtil.toUri(mavenHost, MappingRepository.getMavenPath(yarnMavenId, classifier, "jar")));

			if (response.statusCode() != 200) {
				response.body().close();
				return false;
			}

			try (ZipInputStream zis = new ZipInputStream(response.body())) {
				ZipEntry entry;

				while ((entry = zis.getNextEntry()) != null) {
					if (entry.getName().equals("mappings/mappings.tiny")) {
						InputStreamReader reader = new InputStreamReader(zis, StandardCharsets.UTF_8);

						visitor = new MappingNsRenamer(visitor, Map.of("named", "yarn"));

						if (isTinyV2) {
							Tiny2FileReader.read(reader, visitor);
						} else {
							Tiny1FileReader.read(reader, visitor);
						}

						return true;
					}
				}
			}
		} catch (IOException e) {
			HttpUtil.logError("fetching/parsing yarn mappings for %s, classifier %s failed".formatted(yarnMavenId, classifier), e, LOGGER);
		}

		return false; // no mappings/mappings.tiny
	}

	static boolean hasYarnJavadoc(String yarnMavenId) throws InterruptedException, URISyntaxException {
		String id = getYarnJavadocDir(yarnMavenId);

		try {
			HttpResponse<InputStream> response = HttpUtil.makeRequest(HttpUtil.toUri(mavenHost, "/jdlist.txt"));

			if (response.statusCode() != 200) {
				LOGGER.warn("Yarn jdlist.txt request failed: {}", response.statusCode());
				response.body().close();
				return false;
			}

			try (BufferedReader reader = new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
				String line;

				while ((line = reader.readLine()) != null) {
					if (line.equals(id)) return true;
				}
			}
		} catch (IOException e) {
			HttpUtil.logError("fetching/parsing yarn jdlist.txt failed", e, LOGGER);
		}

		return false;
	}

	static String getYarnJavadocDir(String yarnMavenId) {
		return yarnMavenId.substring(yarnMavenId.indexOf(':') + 1).replace(':', '-'); // net.fabricmc:yarn:1.16.5+build.9 -> yarn-1.16.5+build.9
	}
}
