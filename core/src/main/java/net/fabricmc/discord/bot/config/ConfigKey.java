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

import java.util.Objects;

public final class ConfigKey<V> {
	private final String name;
	private final ValueSerializer<V> valueSerializer;

	public ConfigKey(String name, ValueSerializer<V> valueSerializer) {
		Objects.requireNonNull(name, "Config key name cannot be null");
		Objects.requireNonNull(valueSerializer, "Value serializer cannot be null");

		this.name = name;
		this.valueSerializer = valueSerializer;
	}

	public String name() {
		return this.name;
	}

	public ValueSerializer<V> valueSerializer() {
		return this.valueSerializer;
	}
}
