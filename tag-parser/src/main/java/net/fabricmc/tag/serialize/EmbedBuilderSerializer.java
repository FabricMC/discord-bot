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
import java.util.Map;

import org.javacord.api.entity.message.embed.EmbedBuilder;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.configurate.ConfigurationNode;
import org.spongepowered.configurate.serialize.SerializationException;
import org.spongepowered.configurate.serialize.TypeSerializer;

public final class EmbedBuilderSerializer implements TypeSerializer<EmbedBuilder> {
	@Override
	public EmbedBuilder deserialize(Type type, ConfigurationNode node) throws SerializationException {
		final EmbedBuilder builder = new EmbedBuilder();

		if (!node.isMap()) {
			throw new SerializationException("Embed must be an object!");
		}

		for (Map.Entry<Object, ? extends ConfigurationNode> childEntry : node.childrenMap().entrySet()) {
			final String key = childEntry.getKey().toString();
			final ConfigurationNode value = childEntry.getValue();

			switch (key) {
			case "title" -> builder.setTitle(value.getString());
			case "description" -> builder.setDescription(value.getString());
			case "url" -> builder.setUrl(value.getString());
			// Current standard uses british spelling also, so support both
			case "color", "colour" -> builder.setColor(value.get(Color.class));
			case "footer" -> this.readFooter(builder, value);
			case "image" -> this.readImage(builder, value);
			case "author" -> this.readAuthor(builder, value);
			case "thumbnail" -> this.readThumbnail(builder, value);
			case "fields" -> this.readFields(builder, value);
			default -> throw new SerializationException(node.path(), EmbedBuilder.class, "Invalid field %s in embed".formatted(key));
			}
		}

		return builder;
	}

	private void readFooter(EmbedBuilder builder, ConfigurationNode node) throws SerializationException {
		@Nullable String text = null;
		@Nullable String iconUrl = null;

		for (Map.Entry<Object, ? extends ConfigurationNode> childEntry : node.childrenMap().entrySet()) {
			final String key = childEntry.getKey().toString();
			final ConfigurationNode value = childEntry.getValue();

			switch (key) {
			case "text" -> text = value.getString();
			case "iconUrl", "icon-url" -> iconUrl = value.getString();
			default -> throw new SerializationException(node.path(), EmbedBuilder.class, "Invalid field %s in embed footer".formatted(key));
			}
		}

		if (iconUrl != null) {
			builder.setFooter(text, iconUrl);
		} else {
			builder.setFooter(text);
		}
	}

	private void readImage(EmbedBuilder builder, ConfigurationNode node) {
		// TODO
	}

	private void readAuthor(EmbedBuilder builder, ConfigurationNode node) {
		// TODO
	}

	private void readThumbnail(EmbedBuilder builder, ConfigurationNode node) {
		// TODO
	}

	private void readFields(EmbedBuilder builder, ConfigurationNode node) throws SerializationException {
		// Each field
		for (Map.Entry<Object, ? extends ConfigurationNode> childEntry : node.childrenMap().entrySet()) {
			final String key = childEntry.getKey().toString();
			final ConfigurationNode value = childEntry.getValue();

			// Field value/inlining
			for (Map.Entry<Object, ? extends ConfigurationNode> fieldEntry : value.childrenMap().entrySet()) {
				final String fieldEntryKey = fieldEntry.getKey().toString();
				final ConfigurationNode fieldEntryValue = fieldEntry.getValue();

				@Nullable String fieldValue = null;
				boolean inline = false;

				switch (fieldEntryKey) {
				case "value" -> fieldValue = fieldEntryValue.getString();
				case "inline" -> inline = fieldEntryValue.getBoolean();
				default -> throw new SerializationException(fieldEntryValue.path(), EmbedBuilder.class, "Invalid key %s in field entry with key %s".formatted(fieldEntryKey, key));
				}

				if (fieldValue == null) {
					throw new SerializationException(fieldEntryValue.path(), String.class, "Embed field %s must have a value!".formatted(key));
				}

				builder.addField(key, fieldValue, inline);
			}
		}
	}

	@Override
	public void serialize(Type type, @Nullable EmbedBuilder obj, ConfigurationNode node) throws SerializationException {
		throw new SerializationException("Serialization not supported!");
	}
}
