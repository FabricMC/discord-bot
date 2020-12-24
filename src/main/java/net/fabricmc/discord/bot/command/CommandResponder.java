/*
 * Copyright (c) 2020 FabricMC
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

import java.util.concurrent.CompletableFuture;

import org.javacord.api.entity.emoji.Emoji;
import org.javacord.api.entity.message.Message;
import org.javacord.api.event.message.MessageEvent;

import net.fabricmc.discord.bot.util.message.Paginator;

/**
 * An object which provides methods to respond to a command that was sent to the bot.
 */
public final record CommandResponder(MessageEvent event) {
	/**
	 * Deletes a message.
	 *
	 * @return a future which will be completed when the message is deleted
	 */
	public CompletableFuture<Void> deleteMessage() {
		return this.event.deleteMessage();
	}

	/**
	 * Deletes a message.
	 *
	 * @param reason the audit log reason for deleting the message
	 * @return a future which will be completed when the message is deleted
	 */
	public CompletableFuture<Void> deleteMessage(String reason) {
		return this.event.deleteMessage(reason);
	}

	public CompletableFuture<Void> addEmote(String unicodeEmoji) {
		return this.event.addReactionToMessage(unicodeEmoji);
	}

	public CompletableFuture<Void> addEmote(Emoji emoji) {
		return this.event.addReactionToMessage(emoji);
	}

	/**
	 * Displays a paginator in the channel.
	 *
	 * @param paginator the paginator
	 * @return a future which will be completed when the paginator is displayed
	 */
	public CompletableFuture<Message> paginate(Paginator paginator) {
		return paginator.send(this.event.getChannel());
	}
}
