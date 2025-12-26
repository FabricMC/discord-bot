package net.fabricmc.discord.ioimpl.jda;

import net.dv8tion.jda.api.entities.ISnowflake;
import net.dv8tion.jda.api.entities.emoji.CustomEmoji;
import net.dv8tion.jda.api.entities.emoji.Emoji.Type;

import net.fabricmc.discord.io.Emoji;

public class EmojiImpl implements Emoji {
	private final net.dv8tion.jda.api.entities.emoji.Emoji wrapped;
	private final DiscordImpl discord;

	EmojiImpl(net.dv8tion.jda.api.entities.emoji.Emoji wrapped, DiscordImpl discord) {
		this.wrapped = wrapped;
		this.discord = discord;
	}

	@Override
	public DiscordImpl getDiscord() {
		return discord;
	}

	@Override
	public long getId() {
		if (wrapped instanceof ISnowflake s) {
			return s.getIdLong();
		} else {
			return -1;
		}
	}

	@Override
	public boolean isCustom() {
		return wrapped.getType() == Type.CUSTOM;
	}

	@Override
	public String getName() {
		return wrapped.getName();
	}

	@Override
	public boolean isAnimated() {
		return wrapped instanceof CustomEmoji e ? e.isAnimated() : false;
	}

	static EmojiImpl wrap(net.dv8tion.jda.api.entities.emoji.Emoji emoji, DiscordImpl discord) {
		if (emoji == null) return null;

		return new EmojiImpl(emoji, discord);
	}

	static net.dv8tion.jda.api.entities.emoji.Emoji unwrap(Emoji emoji) {
		if (emoji instanceof EmojiImpl e) {
			return e.wrapped;
		} else if (emoji.isCustom()) {
			return net.dv8tion.jda.api.entities.emoji.Emoji.fromCustom(emoji.getName(), emoji.getId(), emoji.isAnimated());
		} else {
			return net.dv8tion.jda.api.entities.emoji.Emoji.fromUnicode(emoji.getName());
		}
	}

	net.dv8tion.jda.api.entities.emoji.Emoji unwrap() {
		return wrapped;
	}
}
