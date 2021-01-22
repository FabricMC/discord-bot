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
 * Builtin value serializers that may be used to store configuration values.
 */
public final class ValueSerializers {
	/**
	 * A value serializer which parses integers
	 */
	public static final ValueSerializer<Integer> INT = new Int();

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

	/**
	 * A value serializer which parses longs.
	 *
	 * <p>This value serializer may be useful for storing a snowflake to refer to a user, channel or guild
	 * @see net.fabricmc.discord.bot.util.Snowflakes
	 */
	public static final ValueSerializer<Long> LONG = new LongSerializer();

	private ValueSerializers() {
	}

	private static final class Int implements ValueSerializer<Integer> {
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
}
