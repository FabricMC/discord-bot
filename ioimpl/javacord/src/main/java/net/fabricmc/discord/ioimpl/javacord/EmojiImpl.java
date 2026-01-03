/*
 * Copyright (c) 2026 FabricMC
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

package net.fabricmc.discord.ioimpl.javacord;

import org.javacord.api.entity.emoji.CustomEmoji;

import net.fabricmc.discord.io.Emoji;

public class EmojiImpl implements Emoji {
	private final org.javacord.api.entity.emoji.Emoji wrapped;
	private final DiscordImpl discord;

	EmojiImpl(org.javacord.api.entity.emoji.Emoji wrapped, DiscordImpl discord) {
		this.wrapped = wrapped;
		this.discord = discord;
	}

	@Override
	public DiscordImpl getDiscord() {
		return discord;
	}

	@Override
	public long getId() {
		CustomEmoji res = wrapped.asCustomEmoji().orElse(null);

		return res != null ? res.getId() : -1;
	}

	@Override
	public boolean isCustom() {
		return wrapped.isCustomEmoji();
	}

	@Override
	public String getName() {
		return wrapped.asUnicodeEmoji().orElse(wrapped.asCustomEmoji().orElseThrow().getName());
	}

	@Override
	public boolean isAnimated() {
		CustomEmoji res = wrapped.asCustomEmoji().orElse(null);

		return res != null && res.isAnimated();
	}

	static EmojiImpl wrap(org.javacord.api.entity.emoji.Emoji emoji, DiscordImpl discord) {
		if (emoji == null) return null;

		return new EmojiImpl(emoji, discord);
	}

	org.javacord.api.entity.emoji.Emoji unwrap() {
		return wrapped;
	}
}
