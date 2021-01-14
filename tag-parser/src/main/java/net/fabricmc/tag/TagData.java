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

import org.javacord.api.entity.message.embed.EmbedBuilder;
import org.spongepowered.configurate.objectmapping.ConfigSerializable;

public interface TagData {
	String name();

	@ConfigSerializable
	record Text(String name, String text) implements TagData {
	}

	@ConfigSerializable
	record Alias(String name, String target) implements TagData {
	}

	@ConfigSerializable
	record Embed(String name, EmbedBuilder embed) implements TagData {
	}
}
