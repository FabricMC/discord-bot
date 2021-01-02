package net.fabricmc.tag;

import java.lang.reflect.Type;
import java.util.Objects;

import org.checkerframework.checker.nullness.qual.Nullable;
import org.spongepowered.configurate.ConfigurationNode;
import org.spongepowered.configurate.objectmapping.ConfigSerializable;
import org.spongepowered.configurate.serialize.SerializationException;
import org.spongepowered.configurate.serialize.TypeSerializer;

import net.fabricmc.discord.bot.message.EmbedTemplate;

public interface TagData {
	TypeSerializer<TagData> SERIALIZER = new TypeSerializer<>() {
		@Override
		public TagData deserialize(Type type, ConfigurationNode node) throws SerializationException {
			if (node.node("type").virtual()) {
				throw new SerializationException(node, type, "Type of tag must be specified!");
			}

			return switch (Objects.requireNonNull(node.node("type").getString())) {
			case "text" -> node.get(Text.class);
			case "embed" -> node.get(Embed.class);
			case "alias" -> node.get(Alias.class);
			default -> throw new SerializationException(node, type, "Unsupported type %s".formatted(node.node("type").getString()));
			};
		}

		@Override
		public void serialize(Type type, @Nullable TagData obj, ConfigurationNode node) throws SerializationException {
			throw new SerializationException("Serialization not supported!");
		}
	};

	String name();

	@ConfigSerializable
	record Text(String name, String text) implements TagData {
	}

	@ConfigSerializable
	record Alias(String name, String target) implements TagData {
	}

	@ConfigSerializable
	record Embed(String name, EmbedTemplate embed) implements TagData {
	}
}
