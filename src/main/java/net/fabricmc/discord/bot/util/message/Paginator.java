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

package net.fabricmc.discord.bot.util.message;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.javacord.api.entity.channel.TextChannel;
import org.javacord.api.entity.emoji.Emoji;
import org.javacord.api.entity.message.Message;
import org.javacord.api.entity.message.embed.EmbedBuilder;
import org.javacord.api.entity.user.User;
import org.javacord.api.event.message.reaction.ReactionAddEvent;
import org.jetbrains.annotations.Nullable;

/**
 * A utility to create paginated messages.
 *
 * <p>These messages contain emotes to allow switching pages and destroying the paginated message.
 * Paginated messages will expire after a certain amount of time and will drop all page emotes.
 */
public final class Paginator {
	private static final String ARROW_BACKWARDS_EMOTE = "arrow_forward";
	private static final String ARROW_FORWARDS_EMOTE = "arrow_backward";
	private static final String X_EMOTE = "x";
	private final List<String> pages;
	private final int pageCount;
	private final int timeout;
	private final long owner;
	/**
	 * The current page.
	 * The first page is `1` and this value will never be 0.
	 */
	private int currentPage = -1;
	private boolean sent;
	@Nullable
	private Message message;

	/**
	 * Creates a new paginator.
	 *
	 * @param pages the content of each page. This list cannot be empty
	 * @param timeout the timeout in which the paginator will automatically be destroyed
	 * @param owner the user who is allowed to interface with the paginator
	 */
	public Paginator(List<String> pages, int timeout, User owner) {
		this(pages, timeout, owner.getId());
	}

	/**
	 * Creates a new paginator.
	 *
	 * @param pages the content of each page. This list cannot be empty
	 * @param timeout the timeout in which the paginator will automatically be destroyed
	 * @param ownerSnowflake the snowflake of the user who is allowed to interface with the paginator
	 */
	public Paginator(List<String> pages, int timeout, long ownerSnowflake) {
		if (pages.isEmpty()) {
			throw new IllegalArgumentException("Cannot have a 0 page paginator");
		}

		this.pages = new ArrayList<>(pages);
		this.owner = ownerSnowflake;
		this.timeout = timeout;
		this.pageCount = pages.size();
	}

	public int getCurrentPage() {
		return this.currentPage;
	}

	public int getPageCount() {
		return this.pageCount;
	}

	/**
	 * @return the snowflake which may be used to refer to the user who is allow to interface with this paginator.
	 */
	public long getOwnerSnowflake() {
		return this.owner;
	}

	/**
	 * Sends the paginator to be displayed in a channel.
	 *
	 * @param channel the text channel to display the paginator in
	 * @return a future which contains the message that was created
	 */
	public CompletableFuture<Message> send(TextChannel channel) {
		Objects.requireNonNull(channel, "Channel cannot be null");

		return send0(channel);
	}

	public CompletableFuture<Boolean> nextPage() {
		if (this.message != null) {
			if (this.currentPage == this.pageCount) {
				return CompletableFuture.completedFuture(false);
			}

			// TODO: Test?
			return this.message.edit(createEmbed(this.pages.get(this.currentPage), this.currentPage + 1))
					.thenApply(ignored -> true);
		}

		return CompletableFuture.completedFuture(false);
	}

	public CompletableFuture<Boolean> previousPage() {
		if (this.message != null) {
			if (this.currentPage > 1) {
				if (this.pageCount < this.currentPage + 1) {
					// TODO: Test?
					return this.message.edit(createEmbed(this.pages.get(this.currentPage - 1), this.currentPage))
							.thenApply(ignored -> true);
				}
			}
		}

		return CompletableFuture.completedFuture(false);
	}

	/**
	 * Destroys the paginator.
	 *
	 * <p>When the paginator is destroyed, the current page won't be able to be changed and the emotes to move page or destroy the message will be removed.
	 *
	 * @return a future signifying when the paginator has been destroyed
	 */
	public CompletableFuture<Void> destroy() {
		if (this.message != null) {
			return this.message.removeAllReactions();
		}

		return CompletableFuture.completedFuture(null);
	}

	private CompletableFuture<Message> send0(TextChannel channel) {
		if (this.sent) {
			return CompletableFuture.failedFuture(new UnsupportedOperationException("Cannot display paginated message again!"));
		}

		this.sent = true;
		this.currentPage = 1;

		return channel.sendMessage(createEmbed(this.pages.get(0), this.currentPage)).thenApply(message -> {
			this.message = message;
			message.addReaction(ARROW_BACKWARDS_EMOTE);
			message.addReaction(X_EMOTE);
			message.addReaction(ARROW_FORWARDS_EMOTE);

			message.addReactionAddListener(this::handleEmojiAdded).removeAfter(this.timeout, TimeUnit.SECONDS).addRemoveHandler(this::destroy);

			return message;
		});
	}

	private void handleEmojiAdded(ReactionAddEvent event) {
		if (event.getUserId() == this.getOwnerSnowflake()) {
			final Emoji emoji = event.getEmoji();

			if (emoji.isUnicodeEmoji()) {
				if (emoji.equalsEmoji(ARROW_BACKWARDS_EMOTE)) {
					this.nextPage();
				} else if (emoji.equalsEmoji(ARROW_FORWARDS_EMOTE)) {
					this.previousPage();
				} else if (emoji.equalsEmoji(X_EMOTE)) {
					this.destroy();
				}
			}
		}

		// Remove the reaction
		event.removeReaction();
	}

	private static EmbedBuilder createEmbed(String content, int page) {
		return new EmbedBuilder(); // TODO:
	}
}
