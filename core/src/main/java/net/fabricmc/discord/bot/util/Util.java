package net.fabricmc.discord.bot.util;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

public class Util {
	public static <T> Set<T> newIdentityHashSet() {
		return Collections.newSetFromMap(new IdentityHashMap<>());
	}
}
