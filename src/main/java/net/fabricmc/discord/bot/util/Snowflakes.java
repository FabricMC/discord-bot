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
