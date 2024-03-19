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

package net.fabricmc.tag.serialize;

import java.awt.Color;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import org.javacord.api.entity.message.embed.EmbedBuilder;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.configurate.ConfigurationNode;
import org.spongepowered.configurate.serialize.SerializationException;
import org.spongepowered.configurate.serialize.TypeSerializer;

import net.fabricmc.tag.TagFrontMatter;

public final class TagFrontMatterSerializer implements TypeSerializer<TagFrontMatter> {
	@Override
	public TagFrontMatter deserialize(Type type, ConfigurationNode node) throws SerializationException {
		final ConfigurationNode typeNode = node.node("type");

		if (typeNode.virtual()) {
			throw new SerializationException(node.path(), TagFrontMatter.class, "Tag requires a \"type\" field in front matter!");
		}

		final String tagType = typeNode.getString();

		if (tagType == null) {
			throw new SerializationException(typeNode.path(), String.class, "Failed to coerce tag type field to a string!");
		}

		return switch (tagType) {
		case "text" -> TagFrontMatter.TEXT;
		case "parameterized" -> deserializeParameterized(node);
		case "embed" -> deserializeEmbedTag(node);
		case "alias" -> deserializeAlias(node);
		default -> throw new SerializationException("Unsupported tag type \"%s\"!".formatted(tagType));
		};
	}

	private TagFrontMatter deserializeParameterized(ConfigurationNode node) throws SerializationException {
		final ConfigurationNode validatorsNode = node.node("validators");

		if (validatorsNode.virtual()) {
			throw new SerializationException("Parameterized tag requires a validator!");
		}

		final List<String> patterns = validatorsNode.getList(String.class);

		if (patterns == null || patterns.isEmpty()) {
			throw new SerializationException("Parameterized tag requires a validator!");
		}

		final List<Predicate<String>> validators = new ArrayList<>();

		for (String pattern : patterns) {
			try {
				validators.add(Pattern.compile(pattern).asMatchPredicate());
			} catch (PatternSyntaxException e) {
				throw new SerializationException(e);
			}
		}

		return new TagFrontMatter.ParameterizedText(validators);
	}

	private TagFrontMatter deserializeAlias(ConfigurationNode node) throws SerializationException {
		final ConfigurationNode targetNode = node.node("target");

		if (targetNode.virtual()) {
			throw new SerializationException("Alias tag requires a target!");
		}

		final String target = targetNode.getString();

		if (target == null || target.isEmpty()) {
			throw new SerializationException("Target had null value or was empty!");
		}

		return new TagFrontMatter.Alias(target);
	}

	private TagFrontMatter deserializeEmbedTag(ConfigurationNode node) throws SerializationException {
		final ConfigurationNode embedNode = node.node("embed");

		if (embedNode.virtual()) {
			throw new SerializationException(embedNode.path(), EmbedBuilder.class, "Embed tag requires an embed!");
		}

		@Nullable
		final EmbedBuilder embed = embedNode.get(EmbedBuilder.class);

		if (embed == null) {
			throw new SerializationException(embedNode.path(), EmbedBuilder.class, "Failed to parse embed!");
		}

		final ConfigurationNode colorNode = node.node("color");
		final ConfigurationNode colourNode = node.node("colour");

		// Current standard supports color outside of the embed object only.
		if (!colourNode.virtual() || !colourNode.virtual()) {
			// Try british spelling first so existing tags will work
			if (!colourNode.virtual()) {
				@Nullable
				final Color colour = colourNode.get(Color.class);

				if (colour != null) {
					embed.setColor(colour);
				}
			// American spelling
			} else if (!colorNode.virtual()) {
				@Nullable
				final Color color = colorNode.get(Color.class);

				if (color != null) {
					embed.setColor(color);
				}
			}
		}

		return new TagFrontMatter.Embed(embed);
	}

	@Override
	public void serialize(Type type, @Nullable TagFrontMatter obj, ConfigurationNode node) throws SerializationException {
		throw new SerializationException("Serialization not supported!");
	}
}
