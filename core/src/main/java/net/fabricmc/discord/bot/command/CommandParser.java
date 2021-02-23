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
import net.fabricmc.discord.bot.command.UsageParser.GroupNode;
import net.fabricmc.discord.bot.command.UsageParser.Node;
import net.fabricmc.discord.bot.command.UsageParser.OrNode;
import net.fabricmc.discord.bot.command.UsageParser.PlainNode;
import net.fabricmc.discord.bot.command.UsageParser.VarNode;

public final class CommandParser {
	public static void main(String[] args) {
		UsageParser usageParser = new UsageParser();
		Node node = usageParser.parse("(any|block|be|blockentity|entity) (class <class> | id <id>) [<dimId>] [--ticking] [--unloaded] [--countOnly | --chunkCounts] [--filter[=<x>]] [--clear]");
		//Node node = usageParser.parse("list [<user>] | (add|remove) [<user>] <name>");
		//Node node = usageParser.parse("warn <user> <reason...> <test> ss [<asd>]");
		System.out.printf("root node: %s%n", node.toStringFull());

		//String input = "be id minecraft:chest nether --unloaded --countOnly --filter=test --clear";
		String input = "be id minecraft:chest nether --unloaded --chunkCounts --filter=test --clear";
		//String input = "add somegroup";
		//String input = "warn someone reason asd xy ss qwe";
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
		this.tokenIndex = 0;
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

				String key = getValue(start, end);
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

				if (DEBUG) System.out.printf("new flarg: %s = %s%n", key, value);
				floatingArgs.put(key, value);
				i = end;
			} else if (!Character.isWhitespace(c)) {
				int end = findBlockEndWhitespace(input, i + 1, inputEnd);
				addToken(i, end);
				i = end;
			}
		}

		int tokenCount = tokenIndex / TOKEN_STRIDE;

		if (tokenCount < node.getMinTokensTotal() || tokenCount > node.getMaxTokensTotal()) {
			return false;
		}

		capturedArgs.clear();
		allowedFloatingArgs.clear();
		queueSize = 0;

		boolean ret = processNodes(node, 0, tokenCount);

		if (ret) {
			for (int i = 0; i < capturedArgs.size(); i += 2) {
				String key = capturedArgs.get(i);
				if (key == null) key = String.format("unnamed_%d", i >>> 1);
				String value = capturedArgs.get(i + 1);

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
		if (tokenIndex == tokens.length) tokens = Arrays.copyOf(tokens, tokens.length * TOKEN_STRIDE);
		tokens[tokenIndex++] = start;
		tokens[tokenIndex++] = end;

		if (DEBUG) System.out.printf("new token: %s%n", input.subSequence(start, end));
	}

	private boolean processNodes(Node node, int token, int tokensAvailable) {
		// TODO: implement repeat
		queue(node, 0, token, tokensAvailable);

		queueLoop: while ((node = deQueue()) != null) {
			int nodeData = getQueueNodeData();
			token = getQueueToken();
			tokensAvailable = getQueueTokensAvailable();

			do {
				Node next = null;
				boolean matched;

				if (node instanceof FloatingArgNode) {
					FloatingArgNode faNode = (FloatingArgNode) node;

					if (floatingArgs.containsKey(faNode.key)) {
						String value = floatingArgs.get(faNode.key);

						matched = value == null && (faNode.value == null || faNode.value.isOptional())
								|| value != null && faNode.value != null;  // TODO: check if value is compliant with whatever faNode.value requires
					} else {
						matched = false;
					}
				} else if (node instanceof GroupNode) {
					GroupNode group = (GroupNode) node;
					next = group.child;
					matched = true;
				} else if (node instanceof OrNode) {
					OrNode orNode = (OrNode) node;

					for (Node option : orNode) {
						if (next == null) {
							next = option;
						} else {
							queue(option, 0, token, tokensAvailable);
						}
					}

					matched = true;
				} else if (token >= tokenIndex) {
					matched = false;
				} else if (node instanceof PlainNode) {
					PlainNode plainNode = (PlainNode) node;
					matched = plainNode.content.equals(getValue(token));
				} else if (node instanceof VarNode) {
					VarNode varNode = (VarNode) node;
					// TODO: implement varNode value verification
					matched = true;
				} else {
					throw new IllegalStateException();
				}

				if (!matched && !node.isOptional()) {
					continue queueLoop; // dead end
				}

				if (next == null) {
					next = node.getNext();

					while (next == null && node.getParent() != null) {
						next = node.getParent().getNext();
					}
				}

				if (matched) {
					if (node.isOptional() && node.isPositionDependent() && next != null) {
						queue(next, 0, token, tokensAvailable); // may have to retry without node
					}

					int tokensConsumed;

					if (node instanceof FloatingArgNode) {
						allowedFloatingArgs.add(((FloatingArgNode) node).key);
						tokensConsumed = 0;
					} else if (node instanceof PlainNode) {
						capturedArgs.add(null);
						capturedArgs.add(((PlainNode) node).content);
						tokensConsumed = 1;
					} else if (node instanceof VarNode) {
						VarNode varNode = (VarNode) node;

						String value;

						if (varNode.multiWord) {
							int words = tokensAvailable - nodeData - node.getMinTokensNext();
							assert words > 0;

							if (words > 1) {
								queue(node, nodeData + 1, token, tokensAvailable); // may have to retry with less words
							}

							value = getValue(tokens[token], tokens[token + (words - 1) * TOKEN_STRIDE + 1]);
							tokensConsumed = words;
						} else {
							value = getValue(token);
							tokensConsumed = 1;
						}

						capturedArgs.add(((VarNode) node).variable);
						capturedArgs.add(value);
					} else {
						tokensConsumed = 0;
					}

					token += tokensConsumed * TOKEN_STRIDE;
					tokensAvailable -= tokensConsumed;
				}

				if (next == null) {
					if (!floatingArgs.isEmpty()) { // check if all floating args have been provided on the path taken
						for (String arg : floatingArgs.keySet()) {
							if (!allowedFloatingArgs.contains(arg)) {
								continue queueLoop; // extra floating args
							}
						}
					}

					return true;
				}

				nodeData = 0;
				node = next;
			} while (node != null);
		}

		return false;
	}

	private void queue(Node node, int nodeData, int token, int tokensAvailable) {
		if (queuedNodes == null) {
			queuedNodes = new Node[5 * QUEUE_NODE_STRIDE];
			queuedData = new int[5 * QUEUE_DATA_STRIDE];
		} else {
			queuedNodes = Arrays.copyOf(queuedNodes, queuedNodes.length * 2);
			queuedData = Arrays.copyOf(queuedData, queuedData.length * 2);
		}

		int nodeIdx = queueSize * QUEUE_NODE_STRIDE;
		queuedNodes[nodeIdx++] = node;

		int dataIdx = queueSize * QUEUE_DATA_STRIDE;
		queuedData[dataIdx++] = nodeData;
		queuedData[dataIdx++] = token;
		queuedData[dataIdx++] = tokensAvailable;
		queuedData[dataIdx++] = capturedArgs.size();
		queuedData[dataIdx++] = allowedFloatingArgs.size();
		queueSize++;

		assert nodeIdx == queueSize * QUEUE_NODE_STRIDE;
		assert dataIdx == queueSize * QUEUE_DATA_STRIDE;
	}

	private Node deQueue() {
		if (queueSize == 0) return null;

		queueSize--;

		int nodeIdx = queueSize * QUEUE_NODE_STRIDE;
		Node ret = queuedNodes[nodeIdx];
		queuedNodes[nodeIdx] = null;

		int dataIdx = queueSize * QUEUE_DATA_STRIDE;
		trimList(capturedArgs, queuedData[dataIdx + 3]);
		trimList(allowedFloatingArgs, queuedData[dataIdx + 4]);

		return ret;
	}

	private int getQueueNodeData() {
		return queuedData[queueSize * QUEUE_DATA_STRIDE + 0];
	}

	private int getQueueToken() {
		return queuedData[queueSize * QUEUE_DATA_STRIDE + 1];
	}

	private int getQueueTokensAvailable() {
		return queuedData[queueSize * QUEUE_DATA_STRIDE + 2];
	}

	private static void trimList(List<?> list, int size) {
		for (int i = list.size() - 1; i >= size; i--) {
			list.remove(i);
		}
	}

	private String getValue(int token) {
		assert token < tokenIndex;

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

	private static final boolean DEBUG = false;

	private static final int TOKEN_STRIDE = 2;
	private static final int QUEUE_NODE_STRIDE = 1;
	private static final int QUEUE_DATA_STRIDE = 5;

	private CharSequence input;
	private int[] tokens = new int[TOKEN_STRIDE * 20]; // [start,end[ position pairs
	private int tokenIndex = 0; // total token array entries (2 per actual token)
	private final Map<String, String> floatingArgs = new HashMap<>();
	private final StringBuilder buffer = new StringBuilder();
	private final List<String> capturedArgs = new ArrayList<>();
	private final List<String> allowedFloatingArgs = new ArrayList<>();

	private Node[] queuedNodes;
	private int[] queuedData;
	int queueSize = 0;
}
