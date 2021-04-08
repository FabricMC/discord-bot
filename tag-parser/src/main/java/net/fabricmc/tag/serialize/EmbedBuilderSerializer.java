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
			final ConfigurationNode valueNode = childEntry.getValue();

			switch (key) {
			case "text" -> text = valueNode.getString();
			case "iconUrl", "icon-url" -> iconUrl = valueNode.getString();
			default -> throw new SerializationException(valueNode.path(), EmbedBuilder.class, "Invalid field %s in embed footer".formatted(key));
			}
		}

		if (iconUrl != null) {
			builder.setFooter(text, iconUrl);
		} else {
			builder.setFooter(text);
		}
	}

	private void readImage(EmbedBuilder builder, ConfigurationNode node) throws SerializationException {
		for (Map.Entry<Object, ? extends ConfigurationNode> propertyEntry : node.childrenMap().entrySet()) {
			final String key = propertyEntry.getKey().toString();
			final ConfigurationNode valueNode = propertyEntry.getValue();

			switch (key) {
			case "url" -> builder.setImage(valueNode.getString());
			default -> throw new SerializationException(node.path(), EmbedBuilder.class, "Invalid image key %s in embed".formatted(key));
			}
		}
	}

	private void readAuthor(EmbedBuilder builder, ConfigurationNode node) throws SerializationException {
		String name = null;
		String url = null;
		String iconUrl = null;

		for (Map.Entry<Object, ? extends ConfigurationNode> propertyEntry : node.childrenMap().entrySet()) {
			final String key = propertyEntry.getKey().toString();
			final ConfigurationNode valueNode = propertyEntry.getValue();

			switch (key) {
			case "name" -> name = valueNode.getString();
			case "url" -> url = valueNode.getString();
			case "iconUrl" -> iconUrl = valueNode.getString();
			default -> throw new SerializationException(valueNode.path(), EmbedBuilder.class, "Invalid author key %s in embed".formatted(key));
			}
		}

		if (name == null) {
			throw new SerializationException(node.path(), String.class, "Embed author must have a name!");
		}

		builder.setAuthor(name, url, iconUrl);
	}

	private void readThumbnail(EmbedBuilder builder, ConfigurationNode node) throws SerializationException {
		for (Map.Entry<Object, ? extends ConfigurationNode> propertyEntry : node.childrenMap().entrySet()) {
			final String key = propertyEntry.getKey().toString();
			final ConfigurationNode valueNode = propertyEntry.getValue();

			switch (key) {
			case "url" -> builder.setThumbnail(valueNode.getString());
			default -> throw new SerializationException(node.path(), EmbedBuilder.class, "Invalid thumbnail key %s in embed".formatted(key));
			}
		}
	}

	private void readFields(EmbedBuilder builder, ConfigurationNode node) throws SerializationException {
		for (ConfigurationNode itemEntry : node.childrenList()) {
			String name = null;
			String value = null;
			boolean inline = false;

			for (Map.Entry<Object, ? extends ConfigurationNode> propertyEntry : itemEntry.childrenMap().entrySet()) {
				final String key = propertyEntry.getKey().toString();
				final ConfigurationNode valueNode = propertyEntry.getValue();

				switch (key) {
				case "name" -> name = valueNode.getString();
				case "value" -> value = valueNode.getString();
				case "inline" -> inline = valueNode.getBoolean();
				default -> throw new SerializationException(valueNode.path(), EmbedBuilder.class, "Invalid field key %s in embed".formatted(key));
				}
			}

			if (name == null) {
				throw new SerializationException(itemEntry.path(), String.class, "Embed field must have a name!");
			}

			if (value == null) {
				throw new SerializationException(itemEntry.path(), String.class, "Embed field must have a value!");
			}

			builder.addField(name, value, inline);
		}
	}

	@Override
	public void serialize(Type type, @Nullable EmbedBuilder obj, ConfigurationNode node) throws SerializationException {
		throw new SerializationException("Serialization not supported!");
	}
}
