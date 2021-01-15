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

final class UsageParser {
	public static void main(String[] args) throws IOException {
		UsageParser parser = new UsageParser();
		//Node node = parser.parse("(any|block|be|blockentity|entity) (class <class> | id <id>) [<dimId>] [--ticking] [--unloaded] [--countOnly | --chunkCounts] [--filter[=<x>]] [--clear]", false);
		//Node node = parser.parse("(any|block|be|blockentity|entity) (class <class> | id <id>) [<dimId>] [--ticking] [--unloaded]", false);
		//Node node = parser.parse("(any|block|be|blockentity|entity) (class <class> | id <id>) [<dimId>]", false);
		Node node = parser.parse("[<dim-id>] [<limit>]", true);
		//Node node = parser.parse("[<dim-id>] [<limit>] [--class]", false);
		//Node node = parser.parse("[<dim-id>] [<limit>] [--class] [--asd]", false);
		//Node node = parser.parse("<dim-id>", false);
		System.out.printf("result: %s%n", node.toString());
		GraphNode graph = toGraph(node, false, null);
		//System.out.println(graph);

		Set<GraphNode> queued = Collections2.newIdentityHashSet();
		Map<GraphNode, Integer> idMap = new IdentityHashMap<>();
		AtomicInteger nextId = new AtomicInteger();
		Function<GraphNode, Integer> idAllocator = ignore -> nextId.getAndIncrement();
		Queue<GraphNode> toVisit = new ArrayDeque<>();
		toVisit.add(graph);
		queued.add(graph);
		GraphNode gn;

		try (PrintWriter pw = new PrintWriter(Files.newBufferedWriter(Paths.get("graph.gv")))) {
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
	 *   --x: position independent flag x
	 * --x=a: position independent flag x with mandatory value a (value may still be empty if a is e.g. (|b) or [b])
	 * --x[=a]: position independent flag x, optinally with value a
	 * </pre>
	 *
	 * @param usage usage string encoding the acceptable command parameters
	 * @param fixPositionDependence whether to ensure that there can be only one optional position dependent parameter
	 *        at a time by introducing the missing dependency between those parameters that removes ambiguity
	 * @return root node representing the usage string's tree form
	 */
	public Node parse(String usage, boolean fixPositionDependence) {
		this.input = usage;
		this.tokenCount = 0;

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

		Node ret = toTree(0, tokenCount);

		if (fixPositionDependence) {
			ret = fixPositionDependence(ret);
		}

		return ret;
	}

	private void addToken(int start, int end) {
		if (tokenCount == tokens.length) tokens = Arrays.copyOf(tokens, tokens.length * TOKEN_STRIDE);
		tokens[tokenCount++] = start;
		tokens[tokenCount++] = end;
	}

	private Node toTree(int startToken, int endToken) {
		if (startToken == endToken) {
			return EmptyNode.INSTANCE;
		}

		OrNode orNode = null; // overall OrNode for this token range
		Node cur = null; // node currently being assembled (excl. encompassing OrNode or OrNode siblings)
		boolean isListNode = false; // whether cur was already wrapped in ListNode
		boolean lastWasEmpty = false; // tracks whether the previous node was empty, thus not in the current list and not applicable for repeat (...)

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

				if (c == '[') node.setOptional();
			} else if (c == '|') { // alternative options: ..|..
				if (orNode == null) orNode = new OrNode();

				if (cur instanceof OrNode) {
					for (Node n : ((OrNode) cur)) {
						orNode.add(n);
					}
				} else {
					orNode.add(cur);
				}

				// reset cur
				cur = null;
				isListNode = false;
				continue;
			} else if (c == '-' && tokens[token + 1] > startPos + 2 && input.charAt(startPos + 1) == '-') { // --key or --key=value or --key[=value] pos-independent arg
				String key = input.subSequence(startPos + 2, tokens[token + 1]).toString();
				int separatorToken = token + TOKEN_STRIDE;
				Node value;
				char next;

				if (separatorToken < endToken
						&& ((next = input.charAt(tokens[separatorToken])) == '='
						|| next == '[' && separatorToken + TOKEN_STRIDE < endToken && input.charAt(tokens[separatorToken + TOKEN_STRIDE]) == '=')) { // next token is = -> --key=value
					int valueToken = separatorToken + TOKEN_STRIDE;
					int lastToken = valueToken;

					if (next == '[') {
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
				if (cur == null) throw new IllegalArgumentException("standalone ...");

				if (!lastWasEmpty) {
					if (isListNode) {
						ListNode prev = (ListNode) cur;
						prev.get(prev.size() - 1).setRepeat();
					} else {
						cur.setRepeat();
					}
				}

				continue;
			} else if (c == '<') { // variable <x>
				node = new VarNode(input.subSequence(startPos + 1, tokens[token + 1] - 1).toString());
			} else { // plain string
				node = new PlainNode(input.subSequence(startPos, tokens[token + 1]).toString());
			}

			lastWasEmpty = node instanceof EmptyNode;

			if (cur == null || cur instanceof EmptyNode) {
				cur = node;
			} else if (!lastWasEmpty) { // 2+ consecutive nodes not separated by |, use ListNode
				if (!isListNode) {
					cur = new ListNode(cur);
					isListNode = true;
				}

				((ListNode) cur).add(node);
			}
		}

		if (orNode != null) {
			// add last node before end
			orNode.add(cur);

			return orNode.simplify();
		} else {
			return cur;
		}
	}

	private Node fixPositionDependence(Node node) {
		if (node instanceof ListNode) {
			// transform a list with multiple optional position dependent entries such that it will have only one position dependent entry
			// e.g. [a] [b] [c]  ->  [a], a [b], a b [c]  <-  [a [b [c]]]
			// or    a  [b] [c]  ->  a [b], a b [c]       <-   a [b [c]]
			// or   a [b] [c] d  ->  a [b] d, a b [c] d   <-   a [b [c]] d

			ListNode list = (ListNode) node;
			OrNode orNode = null;
			int lastOptional = -1;

			for (int i = 0; i < list.size(); i++) {
				Node element = list.get(i);

				if (!element.isOptional() || !element.isPositionDependent()) {
					continue;
				}

				if (lastOptional >= 0) {
					if (orNode == null) {
						orNode = new OrNode();

						// set first option to list[0..firstOptional+1] + filterOpt(list[firstOptional+1..])

						if (lastOptional > 0 || list.size() > lastOptional + 1 + 1) { // new list may have more than 1 element (2+ leading or 1+ trailing elements)
							ListNode newList = new ListNode(list.size() - 1);
							copyList(list, 0, lastOptional + 1, newList);
							copyListNonOptional(list, lastOptional + 1, newList);

							if (newList.size() > 1) {
								orNode.add(newList);
							} else {
								orNode.add(newList.get(0));
							}
						} else {
							orNode.add(list.get(0));
						}
					}

					ListNode newList = new ListNode(list.size());

					if (lastOptional > 0) {
						ListNode prevList = (ListNode) orNode.get(orNode.size() - 1); // previously produced list has already at most 1 optional position dependent element at lastOptional
						copyList(prevList, 0, lastOptional, newList);
					}

					Node opt = list.get(lastOptional).copy();
					opt.clearOptional();
					newList.add(opt);

					copyList(list, lastOptional + 1, i + 1, newList);
					copyListNonOptional(list, i + 1, newList);
					orNode.add(newList);
				}

				lastOptional = i;
			}

			return orNode != null ? orNode : node;
		} else if (node instanceof OrNode) {
			for (ListIterator<Node> it = ((OrNode) node).options.listIterator(); it.hasNext(); ) {
				Node option = it.next();
				it.set(fixPositionDependence(option));
			}

			return node;
		} else {
			return node;
		}
	}

	private static void copyList(ListNode src, int start, int end, ListNode dst) {
		for (int i = start; i < end; i++) {
			dst.add(src.get(i));
		}
	}

	private static void copyListNonOptional(ListNode src, int start, ListNode dst) {
		for (int i = start; i < src.size(); i++) {
			Node element = src.get(i);

			if (!element.isOptional() || !element.isPositionDependent()) {
				dst.add(element);
			}
		}
	}

	public static abstract class Node {
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

		protected abstract Node copy();

		protected <T extends Node> T copyFlags(T node) {
			if (optional) node.setOptional();
			if (repeat) node.setRepeat();

			return node;
		}

		abstract boolean isPositionDependent();

		protected abstract String toString0();

		protected final String toString(boolean ignoreOptional) {
			String s = toString0();

			if (repeat) {
				return s.concat("...");
			} else if (optional && !ignoreOptional) {
				return String.format("[%s]", s);
			} else {
				return s;
			}
		}

		@Override
		public final String toString() {
			return toString(false);
		}

		/**
		 * Whether this node may be omitted.
		 */
		private boolean optional;
		/**
		 * Whether this node may be repeatedly specified.
		 */
		private boolean repeat;
	}

	/**
	 * Plain text string.
	 */
	public static final class PlainNode extends Node {
		PlainNode(String content) {
			this.content = content;
		}

		@Override
		protected PlainNode copy() {
			return copyFlags(new PlainNode(content));
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
		VarNode(String variable) {
			this.variable = variable;
		}

		@Override
		protected VarNode copy() {
			return copyFlags(new VarNode(variable));
		}

		@Override
		boolean isPositionDependent() {
			return true;
		}

		@Override
		protected String toString0() {
			return String.format("<%s>", variable);
		}

		public final String variable;
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
		protected FloatingArgNode copy() {
			return copyFlags(new FloatingArgNode(key, value));
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
	 * List tree node with consecutive children (unless floating).
	 */
	public static final class ListNode extends Node implements Iterable<Node> {
		ListNode(Node element) {
			elements = new ArrayList<>();
			elements.add(element);
			setOptional();
		}

		ListNode(int size) {
			elements = new ArrayList<>(size);
			setOptional();
		}

		@Override
		protected ListNode copy() {
			ListNode ret = new ListNode(elements.size());
			ret.elements.addAll(elements);

			return copyFlags(ret);
		}

		@Override
		boolean isPositionDependent() {
			for (Node element : elements) {
				if (element.isPositionDependent()) return true;
			}

			return false;
		}

		public int size() {
			return elements.size();
		}

		public Node get(int index) {
			return elements.get(index);
		}

		@Override
		public Iterator<Node> iterator() {
			return elements.iterator();
		}

		void add(Node node) {
			elements.add(node);
			if (!node.isOptional()) clearOptional();
		}

		@Override
		protected String toString0() {
			StringBuilder ret = new StringBuilder();
			ret.append('{');

			for (int i = 0; i < elements.size(); i++) {
				if (i != 0) ret.append(',');
				ret.append(elements.get(i).toString());
			}

			ret.append('}');

			return ret.toString();
		}

		private final List<Node> elements;
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
		protected OrNode copy() {
			OrNode ret = new OrNode(options.size());
			ret.options.addAll(options);

			return copyFlags(ret);
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
			ret.append('{');

			for (int i = 0; i < options.size(); i++) {
				if (i != 0) ret.append('|');
				ret.append(options.get(i).toString());
			}

			ret.append('}');

			return ret.toString();
		}

		private final List<Node> options;
	}

	/**
	 * Synthetic node representing no input.
	 */
	public static final class EmptyNode extends Node {
		@Override
		protected EmptyNode copy() {
			return this;
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
		GraphNode result = toGraph0(rootNode, false, queue, queued, floatingNodeMap);

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
							insert = toGraph0(targetNode, true, queue, queued, null);
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

	private static GraphNode toGraph0(Node node, boolean ignoreOptional, Queue<GraphNode> tmpQueue, Set<GraphNode> tmpSet, Map<GraphNode, List<Node>> floatingNodeMap) {
		GraphNode ret;

		if (node instanceof ListNode) {
			ret = null;
			GraphNode prev = null;

			for (Node element : ((ListNode) node).elements) {
				if (floatingNodeMap != null && !element.isPositionDependent()) {
					if (prev == null) {
						ret = prev = new GraphNode(null);
					}

					floatingNodeMap.computeIfAbsent(prev, ignore -> new ArrayList<>()).add(element);

					continue;
				}

				GraphNode gn = toGraph0(element, ignoreOptional || prev != null, tmpQueue, tmpSet, floatingNodeMap);

				if (prev == null) {
					ret = prev = gn;
				} else {
					boolean optional = element.isOptional();

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
			}
		} else if (node instanceof OrNode) {
			OrNode orNode = (OrNode) node;
			List<GraphNode> tails = new ArrayList<>(orNode.options.size());

			for (Node option : orNode.options) {
				tails.add(toGraph0(option, ignoreOptional || orNode.isOptional(), tmpQueue, tmpSet, floatingNodeMap));
			}

			ret = new GraphNode(null);
			ret.setTails(tails, false, tmpQueue, tmpSet);
		} else {
			ret = new GraphNode(node);
		}

		if (node.isRepeat()) {
			ret.setTail(ret, true, tmpQueue, tmpSet);
		}

		if (!ignoreOptional && node.isOptional()) {
			if (ret.hasNode()) {
				GraphNode newRet = new GraphNode(null);
				newRet.next.add(ret);
				ret = newRet;
			} else if (!ret.next.contains(END_NODE)) {
				ret.next.add(END_NODE);
			}
		}

		return ret;
	}

	public static final class GraphNode {
		GraphNode(Node node) {
			this.node = node;
			if (END_NODE != null) this.next.add(END_NODE);
		}

		GraphNode deepCopy(Map<GraphNode, GraphNode> copies) {
			if (this == END_NODE) return this;
			assert hasNode();

			GraphNode ret = copies.get(this);
			if (ret != null) return ret;

			ret = new GraphNode(node);
			copies.put(this, ret);
			ret.next.clear();

			Queue<GraphNode> queue = null;

			for (GraphNode n : next) {
				if (n == END_NODE) {
					ret.next.add(n);
				} else if (n.hasNode()) {
					ret.next.add(n.deepCopy(copies));
				} else {
					for (GraphNode nsub : n.next) {
						if (nsub.hasNode() || nsub == END_NODE) {
							if (!next.contains(nsub)) {
								ret.next.add(nsub.deepCopy(copies));
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
								ret.next.add(nsub.deepCopy(copies));
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

	public static final GraphNode END_NODE = new GraphNode(null);
	public static final Node FLAGS_NODE = new PlainNode("flags"); // special node type for graph nodes that may consume any number of flags (--x[=y])

	private static final int TOKEN_STRIDE = 2;

	private CharSequence input;
	private int[] tokens = new int[TOKEN_STRIDE * 20]; // [start,end[ position pairs
	private int tokenCount = 0; // total token array entries (2 per actual token)
}
