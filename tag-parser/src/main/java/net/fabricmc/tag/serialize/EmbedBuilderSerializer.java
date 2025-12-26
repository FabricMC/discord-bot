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

import org.jetbrains.annotations.Nullable;
import org.spongepowered.configurate.ConfigurationNode;
import org.spongepowered.configurate.serialize.SerializationException;
import org.spongepowered.configurate.serialize.TypeSerializer;

import net.fabricmc.discord.io.MessageEmbed;

public final class EmbedBuilderSerializer implements TypeSerializer<MessageEmbed.Builder> {
	@Override
	public MessageEmbed.Builder deserialize(Type type, ConfigurationNode node) throws SerializationException {
		final MessageEmbed.Builder builder = new MessageEmbed.Builder();

		if (!node.isMap()) {
			throw new SerializationException("Embed must be an object!");
		}

		for (Map.Entry<Object, ? extends ConfigurationNode> childEntry : node.childrenMap().entrySet()) {
			final String key = childEntry.getKey().toString();
			final ConfigurationNode value = childEntry.getValue();

			switch (key) {
			case "title" -> builder.title(value.getString());
			case "footer" -> this.readFooter(builder, value);
			case "image" -> this.readImage(builder, value);
			case "author" -> this.readAuthor(builder, value);
			case "thumbnail" -> this.readThumbnail(builder, value);
			case "fields" -> this.readFields(builder, value);
			default -> throw new SerializationException(node.path(), MessageEmbed.Builder.class, "Invalid field %s in embed".formatted(key));
			}
		}

		return builder;
	}

	private void readFooter(MessageEmbed.Builder builder, ConfigurationNode node) throws SerializationException {
		@Nullable String text = null;
		@Nullable String iconUrl = null;

		for (Map.Entry<Object, ? extends ConfigurationNode> childEntry : node.childrenMap().entrySet()) {
			final String key = childEntry.getKey().toString();
			final ConfigurationNode valueNode = childEntry.getValue();

			switch (key) {
			case "text" -> text = valueNode.getString();
			case "iconUrl", "icon-url" -> iconUrl = valueNode.getString();
			default -> throw new SerializationException(valueNode.path(), MessageEmbed.Builder.class, "Invalid field %s in embed footer".formatted(key));
			}
		}

		builder.footer(text, iconUrl);
	}

	private void readImage(MessageEmbed.Builder builder, ConfigurationNode node) throws SerializationException {
		for (Map.Entry<Object, ? extends ConfigurationNode> propertyEntry : node.childrenMap().entrySet()) {
			final String key = propertyEntry.getKey().toString();
			final ConfigurationNode valueNode = propertyEntry.getValue();

			switch (key) {
			case "url" -> builder.image(valueNode.getString());
			default -> throw new SerializationException(node.path(), MessageEmbed.Builder.class, "Invalid image key %s in embed".formatted(key));
			}
		}
	}

	private void readAuthor(MessageEmbed.Builder builder, ConfigurationNode node) throws SerializationException {
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
			default -> throw new SerializationException(valueNode.path(), MessageEmbed.Builder.class, "Invalid author key %s in embed".formatted(key));
			}
		}

		if (name == null) {
			throw new SerializationException(node.path(), String.class, "Embed author must have a name!");
		}

		builder.author(name, url, iconUrl);
	}

	private void readThumbnail(MessageEmbed.Builder builder, ConfigurationNode node) throws SerializationException {
		for (Map.Entry<Object, ? extends ConfigurationNode> propertyEntry : node.childrenMap().entrySet()) {
			final String key = propertyEntry.getKey().toString();
			final ConfigurationNode valueNode = propertyEntry.getValue();

			switch (key) {
			case "url" -> builder.thumbnail(valueNode.getString());
			default -> throw new SerializationException(node.path(), MessageEmbed.Builder.class, "Invalid thumbnail key %s in embed".formatted(key));
			}
		}
	}

	private void readFields(MessageEmbed.Builder builder, ConfigurationNode node) throws SerializationException {
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
				default -> throw new SerializationException(valueNode.path(), MessageEmbed.Builder.class, "Invalid field key %s in embed".formatted(key));
				}
			}

			if (name == null) {
				throw new SerializationException(itemEntry.path(), String.class, "Embed field must have a name!");
			}

			if (value == null) {
				throw new SerializationException(itemEntry.path(), String.class, "Embed field must have a value!");
			}

			builder.field(name, value, inline);
		}
	}

	@Override
	public void serialize(Type type, @Nullable MessageEmbed.Builder obj, ConfigurationNode node) throws SerializationException {
		throw new SerializationException("Serialization not supported!");
	}
}
