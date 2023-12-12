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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Builtin value serializers that may be used to store configuration values.
 */
public final class ValueSerializers {
	/**
	 * A value serializer which parses integers
	 */
	public static final ValueSerializer<Integer> INT = new IntSerializer();
	public static final ValueSerializer<List<Integer>> INT_LIST = new ListSerializer<>(INT);

	/**
	 * A value serializer which parses integers, while validating the integer is within a specific range.
	 *
	 * @param lowerBound the lower bound of the allowed values
	 * @param upperBound the upper bound of the allowed values
	 * @return a new value serializer
	 */
	public static ValueSerializer<Integer> rangedInt(int lowerBound, int upperBound) {
		return new RangedInt(lowerBound, upperBound);
	}

	/**
	 * A value serializer which parses strings.
	 */
	public static final ValueSerializer<String> STRING = new StringSerializer();
	public static final ValueSerializer<List<String>> STRING_LIST = new ListSerializer<>(STRING);

	/**
	 * A value serializer which parses longs.
	 *
	 * <p>This value serializer may be useful for storing a snowflake to refer to a user, channel or guild
	 * @see net.fabricmc.discord.bot.util.Snowflakes
	 */
	public static final ValueSerializer<Long> LONG = new LongSerializer();
	public static final ValueSerializer<List<Long>> LONG_LIST = new ListSerializer<>(LONG);

	/**
	 * A value serializer which parses booleans, while treating invalid values as false.
	 *
	 * @see Boolean#parseBoolean(String)
	 */
	public static final ValueSerializer<Boolean> BOOLEAN = new BooleanSerializer();


	private ValueSerializers() {
	}

	private static final class IntSerializer implements ValueSerializer<Integer> {
		@Override
		public Integer deserialize(String serialized) throws IllegalArgumentException {
			try {
				return Integer.parseInt(serialized);
			} catch (NumberFormatException e) {
				throw new IllegalArgumentException(e);
			}
		}

		@Override
		public String serialize(Integer value) {
			return Integer.toString(value);
		}
	}

	private static final class RangedInt implements ValueSerializer<Integer> {
		private final int lowerBound;
		private final int upperBound;

		RangedInt(int lowerBound, int upperBound) {
			this.lowerBound = lowerBound;
			this.upperBound = upperBound;
		}

		@Override
		public Integer deserialize(String serialized) throws IllegalArgumentException {
			try {
				final int value = Integer.parseInt(serialized);

				if (this.lowerBound > value) {
					throw new IllegalArgumentException("Int value is below minimum bound of %s, the value was %s".formatted(this.lowerBound, value));
				}

				if (this.upperBound < value) {
					throw new IllegalArgumentException("Int value is above maximum bound of %s, the value was %s".formatted(this.upperBound, value));
				}

				return value;
			} catch (NumberFormatException e) {
				throw new IllegalArgumentException(e);
			}
		}

		@Override
		public String serialize(Integer value) throws IllegalArgumentException {
			if (this.lowerBound > value) {
				throw new IllegalArgumentException("Int value is below minimum bound of %s, the value was %s".formatted(this.lowerBound, value));
			}

			if (this.upperBound < value) {
				throw new IllegalArgumentException("Int value is above maximum bound of %s, the value was %s".formatted(this.upperBound, value));
			}

			return Integer.toString(value);
		}
	}

	private static final class StringSerializer implements ValueSerializer<String> {
		@Override
		public String deserialize(String serialized) throws IllegalArgumentException {
			return serialized;
		}

		@Override
		public String serialize(String value) {
			return value;
		}
	}

	private static final class LongSerializer implements ValueSerializer<Long> {
		@Override
		public Long deserialize(String serialized) throws IllegalArgumentException {
			try {
				return Long.parseLong(serialized);
			} catch (NumberFormatException e) {
				throw new IllegalArgumentException(e);
			}
		}

		@Override
		public String serialize(Long value) throws IllegalArgumentException {
			return Long.toString(value);
		}
	}

	private static final class ListSerializer<V> implements ValueSerializer<List<V>> {
		public ListSerializer(ValueSerializer<V> elementSerializer) {
			this.elementSerializer = elementSerializer;
		}

		@Override
		public List<V> deserialize(String serialized) throws IllegalArgumentException {
			if (serialized.isEmpty()) return Collections.emptyList();

			List<V> ret = new ArrayList<>();
			int startPos = 0;
			int pos;

			while ((pos = serialized.indexOf(',', startPos)) >= 0) {
				ret.add(elementSerializer.deserialize(serialized.substring(startPos, pos)));
				startPos = pos + 1;
			}

			ret.add(elementSerializer.deserialize(serialized.substring(startPos)));

			return ret;
		}

		@Override
		public String serialize(List<V> value) throws IllegalArgumentException {
			if (value.isEmpty()) return "";

			StringBuilder ret = new StringBuilder();
			boolean first = true;

			for (V v : value) {
				if (first) {
					first = false;
				} else {
					ret.append(',');
				}

				ret.append(elementSerializer.serialize(v));
			}

			return ret.toString();
		}

		private final ValueSerializer<V> elementSerializer;
	}

	private static final class BooleanSerializer implements ValueSerializer<Boolean> {
		@Override
		public Boolean deserialize(String serialized) throws IllegalArgumentException {
			return Boolean.parseBoolean(serialized);
		}

		@Override
		public String serialize(Boolean value) {
			return Boolean.toString(value);
		}
	}
}
