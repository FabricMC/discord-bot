/*
 * Copyright (c) 2021, 2024 FabricMC
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
import java.io.Reader;
import java.net.URISyntaxException;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import net.fabricmc.discord.bot.util.HttpUtil;
import net.fabricmc.mappingio.MappedElementKind;
import net.fabricmc.mappingio.MappingUtil;
import net.fabricmc.mappingio.MappingVisitor;
import net.fabricmc.mappingio.adapter.ForwardingMappingVisitor;
import net.fabricmc.mappingio.adapter.MappingNsRenamer;
import net.fabricmc.mappingio.format.srg.TsrgFileReader;
import net.fabricmc.mappingio.tree.MappingTree;
import net.fabricmc.mappingio.tree.MappingTree.ClassMapping;
import net.fabricmc.mappingio.tree.MappingTree.FieldMapping;
import net.fabricmc.mappingio.tree.MappingTree.MethodMapping;
import net.fabricmc.mappingio.tree.MappingTreeView;
import net.fabricmc.mappingio.tree.MemoryMappingTree;

final class ForgeHandler {
	private static final Logger LOGGER = LogManager.getLogger(ForgeHandler.class);

	static boolean retrieveSrgMappings(String mcVersion, Path mappingsDir, MemoryMappingTree tree) throws URISyntaxException, IOException, InterruptedException {
		if (mcVersion.indexOf('/') >= 0) throw new IllegalArgumentException("invalid mc version: "+mcVersion);

		Path file = mappingsDir.resolve("srg").resolve("srg-%s.tsrg".formatted(mcVersion));

		if (!Files.exists(file)
				|| Files.size(file) == 0 && System.currentTimeMillis() - Files.getLastModifiedTime(file).toMillis() > 2 * 3600 * 1000) { // cached as missing but older than 2h
			if (!downloadSrgMappings(mcVersion, file)) {
				return false;
			}
		}

		if (Files.size(file) > 0) {
			MappingVisitor visitor = tree;
			int mojmapId = tree.getNamespaceId("mojmap");

			if (mojmapId != MappingTreeView.NULL_NAMESPACE_ID) {
				visitor = new SrgMojmapSubstitution(visitor, tree, mojmapId);
			}

			try (Reader reader = Files.newBufferedReader(file)) {
				TsrgFileReader.read(reader, new MappingNsRenamer(new NewElementBlocker(visitor, tree),
						Map.of(MappingUtil.NS_SOURCE_FALLBACK, "official",
								MappingUtil.NS_TARGET_FALLBACK, "srg",
								"obf", "official")));
			}
		}

		return true;
	}

	private static boolean downloadSrgMappings(String mcVersion, Path out) throws URISyntaxException, IOException, InterruptedException {
		try {
			HttpResponse<InputStream> response = HttpUtil.makeRequest(HttpUtil.toUri("maven.minecraftforge.net", MappingRepository.getMavenPath("de.oceanlabs.mcp:mcp_config:%s".formatted(mcVersion), null, "zip")));

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

	static String retrieveMcpMappings(String mcVersion, Path mappingsDir, MemoryMappingTree visitor) throws URISyntaxException, IOException, InterruptedException {
		if (!visitor.getDstNamespaces().contains("srg")) return ""; // worthless without srg mappings

		String majorMinorMcVersion;
		int lastSegmentPos = mcVersion.lastIndexOf('.');

		if (lastSegmentPos >= 0 && mcVersion.lastIndexOf('.', lastSegmentPos - 2) > 0) { // at least a.b.
			majorMinorMcVersion = mcVersion.substring(0, lastSegmentPos); // a.b.c -> a.b
		} else {
			majorMinorMcVersion = null;
		}

		String mcpVersion = null;
		String mcpVersionFuzzy = null;

		try {
			HttpResponse<InputStream> response = HttpUtil.makeRequest(HttpUtil.toUri("maven.minecraftforge.net", "/de/oceanlabs/mcp/mcp_snapshot/maven-metadata.xml"));

			if (response.statusCode() != 200) {
				response.body().close();
				return null;
			}

			try (InputStream is = response.body()) {
				Element element = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(is).getDocumentElement();
				element = (Element) element.getElementsByTagName("versioning").item(0);
				element = (Element) element.getElementsByTagName("versions").item(0);

				NodeList list = element.getElementsByTagName("version");

				for (int i = 0, max = list.getLength(); i < max; i++) {
					String version = list.item(i).getTextContent();
					int pos = version.lastIndexOf('-') + 1;

					if (version.length() == mcVersion.length() + pos
							&& version.startsWith(mcVersion, pos)) {
						mcpVersion = version;
						break;
					} else if (majorMinorMcVersion != null
							&& mcpVersionFuzzy == null
							&& version.startsWith(majorMinorMcVersion, pos)) {
						mcpVersionFuzzy = version;
					}
				}
			} catch (ParserConfigurationException | SAXException e) {
				LOGGER.warn("Error parsing mcp_snapshot maven metadata", e);
				return null;
			}
		} catch (IOException e) {
			HttpUtil.logError("fetching/parsing mcp_snapshot maven metadata failed", e, LOGGER);
			return null;
		}

		if (mcpVersion == null) mcpVersion = mcpVersionFuzzy;

		if (mcpVersion == null) {
			LOGGER.warn("can't find mcp_snapshot for {}", mcVersion);
			return "";
		}

		if (mcpVersion.indexOf('/') >= 0) throw new IllegalArgumentException("invalid mcp_snapshot version: "+mcpVersion);

		Path file = mappingsDir.resolve("mcp").resolve("mcp_snapshot-%s.zip".formatted(mcpVersion));

		if (!Files.exists(file)) {
			if (!downloadMcpMappings(mcpVersion, file)) {
				return null;
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

		return mcpVersion;
	}

	private static boolean downloadMcpMappings(String mcpVersion, Path out) throws URISyntaxException, InterruptedException {
		try {
			HttpResponse<InputStream> response = HttpUtil.makeRequest(HttpUtil.toUri("maven.minecraftforge.net", MappingRepository.getMavenPath("de.oceanlabs.mcp:mcp_snapshot:%s".formatted(mcpVersion), null, "zip")));

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

	/**
	 * Replace the srg class id (e.g. net/minecraft/src/C_5303_) with the mojmap name as used at runtime.
	 *
	 * <p>This is only relevant for 1.17 and later.
	 */
	private static final class SrgMojmapSubstitution extends ForwardingMappingVisitor {
		public SrgMojmapSubstitution(MappingVisitor next, MappingTree tree, int mojmapId) {
			super(next);

			this.tree = tree;
			this.mojmapId = mojmapId;
		}

		@Override
		public void visitNamespaces(String srcNamespace, List<String> dstNamespaces) throws IOException {
			srgNs = dstNamespaces.indexOf("srg");

			super.visitNamespaces(srcNamespace, dstNamespaces);
		}

		@Override
		public boolean visitClass(String srcName) throws IOException {
			cls = tree.getClass(srcName);
			if (cls == null) return false;

			return super.visitClass(srcName);
		}

		@Override
		public void visitDstName(MappedElementKind targetKind, int namespace, String name) throws IOException {
			if (targetKind == MappedElementKind.CLASS
					&& namespace == srgNs
					&& name.startsWith("net/minecraft/src/C_")) {
				String newName = cls.getName(mojmapId);
				if (newName != null) name = newName;
			}

			super.visitDstName(targetKind, namespace, name);
		}

		private final MappingTree tree;
		private final int mojmapId;

		private int srgNs;
		private ClassMapping cls;
	}
}
