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

package net.fabricmc.discord.bot.message;

import org.javacord.api.entity.message.MessageAuthor;

/**
 * Utilities for creating discord mentions.
 */
public final class Mentions {
	/**
	 * Takes a snowflake and turns it into the requried text to mention someone silently.
	 * The user mentioned in the snowflake is not pinged.
	 *
	 * @param snowflake the snowflake of the user
	 * @return the string formatted to contain a silent mention
	 */
	public static String createUserMention(long snowflake) {
		// <@!SNOWFLAKE>
		return "<@!%s>".formatted(snowflake);
	}

	/**
	 * @see Mentions#createUserMention(long)
	 */
	public static String createUserMention(MessageAuthor author) {
		return createUserMention(author.getId());
	}

	private Mentions() {
	}
}
