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

package net.fabricmc.discord.bot.command.mod;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.EnumMap;
import java.util.IdentityHashMap;
import java.util.Map;

import com.google.gson.stream.JsonReader;

import net.fabricmc.discord.bot.command.core.HelpCommand;

final class ActionDesc {
	private static final Map<ActionType, Map<Kind, String>> descs = new IdentityHashMap<>();
	private static final Map<Kind, String> genericDescs = new EnumMap<>(Kind.class);

	static {
		readDescs();
	}

	public static String getShort(ActionType type, boolean reversal) {
		return get(type, reversal ? Kind.SHORT_DISABLE : Kind.SHORT_ENABLE);
	}

	public static String getSecondPerson(ActionType type, boolean reversal) {
		return String.format(get(type, reversal ? Kind.SECOND_PERSON_DISABLE : Kind.SECOND_PERSON_ENABLE), getShort(type, reversal));
	}

	public static String getThirdPerson(ActionType type, boolean reversal, String target) {
		return String.format(get(type, reversal ? Kind.THIRD_PERSON_DISABLE : Kind.THIRD_PERSON_ENABLE), target, getShort(type, reversal));
	}

	private static String get(ActionType type, Kind kind) {
		String ret = descs.getOrDefault(type, genericDescs).get(kind);
		if (ret == null) ret = genericDescs.get(kind);

		return ret != null ? ret : "%s %s".formatted(kind.key, type.getId());
	}

	private static void readDescs() {
		try (JsonReader reader = new JsonReader(new InputStreamReader(HelpCommand.class.getClassLoader().getResourceAsStream("actiondesc.json"), StandardCharsets.UTF_8))) {
			reader.beginObject();

			while (reader.hasNext()) {
				String type = reader.nextName();
				Map<Kind, String> map;

				if (type.equals("generic")) {
					map = genericDescs;
				} else {
					map = new EnumMap<>(Kind.class);
					int pos = type.indexOf('.');
					if (pos <= 0 || pos == type.length() - 1) throw new IOException("invalid type: "+type);
					descs.put(ActionType.get(type.substring(0, pos), type.substring(pos + 1)), map);
				}

				reader.beginObject();

				while (reader.hasNext()) {
					map.put(Kind.get(reader.nextName()), reader.nextString());
				}

				reader.endObject();
			}

			reader.endObject();
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	private enum Kind {
		SHORT_ENABLE("enable"),
		SHORT_DISABLE("disable"),
		SECOND_PERSON_ENABLE("2p-enable"),
		SECOND_PERSON_DISABLE("2p-disable"),
		THIRD_PERSON_ENABLE("3p-enable"),
		THIRD_PERSON_DISABLE("3p-disable");

		public final String key;

		Kind(String key) {
			this.key = key;
		}

		static Kind get(String key) {
			for (Kind kind : values()) {
				if (kind.key.equals(key)) return kind;
			}

			throw new IllegalArgumentException("invalid action desc kind: "+key);
		}
	}
}
