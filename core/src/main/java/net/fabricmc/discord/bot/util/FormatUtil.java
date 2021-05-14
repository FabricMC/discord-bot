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

package net.fabricmc.discord.bot.util;

import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

import org.javacord.api.entity.server.Server;
import org.javacord.api.entity.user.User;

import net.fabricmc.discord.bot.DiscordBot;
import net.fabricmc.discord.bot.UserHandler;
import net.fabricmc.discord.bot.command.CommandContext;
import net.fabricmc.discord.bot.command.CommandException;

public final class FormatUtil {
	public static final DateTimeFormatter dateFormatter = DateTimeFormatter.ISO_LOCAL_DATE.withZone(ZoneOffset.UTC);
	public static final DateTimeFormatter dateTimeFormatter = DateTimeFormatter.ofPattern("EEE, d MMM y HH:mm:ss z", Locale.ENGLISH).withZone(ZoneOffset.UTC);

	public static long parseActionDurationMs(String duration, boolean requireDuration) throws CommandException {
		long durationMs;

		if (duration == null) {
			durationMs = 0;
		} else if (duration.equalsIgnoreCase("perm") || duration.equalsIgnoreCase("permanent")) {
			durationMs = -1;
		} else {
			durationMs = parseDurationMs(duration);
			if (durationMs < 0) throw new CommandException("Invalid duration");
		}

		if (durationMs == 0 && requireDuration) {
			throw new CommandException("Invalid zero duration");
		}

		return durationMs;
	}

	public static long parseDurationMs(String str) {
		int start = 0;
		int end = str.length();

		boolean empty = true;
		int lastQualifierIdx = 2; // pretend the prev qualifier was minutes (idx 2) to make it select seconds (idx 1) by default
		long ret = 0;

		mainLoop: while (start < end) {
			char c = str.charAt(start);

			// skip leading whitespace
			while (Character.isWhitespace(c)) {
				if (++start >= end) break mainLoop;
				c = str.charAt(start);
			}

			// read numeric part
			long accum = 0;

			if (c < '0' || c > '9') {
				return -1; // no numeric part
			} else {
				do {
					accum = accum * 10 + (c - '0');

					if (++start >= end) {
						c = 0;
					} else {
						c = str.charAt(start);
					}
				} while (c >= '0' && c <= '9');
			}

			// skip whitespace between number and potential qualifier
			while (c != 0 && Character.isWhitespace(c)) {
				if (++start >= end) {
					c = 0;
				} else {
					c = str.charAt(start);
				}
			}

			// read qualifier (determine its end pos)
			int qualStart = start;

			while (c != 0 && (c < '0' || c > '9') && !Character.isWhitespace(c)) {
				if (++start >= end) {
					c = 0;
				} else {
					c = str.charAt(start);
				}
			}

			// parse qualifier
			long mul;

			if (start == qualStart) { // no qualifier
				// use one less than the prev qualifier if possible, interprets e.g. 4h30 as 4h30m
				mul = durationLengthsMs[Math.max(lastQualifierIdx - 1, 0)];
			} else {
				mul = -1;
				int len = start - qualStart;

				qualLoop: for (int i = 0; i < durationQualifiers.length; i++) {
					for (String q : durationQualifiers[i]) {
						if (q.length() == len && str.startsWith(q, qualStart)) {
							lastQualifierIdx = i;
							mul = durationLengthsMs[i];
							break qualLoop;
						}
					}
				}

				if (mul < 0) return -1; // no valid qualifier
			}

			empty = false;
			ret += accum * mul;
		}

		return empty ? -1 : ret;
	}

	public static String formatDuration(long durationMs) {
		return formatDuration(durationMs, Integer.MAX_VALUE);
	}

	public static String formatDuration(long durationMs, int maxParts) {
		StringBuilder ret = new StringBuilder();

		if (durationMs < 0) {
			ret.append('-');
			durationMs = -durationMs;
		}

		while (durationMs > 0 && maxParts-- > 0) {
			int maxQualIdx = 0;

			for (int i = durationLengthsMs.length - 1; i > 0; i--) {
				if (durationLengthsMs[i] <= durationMs) {
					maxQualIdx = i;
					break;
				}
			}

			long currentMul = durationLengthsMs[maxQualIdx];
			String currentQual = durationQualifiers[maxQualIdx][0];

			long count = durationMs / currentMul;
			durationMs -= count * currentMul;

			ret.append(Long.toString(count));
			ret.append(currentQual);
		}

		if (ret.length() == 0 || ret.length() == 1 && ret.charAt(0) == '-') {
			return "0";
		} else {
			return ret.toString();
		}
	}

	private static final String[][] durationQualifiers = { {"ms"},
			{"s", "sec", "second", "seconds"}, {"m", "min", "minute", "minutes"}, {"h", "hour", "hours"},
			{"d", "D", "day", "days"}, {"w", "W", "week", "weeks"}, {"M", "mo", "month", "months"}, {"y", "Y", "year", "years"} };
	private static final long[] durationLengthsMs = { 1,
			1_000, 60_000L, 3_600_000L,
			86_400_000L,  604_800_000L, 2_592_000_000L, 31_536_000_000L };

	public static CharSequence formatUserList(List<User> targets) {
		StringBuilder ret = new StringBuilder();

		for (User user : targets) {
			ret.append('\n');
			ret.append(UserHandler.formatDiscordUser(user));
		}

		return ret;
	}

	public static CharSequence formatUserList(List<Long> targets, CommandContext context) {
		return formatUserList(targets, context.bot(), context.server());
	}

	public static CharSequence formatUserList(List<Long> targets, DiscordBot bot, Server server) {
		StringBuilder ret = new StringBuilder();

		for (long targetDiscordId : targets) {
			ret.append('\n');
			ret.append(bot.getUserHandler().formatDiscordUser(targetDiscordId, server));
		}

		return ret;
	}

	private static final String TO_ESCAPE = "*_~|<>`:@[]\\";
	private static final char ZERO_WIDTH_SPACE = '\u200b';

	public static String escape(String s, OutputType outputType, boolean emitBlock) {
		int len = s.length();

		switch (outputType) {
		case PLAIN -> {
			StringBuilder ret = null;
			int startPos = 0;

			for (int i = 0; i < len; i++) {
				char c = s.charAt(i);

				if (TO_ESCAPE.indexOf(c) >= 0) {
					if (ret == null) ret = new StringBuilder();
					ret.append(s, startPos, i);
					ret.append('\\');
					startPos = i;
				}
			}

			if (ret != null) {
				ret.append(s, startPos, len);

				return ret.toString();
			} else {
				return s;
			}
		}
		case INLINE_CODE -> {
			if (len == 0) return emitBlock ? "`"+ZERO_WIDTH_SPACE+"`" : ""+ZERO_WIDTH_SPACE;
			if (!s.contains("`")) return emitBlock ? "`%s`".formatted(s) : s;

			StringBuilder ret = new StringBuilder(len + 10);
			if (emitBlock) ret.append("``");

			if (s.charAt(0) == '`') {
				ret.append(ZERO_WIDTH_SPACE);
			}

			int startPos = 0;
			int pos;

			while ((pos = s.indexOf('`', startPos)) >= 0) {
				ret.append(s, startPos, pos);
				ret.append('`');

				if (++pos == len || s.charAt(pos) == '`') {
					ret.append(ZERO_WIDTH_SPACE);
				}

				startPos = pos;
			}

			ret.append(s, startPos, len);
			if (emitBlock) ret.append("``");

			return ret.toString();
		}
		case CODE -> {
			if (len == 0) return emitBlock ? "```"+ZERO_WIDTH_SPACE+"```" : ""+ZERO_WIDTH_SPACE;
			if (!s.contains("```") && !s.endsWith("`")) return emitBlock ? "```%s```".formatted(s) : s;

			StringBuilder ret = new StringBuilder(len + 10);
			if (emitBlock) ret.append("```");

			int startPos = 0;
			int pos;

			while ((pos = s.indexOf("```", startPos)) >= 0) {
				ret.append(s, startPos, pos);
				ret.append("``"+ZERO_WIDTH_SPACE);
				startPos = pos + 2;
			}

			ret.append(s, startPos, len);

			if (s.charAt(len - 1) == '`') {
				ret.append(ZERO_WIDTH_SPACE);
			}

			if (emitBlock) ret.append("```");

			return ret.toString();
		}
		default -> throw new IllegalArgumentException(outputType.toString());
		}
	}

	public enum OutputType {
		PLAIN, INLINE_CODE, CODE;
	}

	public static String capitalize(String s) {
		if (s.isEmpty()) return s;

		return Character.toUpperCase(s.charAt(0))+s.substring(1);
	}
}
