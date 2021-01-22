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

package net.fabricmc.discord.bot.config;

/**
 * Used to serialize and deserialize config values
 *
 * @param <V> the value type
 */
public interface ValueSerializer<V> {
	/**
	 * Deserializes a string into a value of a specific type.
	 * Implementations may throw an exception if the serialized value is invalid or does not meet certain constraints.
	 *
	 * @param serialized the serialized value
	 * @return the deserialized value
	 * @throws IllegalArgumentException if there were any issues parsing the serialized
	 */
	V deserialize(String serialized) throws IllegalArgumentException;

	/**
	 * Serializes a value into a string for storage.
	 * Implementations may throw an exception if the value is invalid or does not meet certain constraints.
	 *
	 * @param value the value
	 * @return the serialized value
	 * @throws IllegalArgumentException if there were any issues serializing the value
	 */
	String serialize(V value) throws IllegalArgumentException;
}
