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

package net.fabricmc.discord.bot.module.fabricversion;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URISyntaxException;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import com.google.gson.stream.JsonReader;
import org.javacord.api.entity.message.embed.EmbedBuilder;
import org.jetbrains.annotations.Nullable;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import net.fabricmc.discord.bot.command.Command;
import net.fabricmc.discord.bot.command.CommandContext;
import net.fabricmc.discord.bot.command.CommandException;
import net.fabricmc.discord.bot.module.mcversion.McVersionRepo;
import net.fabricmc.discord.bot.util.HttpUtil;

public final class FabricVersionCommand extends Command {
	private static final String metaHost = "meta.fabricmc.net";
	private static final String mavenHost = "maven.fabricmc.net";

	private static final Pattern RELEASE_PATTERN = Pattern.compile("^(\\d+)\\.(\\d+)(?:\\.(\\d+))?");
	private static final Pattern SNAPSHOT_PATTERN = Pattern.compile("(\\d+)w0?(0|[1-9]\\d*)([a-z])");

	FabricVersionCommand() { }

	@Override
	public String name() {
		return "fabricVersion";
	}

	@Override
	public List<String> aliases() {
		return List.of("fabricVersions", "version", "versions");
	}

	@Override
	public String usage() {
		return "[latest | latestStable | <mcVersion>]";
	}

	@Override
	public boolean run(CommandContext context, Map<String, String> arguments) throws Exception {
		String mcVersion = arguments.get("mcVersion");
		if (mcVersion == null) mcVersion = arguments.get("unnamed_0");
		mcVersion = McVersionRepo.get(context.bot()).resolve(context, mcVersion);
		if (mcVersion == null) throw new CommandException("invalid version or latest version data is unavailable");

		VersionData data = getData(mcVersion);
		if (data == null) throw new CommandException("Invalid/unavailable MC version");

		StringBuilder sb = new StringBuilder(1000);

		sb.append(String.format("**build.gradle** (constants inside the build script)\n"
				+ "```"
				+ "dependencies {\n"
				+ "    minecraft \"com.mojang:minecraft:%s\"\n"
				+ "    mappings \"net.fabricmc:yarn:%s:v2\"\n"
				+ "    modImplementation \"net.fabricmc:fabric-loader:%s\"\n",
				data.mcVersion, data.yarnVersion, data.loaderVersion));

		if (data.apiVersion != null) {
			sb.append(String.format("\n"
					+ "    // Fabric API\n"
					+ "    modImplementation \"net.fabricmc.fabric-api:fabric-api:%s\"\n",
					data.apiVersion));
		}

		sb.append("}```\n");

		sb.append(String.format("**gradle.properties** (constants in a separate file, as with Example Mod)\n"
				+ "```"
				+ "minecraft_version=%s\n"
				+ "yarn_mappings=%s\n"
				+ "loader_version=%s\n",
				data.mcVersion, data.yarnVersion, data.loaderVersion));

		if (data.apiVersion != null) {
			sb.append(String.format("\n"
					+ "# Fabric API\n"
					+ "fabric_version=%s",
					data.apiVersion));
		}

		sb.append("```\n");

		sb.append(String.format("**Mappings Migration** ([more info](https://fabricmc.net/wiki/tutorial:migratemappings))\n"
				+ "```"
				+ "gradlew migrateMappings --mappings \"%s\""
				+ "```",
				data.yarnVersion));

		sb.append("\nNote that the Fabric API version is usually only correct for the latest minor MC release "
				+ "(e.g. 1.16.5 or 1.15.2) due to implementation limitations. "
				+ "Check [CurseForge](https://minecraft.curseforge.com/projects/fabric/files) for a more precise listing.");

		context.channel().sendMessage(new EmbedBuilder()
				.setTitle("%s Fabric versions".formatted(data.mcVersion))
				.setDescription(sb.toString())
				.setTimestampToNow());

		return true;
	}

	private static @Nullable VersionData getData(String mcVersion) throws IOException, InterruptedException, URISyntaxException {
		if (mcVersion.indexOf('/') >= 0)  throw new IllegalArgumentException("invalid mc version: "+mcVersion);

		String yarnVersion = null;
		String loaderVersion = null;

		HttpResponse<InputStream> response = HttpUtil.makeRequest(HttpUtil.toUri(metaHost, "/v1/versions/loader/%s".formatted(mcVersion), "limit=1"));
		if (response.statusCode() != 200) throw new IOException("meta request failed with code "+response.statusCode());

		try (JsonReader reader = new JsonReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
			reader.beginArray();
			if (!reader.hasNext()) return null;

			reader.beginObject();

			while (reader.hasNext()) {
				String key = reader.nextName();

				switch (key) {
				case "loader", "mappings" -> {
					reader.beginObject();

					while (reader.hasNext()) {
						if (reader.nextName().equals("version")) {
							if (key.equals("loader")) {
								loaderVersion = reader.nextString();
							} else { // mappings
								yarnVersion = reader.nextString();
							}
						} else {
							reader.skipValue();
						}
					}

					reader.endObject();
				}
				default -> reader.skipValue();
				}
			}

			reader.endObject();
		}

		List<String> apiBranches = getApiBranch(mcVersion);
		//String apiMavenGroupName = apiBranch.startsWith("1.14") ? "net.fabricmc.fabric" : "net.fabricmc.fabric-api:fabric-api";
		String apiMavenGroupName = "net.fabricmc.fabric-api:fabric-api";
		String[] apiVersions = new String[apiBranches.size() * 2]; // ordered by match quality (exact matches, then prefix matches)

		response = HttpUtil.makeRequest(HttpUtil.toUri(mavenHost, "/%s/maven-metadata.xml".formatted(apiMavenGroupName.replace('.', '/').replace(':', '/'))));
		if (response.statusCode() != 200) throw new IOException("maven request failed with code "+response.statusCode());

		try (InputStream is = response.body()) {
			Element element = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(is).getDocumentElement();
			//element = (Element) element.getElementsByTagName("metadata").item(0);
			element = (Element) element.getElementsByTagName("versioning").item(0);
			element = (Element) element.getElementsByTagName("versions").item(0);

			NodeList list = element.getElementsByTagName("version");

			for (int i = 0, max = list.getLength(); i < max; i++) {
				String version = list.item(i).getTextContent();
				int pos = Math.max(version.lastIndexOf('+'), version.lastIndexOf('-'));
				if (pos < 0) continue;

				int suffixStart = pos + 1;
				int suffixLen = version.length() - suffixStart;

				for (int j = 0; j < apiBranches.size(); j++) {
					String apiBranch = apiBranches.get(j);

					if (version.startsWith(apiBranch, suffixStart)) {
						// overwriting the prev apiVersions entry assumes the latest version being last in the metadata xml

						if (apiBranch.length() == suffixLen) { // exact match
							apiVersions[j] = version;
							break; // match won't get better
						} else { // prefix match
							apiVersions[j + apiBranches.size()] = version;
						}
					}
				}
			}
		} catch (ParserConfigurationException | SAXException e) {
			e.printStackTrace(); // TODO: log properly
		}

		String apiVersion = null;

		for (String version : apiVersions) {
			if (version != null) {
				apiVersion = version;
				break;
			}
		}

		return new VersionData(mcVersion, yarnVersion, loaderVersion, apiVersion, apiMavenGroupName);
	}

	private static List<String> getApiBranch(String mcVersion) {
		Matcher matcher = RELEASE_PATTERN.matcher(mcVersion);

		if (matcher.find()) {
			int major = Integer.parseInt(matcher.group(1));
			int minor = Integer.parseInt(matcher.group(2));
			int patch = matcher.group(3) != null ? Integer.parseInt(matcher.group(3)) : 0;

			return Arrays.asList(String.format("%d.%d.%s", major, minor, patch), String.format("%d.%d", major, minor));
		}

		matcher = SNAPSHOT_PATTERN.matcher(mcVersion);
		String version;

		if (matcher.find()) {
			int year = Integer.parseInt(matcher.group(1));
			int week = Integer.parseInt(matcher.group(2));

			if (year > 21 || year == 21 && week >= 28) {
				version = "1.18";
			} else if (year == 21 && week <= 20 || year == 20 && week >= 45) {
				version = "1.17";
			} else if (year == 20 && week >= 6) {
				version = "1.16";
			} else if (year == 19 && week >= 34) {
				version = "1.15";
			} else if (year == 18 && week >= 43 || year == 19 && week <= 14) {
				version = "1.14";
			} else {
				version = "1.13";
			}
		} else {
			version = "1.18";
		}

		return Collections.singletonList(version);
	}

	private record VersionData(String mcVersion, String yarnVersion, String loaderVersion, String apiVersion, String apiMavenName) { }
}
