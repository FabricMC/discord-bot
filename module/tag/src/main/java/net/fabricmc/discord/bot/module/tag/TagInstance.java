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

import java.util.concurrent.CompletableFuture;

import org.javacord.api.entity.message.Message;
import org.javacord.api.entity.message.embed.EmbedBuilder;

import net.fabricmc.discord.bot.command.CommandContext;

public abstract class TagInstance {
	private final String name;

	public TagInstance(String name) {
		this.name = name;
	}

	public String getName() {
		return this.name;
	}

	public abstract CompletableFuture<Message> send(CommandContext context, String arguments);

	public static final class Text extends TagInstance {
		private final String text;

		public Text(String name, String text) {
			super(name);
			this.text = text;
		}

		@Override
		public CompletableFuture<Message> send(CommandContext context, String arguments) {
			// TODO
			return null;
		}

		@Override
		public String toString() {
			return "Text{name=\"%s\", text=\"%s\"}".formatted(this.getName(), this.text);
		}
	}

	public static final class Embed extends TagInstance {
		private final EmbedBuilder embed;

		public Embed(String name, String content, EmbedBuilder embed) {
			super(name);
			this.embed = embed;
		}

		@Override
		public CompletableFuture<Message> send(CommandContext context, String arguments) {
			// TODO
			return null;
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

		@Override
		public String toString() {
			return "Alias{\"%s\" -> \"%s\"}".formatted(this.getName(), this.delegate.getName());
		}

		@Override
		public CompletableFuture<Message> send(CommandContext context, String arguments) {
			return this.delegate.send(context, arguments);
		}
	}
}
