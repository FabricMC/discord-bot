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

package net.fabricmc.discord.bot.command;

import java.util.Iterator;
import java.util.concurrent.TimeUnit;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongList;
import it.unimi.dsi.fastutil.longs.LongLists;

import net.fabricmc.discord.bot.DiscordBot;

public final class CommandReplyTracker {
	private static final long MAX_AGE_SEC = 3600 * 6; // 6h

	private final Long2ObjectMap<Entry> map = new Long2ObjectOpenHashMap<>();

	public CommandReplyTracker(DiscordBot bot) {
		bot.getScheduledExecutor().scheduleAtFixedRate(this::cleanup, 30, 30, TimeUnit.MINUTES);
	}

	public synchronized void add(long commandMessageId, long replyMessageId) {
		Entry entry = map.get(commandMessageId);

		if (entry == null) {
			entry = new Entry(System.nanoTime(), new LongArrayList());
			map.put(commandMessageId, entry);
		}

		entry.replies().add(replyMessageId);
	}

	public synchronized LongList remove(long commandMessageId) {
		Entry ret = map.remove(commandMessageId);

		return ret != null ? ret.replies() : LongLists.emptyList();
	}

	private synchronized void cleanup() {
		long minTime = System.nanoTime() - MAX_AGE_SEC * 1_000_000_000L;

		for (Iterator<Entry> it = map.values().iterator(); it.hasNext(); ) {
			Entry entry = it.next();

			if (entry.time - minTime < 0) {
				it.remove();
			}
		}
	}

	private record Entry(long time, LongList replies) { }
}
