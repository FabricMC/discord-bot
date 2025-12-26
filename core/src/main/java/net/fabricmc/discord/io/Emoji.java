package net.fabricmc.discord.io;

public interface Emoji {
	Discord getDiscord();
	long getId(); // only present for custom
	boolean isCustom();
	String getName();
	boolean isAnimated();

	static Emoji fromUnicode(String str) {
		return new Emoji() {
			@Override
			public Discord getDiscord() {
				throw new UnsupportedOperationException();
			}

			@Override
			public long getId() {
				return -1;
			}

			@Override
			public boolean isCustom() {
				return false;
			}

			@Override
			public String getName() {
				return str;
			}

			@Override
			public boolean isAnimated() {
				return false;
			}
		};
	}
}
