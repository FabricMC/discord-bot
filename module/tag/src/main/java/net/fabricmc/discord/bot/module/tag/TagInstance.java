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

package net.fabricmc.discord.bot.module.tag;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;

import org.javacord.api.entity.message.Message;
import org.javacord.api.entity.message.embed.EmbedBuilder;
import org.jetbrains.annotations.Nullable;

import net.fabricmc.discord.bot.command.CommandContext;
import net.fabricmc.discord.bot.command.CommandException;
import net.fabricmc.discord.bot.util.DiscordUtil;

public abstract class TagInstance {
	private final String name;

	public TagInstance(String name) {
		this.name = name;
	}

	public String getName() {
		return this.name;
	}

	public abstract int getArgCount();

	public abstract CompletableFuture<Message> send(CommandContext context, String arguments) throws CommandException;

	public static final class PlainText extends TagInstance {
		private final String text;

		public PlainText(String name, String text) {
			super(name);
			this.text = text;
		}

		@Override
		public int getArgCount() {
			return 0;
		}

		@Override
		public CompletableFuture<Message> send(CommandContext context, String arguments) {
			return DiscordUtil.sendMentionlessMessage(context.channel(), text);
		}

		@Override
		public String toString() {
			return "PlainText{name=\"%s\", text=\"%s\"}".formatted(this.getName(), this.text);
		}
	}

	public static final class ParameterizedText extends TagInstance {
		private final int[] parts; // 0..n for literalParts references, -1,..-m for arg# -(part+1)
		@Nullable
		private final List<Predicate<String>> validators;
		private final String[] literalParts;
		private final int argCount;

		public ParameterizedText(String name, String text) {
			this(name, null, text);
		}

		public ParameterizedText(String name, @Nullable List<Predicate<String>> validators, String text) {
			super(name);

			// parse text into segments of literal texts and tokens

			List<Integer> parts = new ArrayList<>();
			List<String> literalParts = new ArrayList<>();
			int argCount = 0;
			int startPos = 0;
			int pos;

			textLoop: while ((pos = text.indexOf("{{", startPos)) >= 0) {
				int end, arg;

				for (;;) {
					end = text.indexOf("}}", pos + 2);
					arg = -1;

					if (end > pos + 2) { // at least 1 char between {{ and }}
						try {
							arg = Integer.parseUnsignedInt(text, pos + 2, end, 10);
						} catch (NumberFormatException e) { }
					}

					if (arg >= 0) break;

					// not a well formed token, skip as if it wasn't there (similar to letting the outer loop continue, but without changing startPos)
					pos = text.indexOf("{{", pos + 1);
					if (pos < 0) break textLoop;
				}

				assert arg >= 0;

				if (pos > startPos) {
					parts.add(literalParts.size());
					literalParts.add(text.substring(startPos, pos));
				}

				parts.add(-(arg + 1));
				argCount = Math.max(argCount, arg + 1);

				startPos = end + 2;
			}

			if (startPos < text.length()) {
				parts.add(literalParts.size());
				literalParts.add(text.substring(startPos));
			}

			this.parts = new int[parts.size()];

			for (int i = 0; i < parts.size(); i++) {
				this.parts[i] = parts.get(i);
			}

			this.validators = validators;
			this.literalParts = literalParts.toArray(new String[0]);
			this.argCount = argCount;

			if (validators != null && validators.size() != argCount) {
				throw new IllegalStateException("Missing validator for tag %s; required %d, found %d".formatted(name, argCount, validators.size()));
			}
		}

		@Override
		public int getArgCount() {
			return argCount;
		}

		@Override
		public CompletableFuture<Message> send(CommandContext context, String arguments) throws CommandException {
			arguments = arguments.trim();
			StringBuilder sb = new StringBuilder();
			List<String> args;

			if (arguments.isEmpty() || argCount == 0) {
				args = Collections.emptyList();
			} else if (argCount <= 1) { // special case without any escape or quote handling
				args = Collections.singletonList(arguments);
			} else {
				args = new ArrayList<>();
				boolean atStart = true; // for inter-arg trimming
				boolean quoted = false;

				for (int pos = 0; pos < arguments.length(); pos++) {
					char c = arguments.charAt(pos);

					if (c == '\\' && pos + 1 < arguments.length()) {
						sb.append(arguments.charAt(++pos));
						atStart = false;
					} else if (c == '"') {
						quoted = !quoted;
						atStart = false;
					} else if (c == ' ' && !quoted) {
						if (!atStart) {
							args.add(sb.toString());
							sb.setLength(0);
							atStart = true;
						}
					} else {
						sb.append(c);
						atStart = false;
					}
				}

				if (quoted) throw new CommandException("Unterminated quote");
				if (!atStart) args.add(sb.toString());
				sb.setLength(0);
			}

			if (args.size() < argCount) throw new CommandException(String.format("Missing arguments, %d/%d present", args.size(), argCount));

			format(args, sb);

			return DiscordUtil.sendMentionlessMessage(context.channel(), sb);
		}

		private void format(List<String> args, StringBuilder out) throws CommandException {
			for (int part : parts) {
				if (part >= 0) {
					out.append(literalParts[part]);
				} else {
					int index = -(part+1);
					String arg = args.get(index);

					if (this.validators != null && !this.validators.get(index).test(arg)) {
						throw new CommandException("Received malformed argument");
					}

					out.append(arg);
				}
			}
		}

		private void formatUnvalidated(List<String> args, StringBuilder out) {
			for (int part : parts) {
				if (part >= 0) {
					out.append(literalParts[part]);
				} else {
					out.append(args.get(-(part+1)));
				}
			}
		}

		@Override
		public String toString() {
			List<String> args = new ArrayList<>(argCount);

			for (int i = 0; i < argCount; i++) {
				args.add("{{%d}}".formatted(i));
			}

			StringBuilder sb = new StringBuilder();
			formatUnvalidated(args, sb);

			return "ParameterizedText{name=\"%s\", text=\"%s\"}".formatted(this.getName(), sb);
		}
	}

	public static final class Embed extends TagInstance {
		private final EmbedBuilder embed;

		public Embed(String name, @Nullable String content, EmbedBuilder embed) {
			super(name);
			this.embed = embed;
			if (content != null) embed.setDescription(content);
		}

		@Override
		public int getArgCount() {
			return 0;
		}

		@Override
		public CompletableFuture<Message> send(CommandContext context, String arguments) {
			return context.channel().sendMessage(embed);
		}

		@Override
		public String toString() {
			return "Embed{name=\"%s\", embed=\"%s\"}".formatted(this.getName(), this.embed);
		}
	}

	public static final class Alias extends TagInstance {
		private final TagInstance delegate;

		public Alias(String name, TagInstance delegate) {
			super(name);

			if (delegate instanceof Alias) {
				throw new IllegalArgumentException("Cannot make an alias tag for alias tag \"%s\"!".formatted(delegate));
			}

			this.delegate = delegate;
		}

		public TagInstance getTarget() {
			return delegate;
		}

		@Override
		public int getArgCount() {
			return delegate.getArgCount();
		}

		@Override
		public String toString() {
			return "Alias{\"%s\" -> \"%s\"}".formatted(this.getName(), this.delegate.getName());
		}

		@Override
		public CompletableFuture<Message> send(CommandContext context, String arguments) throws CommandException {
			return this.delegate.send(context, arguments);
		}
	}
}
