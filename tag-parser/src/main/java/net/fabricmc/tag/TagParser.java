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

package net.fabricmc.tag;

import java.awt.Color;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashSet;
import java.util.Set;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.javacord.api.entity.message.embed.EmbedBuilder;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.configurate.ConfigurateException;
import org.spongepowered.configurate.serialize.TypeSerializerCollection;
import org.spongepowered.configurate.yaml.YamlConfigurationLoader;

import net.fabricmc.tag.serialize.ColorSerializer;
import net.fabricmc.tag.serialize.EmbedBuilderSerializer;
import net.fabricmc.tag.serialize.TagFrontMatterSerializer;

public final class TagParser {
	public static final TypeSerializerCollection SERIALIZERS = TypeSerializerCollection.builder()
			.registerAll(TypeSerializerCollection.defaults())
			.register(Color.class, new ColorSerializer())
			.register(EmbedBuilder.class, new EmbedBuilderSerializer())
			.register(TagFrontMatter.class, new TagFrontMatterSerializer())
			.build();

	/**
	 * This can be run by github actions to validate tags before merge.
	 */
	public static void main(String[] args) throws IOException {
		final Logger logger = LogManager.getLogger("TagParser");
		final Path dir = Paths.get("").toAbsolutePath();

		logger.info("Checking tags in {}", dir);

		final TagLoadResult result = loadTags(logger, dir);

		logger.info("Loaded: {}, Malformed: {}", result.loadedTags().size(), result.malformedTags().size());

		if (!result.malformedTags().isEmpty()) {
			logger.error("Failed to parse all tags. The following tags are malformed:");

			for (String malformedTag : result.malformedTags()) {
				logger.error(malformedTag);
			}

			System.exit(1);
		}
	}

	public static TagLoadResult loadTags(Logger logger, Path tagsDir) throws IOException {
		// State logic
		Set<TagEntry> loadedTags = new HashSet<>();
		Set<String> malformedTags = new HashSet<>();

		// Visit the cloned repo for tags and deserialize them
		Files.walkFileTree(tagsDir, new SimpleFileVisitor<>() {
			@Override
			public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
				if (file.getFileName().toString().endsWith(".ytag")) {
					logger.info("Loading tag {}", file);

					final String rawFileName = file.getFileName().toString();
					final int extensionStart = rawFileName.lastIndexOf(".");
					final String tagName = rawFileName.substring(0, extensionStart);

					// JDK impl autocloses the readString call
					final String fileAsString = Files.readString(file, StandardCharsets.UTF_8);
					String yamlContent;
					@Nullable String messageContent = null;

					final String[] split = fileAsString.split("\\n---\\n");

					if (split.length == 2) {
						logger.error(split[0]);
						yamlContent = split[0];
						messageContent = split[split.length - 1];
					} else {
						// This SHOULD be an alias tag but it could be bad syntax. We will see
						yamlContent = fileAsString;
					}

					try {
						// Read the tag's yml front matter
						final TagFrontMatter frontMatter = YamlConfigurationLoader.builder()
								.defaultOptions(options -> options.serializers(SERIALIZERS))
								.buildAndLoadString(yamlContent)
								.get(TagFrontMatter.class);

						loadedTags.add(new TagEntry(tagName, frontMatter, messageContent));
					} catch (ConfigurateException e) {
						logger.error("Failed to load tag \"{}\" due to invalid front matter!", tagName, e);
						malformedTags.add(tagName);
					}
				}

				return FileVisitResult.CONTINUE;
			}
		});

		return new TagLoadResult(loadedTags, malformedTags);
	}

	record TagEntry(String name, TagFrontMatter frontMatter, @Nullable String messageContent) {
	}
}
