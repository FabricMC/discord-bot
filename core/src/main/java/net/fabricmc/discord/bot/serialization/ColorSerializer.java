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

package net.fabricmc.discord.bot.serialization;

import java.awt.Color;
import java.lang.reflect.Type;

import org.checkerframework.checker.nullness.qual.Nullable;
import org.spongepowered.configurate.ConfigurationNode;
import org.spongepowered.configurate.serialize.SerializationException;
import org.spongepowered.configurate.serialize.TypeSerializer;

final class ColorSerializer implements TypeSerializer<Color> {
	@Override
	public Color deserialize(Type type, ConfigurationNode node) throws SerializationException {
		if (node.isMap()) {
			if (node.node("r").virtual()) {
				throw new SerializationException("Color requires \"r\" field for red color value!");
			}

			if (node.node("g").virtual()) {
				throw new SerializationException("Color requires \"g\" field for green color value!");
			}

			if (node.node("b").virtual()) {
				throw new SerializationException("Color requires \"b\" field for blue color value!");
			}

			final int red = node.node("r").getInt();
			final int green = node.node("g").getInt();
			final int blue = node.node("b").getInt();

			return new Color(red, green, blue);
		}

		throw new SerializationException(node, type, "Color must be an object specifying \"r\", \"g\", \"b\" fields");
	}

	@Override
	public void serialize(Type type, @Nullable Color obj, ConfigurationNode node) throws SerializationException {
		throw new SerializationException("Serialization not supported!");
	}
}
