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

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import net.fabricmc.discord.bot.util.Collections2;

public final class UsageParser {
	public static void main(String[] args) throws IOException {
		UsageParser parser = new UsageParser();
		//Node node = parser.parse("(any|block|be|blockentity|entity) (class <class> | id <id>) [<dimId>] [--ticking] [--unloaded] [--countOnly | --chunkCounts] [--filter[=<x>]] [--clear]");
		//Node node = parser.parse("(any|block|be|blockentity|entity) (class <class> | id <id>) [<dimId>] [--ticking] [--unloaded]");
		//Node node = parser.parse("(any|block|be|blockentity|entity) (class <class> | id <id>) [<dimId>]");
		//Node node = parser.parse("[<dim-id>] [<limit>]");
		//Node node = parser.parse("<test> [<dim-id>] [<limit>]");
		//Node node = parser.parse("[<dim-id>] [<limit>] [--class]");
		Node node = parser.parse("[<dim-id>] [<limit>] [--class] [--asd]");
		//Node node = parser.parse("<dim-id>");
		//Node node = parser.parse("<user> <msg...>");
		System.out.printf("result: %s%n", node.toStringFull());
		GraphNode graph = toGraph(node, true, null);
		//System.out.println(graph);

		Set<GraphNode> queued = Collections2.newIdentityHashSet();
		Map<GraphNode, Integer> idMap = new IdentityHashMap<>();
		AtomicInteger nextId = new AtomicInteger();
		Function<GraphNode, Integer> idAllocator = ignore -> nextId.getAndIncrement();
		Queue<GraphNode> toVisit = new ArrayDeque<>();
		toVisit.add(graph);
		queued.add(graph);
		GraphNode gn;

		Path gvOut = Paths.get("graph.gv");
		Path svgOut = Paths.get("graph.svg");

		try (PrintWriter pw = new PrintWriter(Files.newBufferedWriter(gvOut))) {
			pw.println("digraph G {");

			while ((gn = toVisit.poll()) != null) {
				int id = idMap.computeIfAbsent(gn, idAllocator);

				for (GraphNode next : gn.next) {
					int nid = idMap.computeIfAbsent(next, idAllocator);
					pw.printf("  n_%d -> n_%d;%n", id, nid);

					if (queued.add(next)) toVisit.add(next);
				}
			}

			for (Map.Entry<GraphNode, Integer> entry : idMap.entrySet()) {
				pw.printf("  n_%d [label=\"%s\"];%n", entry.getValue(), entry.getKey().getName().replace("\"", "\\\""));
			}

			pw.println("}");
		}

		Runtime.getRuntime().exec(new String[] { "dot", "-Tsvg", "-o"+svgOut.toString(), gvOut.toString() });
	}

	/**
	 * Parse an usage string into a Node tree.
	 *
	 * <p>The usage string describes the acceptable parameters with their ordering and presence requirements for a
	 * command. Those parameters will be represented by a Node, then grouped appropriately with ListNode and OrNode to
	 * mirror the structure of the usage string. Optional and/or repeating Nodes will be flagged as such.
	 *
	 * <p>The resulting Node tree can then be used to parse and validate a command parameter string or further transformed
	 * into a graph structure.
	 *
	 * <p>Usage string format, a and b are any expression, x is a literal or name:
	 * <pre>
	 *   a b: a and b have to be supplied in this order
	 *   a|b: either a or b can be supplied, lowest precedence (a b|c is the same as (a b)|c)
	 *   [a]: a is optional
	 *  a...: a may be repeated, at least one instance, highest precedence
	 * (a b): a and b act as a common element in the surrounding context
	 *     x: literal input "x" required
	 * {@literal   <x>}: x is a variable capturing any input token
	 * {@literal <x...>}: x is a multi-word variable capturing at least one input token
	 *   --x: position independent flag x
	 * --x=a: position independent flag x with mandatory value a (value may still be empty if a is e.g. (|b) or [b])
	 * --x[=a]: position independent flag x, optinally with value a
	 * </pre>
	 *
	 * @param usage usage string encoding the acceptable command parameters
	 * @return root node representing the usage string's tree form
	 */
	public Node parse(String usage) {
		this.input = usage;
		this.tokenIndex = 0;

		/* parse usage string into the following tokens:
		 * - <..> where .. is anything
		 * - ...
		 * - (
		 * - )
		 * - [
		 * - ]
		 * - |
		 * - =
		 * - other consecutive non-whitespace strings
		 */

		for (int i = 0, max = usage.length(); i < max; i++) {
			char c = usage.charAt(i);

			if (c == '<') {
				int end = i + 1;

				while (end < max && usage.charAt(end) != '>') {
					end++;
				}

				if (usage.charAt(end) != '>') throw new IllegalArgumentException("unterminated < (missing >)");

				addToken(i, end + 1);
				i = end;
			} else if (c == '.' && i + 2 < max && usage.charAt(i + 1) == '.' && usage.charAt(i + 2) == '.') {
				if (i == 0 || Character.isWhitespace(usage.charAt(i - 1))) throw new IllegalArgumentException("... not directly after something");
				addToken(i, i + 3);
				i += 2;
			} else if ("()[]|=".indexOf(c) >= 0) {
				addToken(i, i + 1);
			} else if (!Character.isWhitespace(c)) {
				int end = i;

				while (end + 1 < max
						&& "()[]|=<>".indexOf(c = usage.charAt(end + 1)) < 0
						&& !Character.isWhitespace(c)
						&& (c != '.' || end + 3 >= max || usage.charAt(end + 2) != '.' || usage.charAt(end + 3) != '.')) {
					end++;
				}

				addToken(i, end + 1);
				i = end;
			}
		}

		// turn tokens into a node tree

		Node ret = toTree(0, tokenIndex);

		computeTokenCountBounds(ret);

		this.input = null;

		return ret;
	}

	private void addToken(int start, int end) {
		if (tokenIndex == tokens.length) tokens = Arrays.copyOf(tokens, tokens.length * TOKEN_STRIDE);
		tokens[tokenIndex++] = start;
		tokens[tokenIndex++] = end;

		if (DEBUG) System.out.printf("new token: %s%n", input.subSequence(start, end));
	}

	private Node toTree(int startToken, int endToken) {
		if (DEBUG) System.out.printf("toTree %d..%d: %s%n", startToken, endToken, input.subSequence(tokens[startToken], tokens[endToken - 1]));

		if (startToken == endToken) {
			return EmptyNode.INSTANCE;
		}

		OrNode orNode = null; // overall OrNode for this token range
		Node head = null; // root node of the linked node list currently being assembled (excl. encompassing OrNode or OrNode siblings)
		Node tail = null; // tail node of the linked node list currently being assembled
		Node prevNode = null; // head node for the previous token (current context only, cut at OrNode boundaries)

		for (int token = startToken; token < endToken; token += TOKEN_STRIDE) {
			int startPos = tokens[token];
			char c = input.charAt(startPos);
			Node node;

			if (c == '(' || c == '[') { // precedence-grouping: (x) or optional: [x]
				char closeChar = c == '(' ? ')' : ']';
				int toFind = 1;
				int subStart = token + TOKEN_STRIDE;

				for (int subToken = subStart; subToken < endToken; subToken += TOKEN_STRIDE) {
					char c2 = input.charAt(tokens[subToken]);

					if (c2 == c) {
						toFind++;
					} else if (c2 == closeChar) {
						toFind--;

						if (toFind == 0) {
							token = subToken;
							break;
						}
					}
				}

				if (toFind != 0) throw new IllegalArgumentException("unterminated "+c);

				node = toTree(subStart, token);

				if (c == '[') { // optional node
					if (node.hasNext()) { // more than a single node, use group node to flag the entire set of nodes
						node = new GroupNode(node);
					}

					node.setOptional();
				}
			} else if (c == '|') { // alternative options: ..|..
				if (orNode == null) orNode = new OrNode();

				if (head instanceof OrNode) { // optimize (a|b)|c to a|b|c
					for (Node n : ((OrNode) head)) {
						orNode.add(n);
					}
				} else {
					orNode.add(head);
				}

				// reset cur
				head = tail = prevNode = null;
				continue;
			} else if (c == '-' && tokens[token + 1] > startPos + 2 && input.charAt(startPos + 1) == '-') { // --key or --key=value or --key[=value] pos-independent arg
				String key = input.subSequence(startPos + 2, tokens[token + 1]).toString();
				int separatorToken = token + TOKEN_STRIDE;
				Node value;
				char next;

				if (separatorToken < endToken
						&& ((next = input.charAt(tokens[separatorToken])) == '='
						|| next == '[' && separatorToken + TOKEN_STRIDE < endToken && input.charAt(tokens[separatorToken + TOKEN_STRIDE]) == '=')) { // next is = or [= -> --key=value
					int valueToken = separatorToken + TOKEN_STRIDE;
					int lastToken = valueToken;

					if (next == '[') { // --key[=value] optional value
						valueToken += TOKEN_STRIDE;
						lastToken = valueToken + TOKEN_STRIDE;

						if (lastToken >= endToken || input.charAt(tokens[lastToken]) != ']') throw new IllegalArgumentException("missing ] in --key[=value]");
					}

					if (valueToken >= endToken) throw new IllegalArgumentException("missing value in --key=value");

					value = toTree(valueToken, valueToken + TOKEN_STRIDE);
					if (next == '[') value.setOptional();

					token = lastToken;
				} else { // just --key
					value = null;
				}

				node = new FloatingArgNode(key, value);
			} else if (c == '.' && tokens[token + 1] == startPos + 3) { // repeat: x...
				if (prevNode == null) throw new IllegalArgumentException("standalone ...");

				if (!(prevNode instanceof EmptyNode)) { // not ()... and similar
					if (prevNode.hasNext()) { // more than a single node, use group node to repeat the entire set of nodes
						node = new GroupNode(prevNode);

						// replace prevNode reference with node in the linked list starting at head
						if (head == prevNode) { // replace list root
							head = node;
						} else { // replace list node
							head.replaceNext(prevNode, node);
						}

						prevNode = node;
					}

					prevNode.setRepeat();
				}

				continue;
			} else if (c == '<') { // variable <x>
				int endPos = tokens[token + 1];
				boolean multiWord;

				if (endPos - startPos > 5 && input.charAt(endPos - 4) == '.' && input.charAt(endPos - 3) == '.' && input.charAt(endPos - 2) == '.') { // multi word: <x...>
					multiWord = true;
					endPos -= 3; // strip trailing ... from the variable name
				} else {
					multiWord = false;
				}

				node = new VarNode(input.subSequence(startPos + 1, endPos - 1).toString(), multiWord);
			} else { // plain string
				node = new PlainNode(input.subSequence(startPos, tokens[token + 1]).toString());
			}

			if (head == null || head instanceof EmptyNode) {
				head = node;
				tail = node.getTail();
			} else if (!(node instanceof EmptyNode)) { // 2+ consecutive nodes not separated by |, use ListNode
				tail.setNext(node);
				tail = node.getTail();
			}

			prevNode = node;
		}

		if (orNode != null) {
			// add last node before end
			orNode.add(head);

			return orNode.simplify();
		} else {
			return head;
		}
	}

	private void computeTokenCountBounds(Node node) {
		if (!node.hasNext()) {
			node.minTokensNext = 0;
		} else {
			Node next = node.getNext();
			computeTokenCountBounds(next);
			node.minTokensNext = next.minTokens + next.minTokensNext;
			node.maxTokensNext = addSat(next.maxTokens, next.maxTokensNext);
		}

		if (node instanceof PlainNode) {
			node.minTokens = node.isOptional() ? 0 : 1;
			node.maxTokens = node.isRepeat() ? Integer.MAX_VALUE : 1;
		} else if (node instanceof VarNode) {
			VarNode n = (VarNode) node;

			node.minTokens = node.isOptional() ? 0 : 1;
			node.maxTokens = node.isRepeat() || n.multiWord ? Integer.MAX_VALUE : 1;
		} else if (node instanceof FloatingArgNode) {
			node.minTokens = node.maxTokens;
		} else if (node instanceof GroupNode) {
			GroupNode group = (GroupNode) node;
			Node child = group.child;
			computeTokenCountBounds(child);
			node.minTokens = node.isOptional() ? 0 : child.minTokens + child.minTokensNext;
			node.maxTokens = node.isRepeat() ? Integer.MAX_VALUE : addSat(child.maxTokens, child.maxTokensNext);
		} else if (node instanceof OrNode) {
			OrNode orNode = (OrNode) node;
			assert orNode.size() > 0;
			int min = node.isOptional() ? 0 : Integer.MAX_VALUE;
			int max = node.isRepeat() ? Integer.MAX_VALUE : 0;

			for (Node n : orNode) {
				computeTokenCountBounds(n);
				min = Math.min(min, n.minTokens + n.minTokensNext);
				max = Math.max(max, addSat(n.maxTokens, n.maxTokensNext));
			}

			node.minTokens = min;
			node.maxTokens = max;
		} else {
			throw new IllegalStateException();
		}
	}

	private static int addSat(int a, int b) {
		if (a == Integer.MAX_VALUE || b == Integer.MAX_VALUE) {
			return Integer.MAX_VALUE;
		} else {
			return a + b;
		}
	}

	public static abstract class Node {
		public final Node getParent() {
			return parent;
		}

		public final boolean hasNext() {
			return next != null;
		}

		public final Node getNext() {
			return next;
		}

		final void setNext(Node next) {
			this.next = next;
		}

		final void replaceNext(Node oldNext, Node newNext) {
			Node parent = this;

			while (parent.next != oldNext) {
				parent = parent.next;
				if (parent == null) throw new IllegalArgumentException();
			}

			parent.next = newNext;
		}

		public final Node getTail() {
			Node ret = this;

			while (ret.next != null) {
				ret = ret.next;
			}

			return ret;
		}

		public final Node getNextRecursive() {
			Node cur = this;

			do {
				Node next = cur.getNext();
				if (next != null) return next;

				cur = cur.getParent();
			} while (cur != null);

			return null;
		}

		public final boolean isOptional() {
			return optional;
		}

		protected void setOptional() {
			this.optional = true;
		}

		protected void clearOptional() {
			this.optional = false;
		}

		public final boolean isRepeat() {
			return repeat;
		}

		protected void setRepeat() {
			this.repeat = true;
		}

		public final int getMinTokens() {
			return minTokens;
		}

		public final int getMaxTokens() {
			return maxTokens;
		}

		public final int getMinTokensNext() {
			return minTokensNext;
		}

		public final int getMaxTokensNext() {
			return maxTokensNext;
		}

		public final int getMinTokensTotal() {
			return minTokens + minTokensNext;
		}

		public final int getMaxTokensTotal() {
			return addSat(maxTokens, maxTokensNext);
		}

		abstract boolean isPositionDependent();

		protected abstract String toString0();

		protected final String toString(boolean ignoreOptional) {
			String s = toString0();

			if (repeat) {
				s = s.concat("...");
			} else if (optional && !ignoreOptional) {
				s = String.format("[%s]", s);
			}

			return s;
			/*return s.concat(String.format("[%d-%s+%d-%s]",
					minTokens,
					(maxTokens == Integer.MAX_VALUE ? "inf" : Integer.toString(maxTokens)),
					minTokensNext,
					(maxTokensNext == Integer.MAX_VALUE ? "inf" : Integer.toString(maxTokensNext))));*/
		}

		@Override
		public final String toString() {
			return toString(false);
		}

		public final String toStringFull() {
			return toStringFull(null);
		}

		protected final String toStringFull(Node end) {
			String s = toString(false);

			if (next == null) {
				return s;
			} else {
				StringBuilder sb = new StringBuilder();
				sb.append('{');
				sb.append(s);

				Node n = next;

				while (n != null && n != end) {
					sb.append(',');
					sb.append(n.toString(false));
					n = n.next;
				}

				sb.append('}');

				return sb.toString();
			}
		}

		protected Node parent;
		private Node next;

		/**
		 * Whether this node may be omitted.
		 */
		private boolean optional;
		/**
		 * Whether this node may be repeatedly specified.
		 */
		private boolean repeat;

		int minTokens;
		int maxTokens;
		int minTokensNext;
		int maxTokensNext;
	}

	/**
	 * Plain text string.
	 */
	public static final class PlainNode extends Node {
		PlainNode(String content) {
			this.content = content;
		}

		@Override
		boolean isPositionDependent() {
			return true;
		}

		@Override
		protected String toString0() {
			return String.format("\"%s\"", content);
		}

		public final String content;
	}

	/**
	 * Variable representing user input.
	 */
	public static final class VarNode extends Node {
		VarNode(String variable, boolean multiWord) {
			this.variable = variable;
			this.multiWord = multiWord;
		}

		@Override
		boolean isPositionDependent() {
			return true;
		}

		@Override
		protected String toString0() {
			return String.format("<%s%s>", variable, multiWord ? "..." : "");
		}

		public final String variable;
		public final boolean multiWord;
	}

	/**
	 * Argument that isn't bound to a specific position.
	 */
	public static final class FloatingArgNode extends Node {
		FloatingArgNode(String key, Node value) {
			this.key = key;
			this.value = value;
		}

		@Override
		boolean isPositionDependent() {
			return false;
		}

		@Override
		protected String toString0() {
			if (value == null) {
				return String.format("--%s", key);
			} else if (value.isOptional()) {
				return String.format("--%s[=%s]", key, value.toString(true));
			} else {
				return String.format("--%s=%s", key, value.toString());
			}
		}

		public final String key;
		public final Node value;
	}

	/**
	 * Group node to associate a linked list of nodes with optional/repeat flags in its entirety only.
	 */
	public static final class GroupNode extends Node {
		GroupNode(Node child) {
			this.child = child;
			child.getTail().parent = this;

			setOptional();

			Node n = child;

			do {
				if (!n.optional) {
					clearOptional();
					break;
				}
			} while ((n = n.next) != null);
		}

		@Override
		boolean isPositionDependent() {
			Node n = child;

			do {
				if (n.isPositionDependent()) return true;
			} while ((n = n.next) != null && n != getNext());

			return false;
		}

		@Override
		protected String toString0() {
			return String.format("{%s}", child.toString());
		}

		public final Node child;
	}

	/**
	 * Selection of mutually exclusive children.
	 */
	public static final class OrNode extends Node implements Iterable<Node> {
		OrNode() {
			options = new ArrayList<>();
		}

		OrNode(int size) {
			options = new ArrayList<>(size);
		}

		@Override
		boolean isPositionDependent() {
			for (Node option : options) {
				if (option.isPositionDependent()) return true;
			}

			return false;
		}

		public int size() {
			return options.size();
		}

		public Node get(int index) {
			return options.get(index);
		}

		@Override
		public Iterator<Node> iterator() {
			return options.iterator();
		}

		void add(Node node) {
			node.getTail().parent = this;

			if (node == null || node instanceof EmptyNode) { // leading empty: (|x) or trailing empty: (x|)
				setOptional();
			} else { // regular: (x|y)
				options.add(node);
				if (node.isOptional()) setOptional();
			}
		}

		Node simplify() {
			if (options.isEmpty()) {
				return EmptyNode.INSTANCE;
			} else if (options.size() == 1) {
				Node ret = options.get(0);
				if (isOptional()) ret.setOptional();
				if (isRepeat()) ret.setRepeat();

				return ret;
			} else {
				return this;
			}
		}

		@Override
		protected String toString0() {
			StringBuilder ret = new StringBuilder();
			ret.append('(');

			for (int i = 0; i < options.size(); i++) {
				if (i != 0) ret.append('|');
				ret.append(options.get(i).toString());
			}

			ret.append(')');

			return ret.toString();
		}

		private final List<Node> options;
	}

	/**
	 * Synthetic node representing no input.
	 */
	public static final class EmptyNode extends Node {
		protected EmptyNode() {
			super();
		}

		@Override
		protected void setOptional() { }

		@Override
		protected void setRepeat() { }

		@Override
		protected boolean isPositionDependent() {
			return false;
		}

		@Override
		protected String toString0() {
			return "{empty}";
		}

		static final EmptyNode INSTANCE = new EmptyNode();
	}

	/**
	 * Convert an usage tree with optional or repeating nodes into a directed graph with only mandatory nodes.
	 *
	 * <p>The graph uses extra edges to handle optional or repeating nodes, implemented as bypassing or back edges
	 * respectively. Position independent nodes (FloatingArgNode and nodes only exclusively containing those) will be
	 * represented by either a special repeating "flags" node that is supposed to absorb any number of remaining
	 * floating args or expanded such that the graph captures every possible permutation directly. Expanding grows the
	 * graph by the factorial of total floating args present, making it unsustainable for more than a few.
	 *
	 * <p>Any graph node that consumes input (a command parameter string token) will be backed by the respective original
	 * leaf tree Nodes (Plain/Var/FloatingArg). Valid end points for stopping consuming further tokens have edges
	 * towards the END_NODE. Additionally "PHI" graph nodes may get introduced for graph building purposes.
	 *
	 * <p>None of rootNode or its children will be modified, optional and repeating properties remain as-is but should be
	 * ignored when working with graph nodes. As mentioned above the graph structures itself captures them.
	 *
	 * @param rootNode root Node object for the command usage tree
	 * @param expandFlags whether to expand floating arguments as described above
	 * @param flagNodesOut collection accepting all encountered position independent nodes or null to ignore
	 * @return root graph node
	 */
	public static GraphNode toGraph(Node rootNode, boolean expandFlags, Collection<Node> flagNodesOut) {
		Map<GraphNode, List<Node>> floatingNodeMap = new IdentityHashMap<>();
		Queue<GraphNode> queue = new ArrayDeque<>();
		Set<GraphNode> queued = Collections2.newIdentityHashSet();
		GraphNode result = toGraph0(rootNode, false, false, queue, queued, floatingNodeMap);
		if (DEBUG) System.out.println("floating: "+floatingNodeMap);

		// process floating nodes (--x[=y] style arguments, potentially embedded in OrNode/ListNode)

		if (!floatingNodeMap.isEmpty()) {
			if (result.hasNode()) { // root needs to be a phi node in order to inject before the first non-optional node
				GraphNode newRoot = new GraphNode(null);
				newRoot.setTail(result, false, queue, queued);
				result = newRoot;
			}

			Set<Node> floatingNodes = Collections2.newIdentityHashSet();

			for (List<Node> nodes : floatingNodeMap.values()) {
				floatingNodes.addAll(nodes);
			}

			if (flagNodesOut != null) {
				flagNodesOut.addAll(floatingNodes);
			}

			List<GraphNode> connections = new ArrayList<>();
			Map<GraphNode, GraphNode> targetedGraphNodes = new IdentityHashMap<>();

			if (expandFlags) {
				Map<GraphNode, GraphNode> copies = new IdentityHashMap<>();

				for (Node targetNode : floatingNodes) {
					findConnections(result, queue, queued, connections);

					for (int i = 0; i < connections.size(); i += 2) {
						// TODO: check if targetNode is applicable to the current connection
						GraphNode next = connections.get(i + 1);
						assert next.hasNode() || next == END_NODE;
						GraphNode insert = targetedGraphNodes.get(next);

						if (insert == null) {
							insert = toGraph0(targetNode, true, true, queue, queued, null);
							insert.setTail(next.deepCopy(copies), false, queue, queued);
							targetedGraphNodes.put(next, insert);
						}

						connections.set(i + 1, insert);
					}

					copies.clear();
					targetedGraphNodes.clear();

					for (int i = 0; i < connections.size(); i += 2) {
						GraphNode current = connections.get(i);
						GraphNode insert = connections.get(i + 1);
						current.next.add(insert);
					}

					connections.clear();
				}
			} else { // non-expanded flags, use FLAGS_NODE dummy node
				findConnections(result, queue, queued, connections);

				for (int i = 0; i < connections.size(); i += 2) {
					GraphNode current = connections.get(i);
					GraphNode next = connections.get(i + 1);
					GraphNode insert = targetedGraphNodes.get(next);

					if (insert == null) {
						insert = new GraphNode(FLAGS_NODE);
						insert.next.add(insert);
						insert.setTail(next, false, queue, queued);
						targetedGraphNodes.put(next, insert);
					}

					current.next.add(insert);
				}
			}
		}

		// remove extra phi nodes

		List<GraphNode> removed = new ArrayList<>();
		queue.add(result);
		queued.add(result);
		GraphNode gn;

		while ((gn = queue.poll()) != null) {
			for (Iterator<GraphNode> it = gn.next.iterator(); it.hasNext(); ) {
				GraphNode next = it.next();
				if (next == END_NODE) continue;

				if (!next.hasNode()) {
					it.remove();
					removed.add(next);
				} else {
					if (queued.add(next)) queue.add(next);
				}
			}

			if (!removed.isEmpty()) {
				boolean repeated = false;

				for (GraphNode rem : removed) {
					for (GraphNode next : rem.next) {
						if (gn.next.contains(next)) continue;

						gn.next.add(next);

						if (next != END_NODE) {
							if (queued.add(next)) queue.add(next);

							if (!next.hasNode() && !repeated) { // directly nested phi nodes, need to process gn again
								queue.add(gn);
								repeated = true;
							}
						}
					}
				}

				removed.clear();
			}
		}

		if (DEBUG) System.out.printf("%d nodes", queued.size());

		return result;
	}

	private static void findConnections(GraphNode startNode, Queue<GraphNode> tmpQueue, Set<GraphNode> tmpSet, List<GraphNode> connections) {
		tmpQueue.add(startNode);
		tmpSet.add(startNode);
		GraphNode node;

		while ((node = tmpQueue.poll()) != null) {
			for (GraphNode next : node.next) {
				if (!next.hasNode() && next != END_NODE) { // phi node, treat as transparent
					Queue<GraphNode> subQueue = new ArrayDeque<>();
					Set<GraphNode> subQueued = Collections2.newIdentityHashSet();
					Set<GraphNode> subConnections = Collections2.newIdentityHashSet();
					subQueue.addAll(next.next);
					subQueued.addAll(next.next);
					GraphNode subNode;

					while ((subNode = subQueue.poll()) != null) {
						if (node.next.contains(subNode)) continue;
						if (!subConnections.add(subNode)) continue;

						if (!subNode.hasNode() && subNode != END_NODE) {
							for (GraphNode subNext : subNode.next) {
								if (subQueued.add(subNext)) subQueue.add(subNext);
							}
						} else {
							connections.add(node);
							connections.add(subNode);
							if (tmpSet.add(subNode)) tmpQueue.add(subNode);
						}
					}
				} else {
					connections.add(node);
					connections.add(next);
					if (tmpSet.add(next)) tmpQueue.add(next);
				}
			}
		}

		tmpSet.clear();
	}

	private static GraphNode toGraph0(Node node, boolean ignoreNext, boolean ignoreOptional, Queue<GraphNode> tmpQueue, Set<GraphNode> tmpSet, Map<GraphNode, List<Node>> floatingNodeMap) {
		GraphNode ret = null;
		GraphNode prev = null;

		do {
			if (floatingNodeMap != null && !node.isPositionDependent()) {
				if (prev == null) {
					ret = prev = new GraphNode(null);
				}

				floatingNodeMap.computeIfAbsent(prev, ignore -> new ArrayList<>()).add(node);

				continue;
			}

			boolean optional = node.isOptional();
			GraphNode gn;

			if (node instanceof OrNode) {
				OrNode orNode = (OrNode) node;
				List<GraphNode> tails = new ArrayList<>(orNode.options.size());

				for (Node option : orNode.options) {
					tails.add(toGraph0(option, false, optional, tmpQueue, tmpSet, floatingNodeMap));
				}

				gn = new GraphNode(null);
				gn.setTails(tails, false, tmpQueue, tmpSet);
			} else if (node instanceof GroupNode) {
				gn = toGraph0(((GroupNode) node).child, false, node.isOptional(), tmpQueue, tmpSet, floatingNodeMap);
			} else {
				gn = new GraphNode(node);
			}

			if (node.isRepeat()) {
				gn.setTail(gn, true, tmpQueue, tmpSet);
			}

			if (prev == null) {
				if (optional) {
					ret = prev = new GraphNode(null);
					prev.next.add(gn);
				} else {
					ret = prev = gn;
				}
			} else {
				if (!gn.hasNode()) { // quickly inline phi node
					if (prev.next.contains(END_NODE)) {
						gn.next.remove(END_NODE);
					}

					prev.setTails(gn.next, optional, tmpQueue, tmpSet);
				} else {
					prev.setTail(gn, optional, tmpQueue, tmpSet);
				}

				if (!optional) {
					prev = gn;
				}
			}
		} while (!ignoreNext && (node = node.getNext()) != null);

		if (ignoreOptional) {
			ret.next.remove(END_NODE);
			assert !ret.next.contains(END_NODE);
		}

		return ret;
	}

	public static final class GraphNode {
		GraphNode(Node node) {
			this.node = node;
			if (END_NODE != null) this.next.add(END_NODE);
		}

		GraphNode deepCopy(Map<GraphNode, GraphNode> reusableCopies) {
			if (this == END_NODE) return this;
			assert hasNode();

			GraphNode ret = reusableCopies.get(this);
			if (ret != null) return ret;

			ret = new GraphNode(node);
			reusableCopies.put(this, ret);
			ret.next.clear();

			Queue<GraphNode> queue = null;

			for (GraphNode n : next) {
				if (n == END_NODE) {
					ret.next.add(n);
				} else if (n.hasNode()) {
					ret.next.add(n.deepCopy(reusableCopies));
				} else {
					for (GraphNode nsub : n.next) {
						if (nsub.hasNode() || nsub == END_NODE) {
							if (!next.contains(nsub)) {
								ret.next.add(nsub.deepCopy(reusableCopies));
							}
						} else {
							if (queue == null) queue = new ArrayDeque<>();

							queue.add(nsub);
						}
					}
				}
			}

			if (queue != null) {
				GraphNode node;

				while ((node = queue.poll()) != null) {
					for (GraphNode nsub : node.next) {
						if (nsub.hasNode() || nsub == END_NODE) {
							if (!next.contains(nsub)) {
								ret.next.add(nsub.deepCopy(reusableCopies));
							}
						} else {
							queue.add(nsub);
						}
					}
				}
			}

			return ret;
		}

		public boolean hasNode() {
			return node != null;
		}

		public boolean isEnd() {
			return this == END_NODE;
		}

		void setTail(GraphNode gn, boolean keepEnds, Queue<GraphNode> tmpQueue, Set<GraphNode> tmpSet) {
			setTails(Collections.singletonList(gn), keepEnds, tmpQueue, tmpSet);
		}

		void setTails(List<GraphNode> tails, boolean keepEnds, Queue<GraphNode> tmpQueue, Set<GraphNode> tmpSet) {
			assert tmpQueue.isEmpty() && tmpSet.isEmpty();

			tmpQueue.add(this);
			tmpSet.add(this);
			GraphNode gn;

			while ((gn = tmpQueue.poll()) != null) {
				for (ListIterator<GraphNode> it = gn.next.listIterator(); it.hasNext(); ) {
					GraphNode next = it.next();

					if (next == END_NODE) {
						if (!keepEnds && tails.size() == 1) {
							it.set(tails.get(0));
						} else {
							if (!keepEnds) {
								it.remove();
							}

							for (GraphNode tail : tails) {
								it.add(tail);
							}
						}
					} else if (tmpSet.add(next)) {
						tmpQueue.add(next);
					}
				}
			}

			tmpSet.clear();
		}

		String getName() {
			if (node != null) return node.toString();

			return this == END_NODE ? "END" : "PHI";
		}

		@Override
		public String toString() {
			return this == END_NODE ? "END" : String.format("%s -> %s", getName(), next);
		}

		public final Node node;
		public final List<GraphNode> next = new ArrayList<>();
	}

	private static final boolean DEBUG = false;

	public static final GraphNode END_NODE = new GraphNode(null);
	public static final Node FLAGS_NODE = new PlainNode("flags"); // special node type for graph nodes that may consume any number of flags (--x[=y])

	private static final int TOKEN_STRIDE = 2;

	private CharSequence input;
	private int[] tokens = new int[TOKEN_STRIDE * 20]; // [start,end[ position pairs
	private int tokenIndex = 0; // total token array entries (2 per actual token)
}
