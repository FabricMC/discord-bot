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

import java.io.BufferedReader;
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
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import com.google.gson.stream.JsonReader;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import net.fabricmc.discord.bot.DiscordBot;
import net.fabricmc.discord.bot.util.HttpUtil;
import net.fabricmc.mappingio.MappedElementKind;
import net.fabricmc.mappingio.MappingUtil;
import net.fabricmc.mappingio.MappingVisitor;
import net.fabricmc.mappingio.adapter.ForwardingMappingVisitor;
import net.fabricmc.mappingio.adapter.MappingNsRenamer;
import net.fabricmc.mappingio.adapter.MappingSourceNsSwitch;
import net.fabricmc.mappingio.format.ProGuardReader;
import net.fabricmc.mappingio.format.Tiny1Reader;
import net.fabricmc.mappingio.format.Tiny2Reader;
import net.fabricmc.mappingio.format.TsrgReader;
import net.fabricmc.mappingio.tree.MappingTree;
import net.fabricmc.mappingio.tree.MappingTree.ClassMapping;
import net.fabricmc.mappingio.tree.MappingTree.FieldMapping;
import net.fabricmc.mappingio.tree.MappingTree.MethodMapping;
import net.fabricmc.mappingio.tree.MemoryMappingTree;

public final class MappingRepository {
	private static final int updatePeriodSec = 120;
	private static final String metaHost = "meta.fabricmc.net";
	private static final String mavenHost = "maven.fabricmc.net";
	private static final String KIND_INTERMEDIARY = "intermediary";
	private static final String KIND_YARN = "yarn";

	private static final Logger LOGGER = LogManager.getLogger(MappingRepository.class);

	private final DiscordBot bot;
	private final Path mappingsDir;

	private final Map<String, MappingData> mcVersionToMappingMap = new ConcurrentHashMap<>();

	MappingRepository(DiscordBot bot, Path dataDir) {
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

		HttpResponse<InputStream> response = HttpUtil.makeRequest(HttpUtil.toUri(metaHost, "/v2/versions/%s/%s".formatted(kind, mcVersion), "limit=1"));
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
		MappingData data = createMappingData("1.16.5", Paths.get("data", "mappings"));
		if (data == null) throw new RuntimeException("no data");

		System.out.println(data.findClasses("1337", data.getAllNamespaces()));
		System.out.println(data.findFields("field_6393", data.getAllNamespaces()));
		System.out.println(data.findFields("6393", data.getAllNamespaces()));
		System.out.println(data.findFields("net/minecraft/class_1338/field_6393", data.getAllNamespaces()));
		System.out.println(data.findFields("class_1338/field_6393", data.getAllNamespaces()));
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

			if ((!retrieveYarnMappings(yarnMavenId, "mergedv2", true, mappingTree)
					&& !retrieveYarnMappings(yarnMavenId, null, false, mappingTree))
					|| !retrieveMcMappings(mcVersion, mappingsDir, mappingTree)
					|| !retrieveSrgMappings(mcVersion, mappingsDir, mappingTree)
					|| !retrieveMcpMappings(mcVersion, mappingsDir, mappingTree)) {
				return null;
			}

			return new MappingData(mcVersion, intermediaryMavenId, yarnMavenId, mappingTree);
		} catch (IOException | InterruptedException | URISyntaxException e) {
			throw new RuntimeException(e);
		}
	}

	private static boolean retrieveYarnMappings(String yarnMavenId, String classifier, boolean isTinyV2, MappingVisitor visitor) throws URISyntaxException, InterruptedException {
		try {
			HttpResponse<InputStream> response = HttpUtil.makeRequest(HttpUtil.toUri(mavenHost, getMavenPath(yarnMavenId, classifier, "jar")));

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
							Tiny2Reader.read(reader, visitor);
						} else {
							Tiny1Reader.read(reader, visitor);
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

	private static boolean retrieveMcMappings(String mcVersion, Path mappingsDir, MemoryMappingTree visitor) throws URISyntaxException, IOException, InterruptedException {
		if (mcVersion.indexOf('/') >= 0) throw new IllegalArgumentException("invalid mc version: "+mcVersion);

		Path file = mappingsDir.resolve("mc").resolve("mojmap-%s.map".formatted(mcVersion));

		if (!Files.exists(file)) {
			if (!downloadMcMappings(mcVersion, file)) {
				return false;
			}
		}

		if (Files.size(file) > 0) {
			try (Reader reader = Files.newBufferedReader(file)) {
				ProGuardReader.read(reader, "mojmap", "official", new MappingSourceNsSwitch(new NewElementBlocker(visitor, visitor), "official"));
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

	private static boolean retrieveSrgMappings(String mcVersion, Path mappingsDir, MemoryMappingTree visitor) throws URISyntaxException, IOException, InterruptedException {
		if (mcVersion.indexOf('/') >= 0) throw new IllegalArgumentException("invalid mc version: "+mcVersion);

		Path file = mappingsDir.resolve("srg").resolve("srg-%s.tsrg".formatted(mcVersion));

		if (!Files.exists(file)) {
			if (!downloadSrgMappings(mcVersion, file)) {
				return false;
			}
		}

		if (Files.size(file) > 0) {
			try (Reader reader = Files.newBufferedReader(file)) {
				TsrgReader.read(reader, new MappingNsRenamer(new NewElementBlocker(visitor, visitor),
						Map.of(MappingUtil.NS_SOURCE_FALLBACK, "official",
								MappingUtil.NS_TARGET_FALLBACK, "srg",
								"obf", "official")));
			}
		}

		return true;
	}

	private static boolean downloadSrgMappings(String mcVersion, Path out) throws URISyntaxException, IOException, InterruptedException {
		try {
			HttpResponse<InputStream> response = HttpUtil.makeRequest(HttpUtil.toUri("maven.minecraftforge.net", getMavenPath("de.oceanlabs.mcp:mcp_config:%s".formatted(mcVersion), null, "zip")));

			if (response.statusCode() != 200) {
				response.body().close();

				if (response.statusCode() == 404) {
					Files.deleteIfExists(out);
					Files.createFile(out);
					return true;
				} else {
					return false;
				}
			}

			try (ZipInputStream zis = new ZipInputStream(response.body())) {
				ZipEntry entry;

				while ((entry = zis.getNextEntry()) != null) {
					if (entry.getName().equals("config/joined.tsrg")) {
						Files.createDirectories(out.toAbsolutePath().getParent());
						Files.copy(zis, out, StandardCopyOption.REPLACE_EXISTING);

						return true;
					}
				}
			}

			LOGGER.warn("can't find config/joined.tsrg in {} mcp_config", mcVersion);

			return false; // no config/joined.tsrg
		} catch (IOException e) {
			HttpUtil.logError("fetching/parsing srg mappings for %s failed".formatted(mcVersion), e, LOGGER);
			return false;
		}
	}

	private static boolean retrieveMcpMappings(String mcVersion, Path mappingsDir, MemoryMappingTree visitor) throws URISyntaxException, IOException, InterruptedException {
		if (!visitor.getDstNamespaces().contains("srg")) return true; // worthless without srg mappings

		String mcpVersion = null;

		try {
			HttpResponse<InputStream> response = HttpUtil.makeRequest(HttpUtil.toUri("maven.minecraftforge.net", "/de/oceanlabs/mcp/mcp_snapshot/maven-metadata.xml"));

			if (response.statusCode() != 200) {
				response.body().close();
				return false;
			}

			try (InputStream is = response.body()) {
				Element element = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(is).getDocumentElement();
				element = (Element) element.getElementsByTagName("versioning").item(0);
				element = (Element) element.getElementsByTagName("versions").item(0);

				NodeList list = element.getElementsByTagName("version");

				for (int i = 0, max = list.getLength(); i < max; i++) {
					String version = list.item(i).getTextContent();

					if (version.endsWith(mcVersion)
							&& version.length() > mcVersion.length()
							&& version.charAt(version.length() - mcVersion.length() - 1) == '-') {
						mcpVersion = version;
						break;
					}
				}
			} catch (ParserConfigurationException | SAXException e) {
				LOGGER.warn("Error parsing mcp_snapshot maven metadata", e);
				return false;
			}
		} catch (IOException e) {
			HttpUtil.logError("fetching/parsing mcp_snapshot maven metadata failed", e, LOGGER);
			return false;
		}

		if (mcpVersion == null) {
			LOGGER.warn("can't find mcp_snapshot for {}", mcVersion);
			return true;
		}

		if (mcpVersion.indexOf('/') >= 0) throw new IllegalArgumentException("invalid mcp_snapshot version: "+mcpVersion);

		Path file = mappingsDir.resolve("mcp").resolve("mcp_snapshot-%s.zip".formatted(mcpVersion));

		if (!Files.exists(file)) {
			if (!downloadMcpMappings(mcpVersion, file)) {
				return false;
			}
		}

		if (Files.size(file) > 0) {
			Map<String, String> fieldMap = new Object2ObjectOpenHashMap<>(10000);
			Map<String, String> methodMap = new Object2ObjectOpenHashMap<>(10000);

			try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(file))) {
				ZipEntry entry;

				while ((entry = zis.getNextEntry()) != null) {
					String name = entry.getName();
					boolean isField = name.equals("fields.csv");

					if (isField || name.equals("methods.csv")) {
						Map<String, String> map = isField ? fieldMap : methodMap;
						BufferedReader reader = new BufferedReader(new InputStreamReader(zis, StandardCharsets.UTF_8));
						String line = reader.readLine();

						if (line == null || !line.startsWith("searge,name,")) {
							throw new IOException("invalid mcp csv: %s in %s".formatted(name, mcpVersion));
						}

						while ((line = reader.readLine()) != null) {
							line = line.trim();
							if (line.isEmpty()) continue;

							int srgEnd = line.indexOf(',');
							int mcpEnd = line.indexOf(',', srgEnd + 1);
							if (srgEnd < 0 || mcpEnd < 0) throw new IOException("invalid mcp csv: %s in %s".formatted(name, mcpVersion));

							map.put(line.substring(0, srgEnd), line.substring(srgEnd + 1, mcpEnd));
						}
					}
				}
			}

			if (!fieldMap.isEmpty() || !methodMap.isEmpty()) {
				visitor.visitHeader();
				visitor.visitNamespaces(visitor.getSrcNamespace(), List.of("mcp"));
				int srgNs = visitor.getNamespaceId("srg");
				visitor.visitContent();

				for (ClassMapping cls : visitor.getClasses()) {
					String clsMcpName = cls.getName(srgNs);

					visitor.visitClass(cls.getSrcName());
					if (clsMcpName != null) visitor.visitDstName(MappedElementKind.CLASS, 0, clsMcpName);
					visitor.visitElementContent(MappedElementKind.CLASS);

					for (FieldMapping field : cls.getFields()) {
						String srgName = field.getName(srgNs);
						if (srgName == null) continue;

						String mcpName = fieldMap.getOrDefault(srgName, srgName);

						visitor.visitField(field.getSrcName(), field.getSrcDesc());
						visitor.visitDstName(MappedElementKind.FIELD, 0, mcpName);
						visitor.visitElementContent(MappedElementKind.FIELD);
					}

					for (MethodMapping method : cls.getMethods()) {
						String srgName = method.getName(srgNs);
						if (srgName == null) continue;

						String mcpName = methodMap.getOrDefault(srgName, srgName);

						visitor.visitMethod(method.getSrcName(), method.getSrcDesc());
						visitor.visitDstName(MappedElementKind.METHOD, 0, mcpName);
						visitor.visitElementContent(MappedElementKind.METHOD);
					}
				}

				visitor.visitEnd();
			}
		}

		return true;
	}

	private static boolean downloadMcpMappings(String mcpVersion, Path out) throws URISyntaxException, InterruptedException {
		try {
			HttpResponse<InputStream> response = HttpUtil.makeRequest(HttpUtil.toUri("maven.minecraftforge.net", getMavenPath("de.oceanlabs.mcp:mcp_snapshot:%s".formatted(mcpVersion), null, "zip")));

			if (response.statusCode() != 200) {
				response.body().close();
				return false;
			}

			try (InputStream is = response.body()) {
				Files.createDirectories(out.toAbsolutePath().getParent());
				Files.copy(is, out);
			}

			return true;
		} catch (IOException e) {
			HttpUtil.logError("fetching/saving mcp mappings %s failed".formatted(mcpVersion), e, LOGGER);
			return false;
		}
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

	/**
	 * Prevents adding entirely new classes, field or methods.
	 *
	 * <p>This takes care of mojmap and srg mapping elements that were stripped.
	 */
	private static class NewElementBlocker extends ForwardingMappingVisitor {
		public NewElementBlocker(MappingVisitor next, MappingTree tree) {
			super(next);

			this.tree = tree;
		}

		@Override
		public boolean visitClass(String srcName) throws IOException {
			cls = tree.getClass(srcName);
			if (cls == null) return false;

			return super.visitClass(srcName);
		}

		@Override
		public boolean visitField(String srcName, String srcDesc) throws IOException {
			if (cls.getField(srcName, srcDesc) == null) return false;

			return super.visitField(srcName, srcDesc);
		}

		@Override
		public boolean visitMethod(String srcName, String srcDesc) throws IOException {
			if (cls.getMethod(srcName, srcDesc) == null) return false;

			return super.visitMethod(srcName, srcDesc);
		}

		private final MappingTree tree;
		private ClassMapping cls;
	}
}
