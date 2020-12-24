/**
 * Copyright (c) 2020 FabricMC
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

package net.fabricmc.discord.bot.util;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * Utilities for working with snowflakes.
 * @see <a href="https://discord.com/developers/docs/reference#snowflakes">Discord API documentation</a>
 */
public final class Snowflakes {
	// https://discord.com/developers/docs/reference#snowflakes-snowflake-id-format-structure-left-to-right
	public static final long DISCORD_EPOCH = 1420070400000L;

	/**
	 * Gets the creation time of a snowflake as a local date time.
	 *
	 * @param snowflake the snowflake
	 * @return the local date time to snowflake was created at.
	 */
	public static LocalDateTime getCreationTime(long snowflake) {
		// https://discord.com/developers/docs/reference#convert-snowflake-to-datetime
		final long epochMilli = (snowflake >> 22) + DISCORD_EPOCH;
		return Instant.ofEpochMilli(epochMilli).atZone(ZoneId.systemDefault()).toLocalDateTime();
	}

	private Snowflakes() {
	}
}
