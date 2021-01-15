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

package net.fabricmc.discord.bot.command;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.fabricmc.discord.bot.command.UsageParser.FloatingArgNode;
import net.fabricmc.discord.bot.command.UsageParser.ListNode;
import net.fabricmc.discord.bot.command.UsageParser.Node;
import net.fabricmc.discord.bot.command.UsageParser.OrNode;
import net.fabricmc.discord.bot.command.UsageParser.PlainNode;
import net.fabricmc.discord.bot.command.UsageParser.VarNode;

public final class CommandParser {
	public static void main(String[] args) {
		UsageParser usageParser = new UsageParser();
		Node node = usageParser.parse("(any|block|be|blockentity|entity) (class <class> | id <id>) [<dimId>] [--ticking] [--unloaded] [--countOnly | --chunkCounts] [--filter[=<x>]] [--clear]", false);
		System.out.printf("root node: %s%n", node);

		String input = "be id minecraft:chest nether --unloaded --countOnly --filter=test --clear";
		//String input = "be id minecraft:chest nether --unloaded --chunkCounts --filter=test --clear";
		Map<String, String> result = new LinkedHashMap<>();
		CommandParser cmdParser = new CommandParser();
		boolean rval = cmdParser.parse(input, node, result);

		System.out.printf("res: %b: %s%n", rval, result);
	}

	public boolean parse(CharSequence input, Node node, Map<String, String> out) {
		return parse(input, 0, input.length(), node, out);
	}

	/**
	 * Parse and validate a command parameter string into a map.
	 *
	 * <p>The input will split into tokens through whitespace unless escaped or enclosed in single or double quotes.
	 * Supported escape sequences are \r, \n, \t and \b with their usual Java meaning. Flag keys (--x) may not use
	 * quotes and don't tolerate whitespace between the first - and the end of their value.
	 *
	 * <p>Output map keying rules based on usage string element:
	 * <ul>
	 * <li> variable ({@literal <x>}): variable name (x)
	 * <li> floating arg (--x): flag key (x)
	 * <li> plain arg (x): unnamed_y where y = yth position dependent token, zero based
	 * </ul>
	 *
	 * @param input command parameter string
	 * @param inputStart start index in the command parameter string
	 * @param inputEnd end index in command parameter string (exclusive)
	 * @param node command usage tree's root Node as obtained from UsageParser.parse
	 * @param out map capturing parsed parameters as described above
	 * @return true whether the input was meeting the usage requirements and parsed successfully (wip)
	 * @throws IllegalArgumentException if the input has incorrect syntax (wip)
	 */
	public boolean parse(CharSequence input, int inputStart, int inputEnd, Node node, Map<String, String> out) {
		this.input = input;
		this.tokenCount = 0;
		floatingArgs.clear();

		for (int i = inputStart; i < inputEnd; i++) {
			char c = input.charAt(i);

			if (c == '"' || c == '\'') {
				int end = findBlockEndDelim(input, i + 1, inputEnd, c);
				if (end < 0) throw new IllegalArgumentException("unterminated "+c);

				addToken(i + 1, end);
				i = end;
			} else if (c == '-' && i + 1 < inputEnd && input.charAt(i + 1) == '-') {
				int start = i + 2;
				int end = start;

				while (end < inputEnd && !Character.isWhitespace(c = input.charAt(end)) && c != '=') {
					end++;
				}

				if (end == start) throw new IllegalArgumentException("-- not followed by key");

				String key = input.subSequence(start, end).toString();
				String value;

				if (c == '=') {
					start = end + 1;

					if (end < inputEnd && ((c = input.charAt(end + 1)) == '"' || c == '\'')) {
						start++;
						end = findBlockEndDelim(input, start, inputEnd, c);
						if (end < 0) throw new IllegalArgumentException("unterminated "+c);
					} else {
						end = findBlockEndWhitespace(input, start, inputEnd);
					}

					value = getValue(start, end);
				} else {
					value = null;
				}

				System.out.printf("new flarg: %s = %s%n", key, value);
				floatingArgs.put(key, value);
				i = end;
			} else if (!Character.isWhitespace(c)) {
				int end = findBlockEndWhitespace(input, i + 1, inputEnd);
				addToken(i, end);
				i = end;
			}
		}

		capturedArgsTmp.clear();
		allowedFloatingArgsTmp.clear();

		boolean ret = processNode(node, 0, true, capturedArgsTmp, allowedFloatingArgsTmp) >= 0;

		if (ret) {
			for (int i = 0; i < capturedArgsTmp.size(); i += 2) {
				String key = capturedArgsTmp.get(i);
				if (key == null) key = String.format("unnamed_%d", i >>> 1);
				String value = capturedArgsTmp.get(i + 1);

				out.put(key, value);
			}

			out.putAll(floatingArgs);
		}

		this.input = null;

		return ret;
	}

	private static int findBlockEndWhitespace(CharSequence s, int start, int end) {
		char c;

		while (start < end && !Character.isWhitespace(c = s.charAt(start))) {
			start++;
			if (c == '\\') start++;
		}

		return Math.min(start, end); // start could be beyond end due to trailing \
	}

	private static int findBlockEndDelim(CharSequence s, int start, int end, char endChar) {
		char c;

		while (start < end && (c = s.charAt(start)) != endChar) {
			start++;
			if (c == '\\') start++;
		}

		return start < end ? start : -1;
	}

	private void addToken(int start, int end) {
		if (tokenCount == tokens.length) tokens = Arrays.copyOf(tokens, tokens.length * TOKEN_STRIDE);
		tokens[tokenCount++] = start;
		tokens[tokenCount++] = end;
	}

	private int processNode(Node node, int token, boolean last, List<String> capturedArgs, List<String> allowedFloatingArgs) {
		// TOOD: repeat
		boolean matched;

		if (node instanceof FloatingArgNode) {
			FloatingArgNode faNode = (FloatingArgNode) node;

			if (floatingArgs.containsKey(faNode.key)) {
				String value = floatingArgs.get(faNode.key);

				if (value == null) {
					if (faNode.value != null && !faNode.value.isOptional()) {
						return -1; // missing value
					}
				} else if (faNode.value == null) {
					return -1; // excess value
				} else {
					// TODO: check if value is compliant with whatever faNode.value requires
				}

				allowedFloatingArgs.add(faNode.key);
				matched = true;
			} else {
				matched = false;
			}
		} else if (node instanceof ListNode) {
			ListNode list = (ListNode) node;
			int initialCapturedArgsSize = capturedArgs.size();
			int initialAllowedFloatingArgsSize = allowedFloatingArgs.size();
			matched = true;

			for (int i = 0, max = list.size(); i < max; i++) {
				int newToken = processNode(list.get(i), token, last && i + 1 == max, capturedArgs, allowedFloatingArgs);

				if (newToken < 0) {
					// undo the whole list node, for when it gets skipped entirely as optional
					trimList(capturedArgs, initialCapturedArgsSize);
					trimList(allowedFloatingArgs, initialAllowedFloatingArgsSize);
					matched = false;
					break;
				} else {
					token = newToken;
				}
			}
		} else if (node instanceof OrNode) {
			OrNode orNode = (OrNode) node;
			int initialCapturedArgsSize = capturedArgs.size();
			int initialAllowedFloatingArgsSize = allowedFloatingArgs.size();

			for (Node option : orNode) {
				int newToken = processNode(option, token, last, capturedArgs, allowedFloatingArgs);

				if (newToken >= 0) {
					return newToken; // FIXME: later options need to be preserved for backtracking to them in case the current one fails later (for niche cases..)
				} else {
					trimList(capturedArgs, initialCapturedArgsSize);
					trimList(allowedFloatingArgs, initialAllowedFloatingArgsSize);
				}
			}

			matched = false;
		} else if (token >= tokenCount) {
			matched = false;
		} else if (node instanceof PlainNode) {
			PlainNode plainNode = (PlainNode) node;

			if (plainNode.content.equals(getValue(token))) {
				capturedArgs.add(null);
				capturedArgs.add(plainNode.content);

				token += TOKEN_STRIDE;
				matched = true;
			} else {
				matched = false;
			}
		} else if (node instanceof VarNode) {
			VarNode varNode = (VarNode) node;

			capturedArgs.add(varNode.variable);
			capturedArgs.add(getValue(token));

			token += TOKEN_STRIDE;
			matched = true;
		} else {
			throw new IllegalStateException();
		}

		if (!matched && !node.isOptional() || last && token < tokenCount) {
			return -1;
		}

		if (last && !floatingArgs.isEmpty()) { // check if all floating args have been provided on the path taken
			for (String arg : floatingArgs.keySet()) {
				if (!allowedFloatingArgsTmp.contains(arg)) {
					return -1;
				}
			}
		}

		return token;
	}

	private static void trimList(List<?> list, int size) {
		for (int i = list.size() - 1; i >= size; i--) {
			list.remove(i);
		}
	}

	private String getValue(int token) {
		assert token < tokenCount;

		return getValue(tokens[token], tokens[token + 1]);
	}

	private String getValue(int start, int end) {
		if (start == end) return "";

		int escapeStart = start;

		while (escapeStart < end && input.charAt(escapeStart) != '\\') {
			escapeStart++;
		}

		if (escapeStart == end) return input.subSequence(start, end).toString();

		buffer.setLength(0);
		buffer.append(input, start, escapeStart);
		start = escapeStart;

		while (start < end) {
			char c = input.charAt(start);

			if (c == '\\' && start + 1 < end) {
				c = input.charAt(++start);

				int idx = "nrtb".indexOf(c);

				if (idx >= 0) {
					buffer.append("\n\r\t\b").charAt(idx);
				} else {
					buffer.append(c);
				}
			} else {
				buffer.append(c);
			}
		}

		return buffer.toString();
	}

	private static final int TOKEN_STRIDE = 2;

	private CharSequence input;
	private int[] tokens = new int[TOKEN_STRIDE * 20]; // [start,end[ position pairs
	private int tokenCount = 0; // total token array entries (2 per actual token)
	private final Map<String, String> floatingArgs = new HashMap<>();
	private final StringBuilder buffer = new StringBuilder();
	private final List<String> capturedArgsTmp = new ArrayList<>();
	private final List<String> allowedFloatingArgsTmp = new ArrayList<>();
}
