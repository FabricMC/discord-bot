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
import java.util.Locale;

import org.jetbrains.annotations.Nullable;
import org.spongepowered.configurate.ConfigurationNode;
import org.spongepowered.configurate.serialize.SerializationException;
import org.spongepowered.configurate.serialize.TypeSerializer;

public final class ColorSerializer implements TypeSerializer<Color> {
	public static final Color BLURPLE = Color.decode("#7289DA");
	public static final Color FABRIC = Color.decode("#DBD0B4");
	public static final Color NEGATIVE = Color.decode("#e74c3c");
	public static final Color POSITIVE = Color.decode("#2ecc71");

	@Override
	public Color deserialize(Type type, ConfigurationNode node) throws SerializationException {
		final int value = node.getInt(Integer.MIN_VALUE);

		if (value != Integer.MIN_VALUE) {
			final String colorName = node.getString();

			if (colorName == null) {
				throw new SerializationException(node.path(), Color.class, "Failed to coerce color value to string!");
			}

			return switch (colorName.toLowerCase(Locale.ROOT)) {
			case "blurple" -> BLURPLE;
			case "fabric" -> FABRIC;
			case "negative" -> NEGATIVE;
			case "positive" -> POSITIVE;
			default -> throw new SerializationException(node.path(), Color.class, "Unsupported string color %s".formatted(colorName));
			};
		}

		return new Color(value);
	}

	@Override
	public void serialize(Type type, @Nullable Color obj, ConfigurationNode node) throws SerializationException {
		throw new SerializationException("Serialization not supported!");
	}
}
