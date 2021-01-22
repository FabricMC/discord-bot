/*
 * Copyright (c) 2020, 2021 FabricMC
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

package net.fabricmc.discord.bot.message;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.apache.logging.log4j.Logger;
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
 * <p>These messages contain emotes for controlling the paginator to allow switching pages and destroying the paginated message.
 * Paginated messages will expire after a certain amount of time and will destroy all page controls.
 */
public final class Paginator {
	private static final String ARROW_FORWARDS_EMOTE = "▶";
	private static final String ARROW_BACKWARDS_EMOTE = "◀";
	private static final String X_EMOTE = "❌";
	private final Logger logger;
	private final List<String> pages;
	private final int pageCount;
	private final int timeout;
	private final long owner;
	/**
	 * The current page.
	 * Like all things in java, this is zero indexed
	 */
	private int currentPage = -1;
	private boolean sent;
	/**
	 * The message the paginator is bound to.
	 */
	@Nullable
	private Message message;

	/**
	 * Creates a new paginator.
	 *
	 * @param logger the logger to log error messages to
	 * @param pages the content of each page. This list cannot be empty
	 * @param timeout the timeout in which the paginator will automatically be destroyed
	 * @param owner the user who is allowed to interface with the paginator
	 */
	public Paginator(Logger logger, List<String> pages, int timeout, User owner) {
		this(logger, pages, timeout, owner.getId());
	}

	/**
	 * Creates a new paginator.
	 *
	 * @param logger the logger to log error messages to
	 * @param pages the content of each page. This list cannot be empty
	 * @param timeout the timeout in which the paginator will automatically be destroyed
	 * @param ownerSnowflake the snowflake of the user who is allowed to interface with the paginator
	 */
	public Paginator(Logger logger, List<String> pages, int timeout, long ownerSnowflake) {
		this.logger = logger;

		if (pages.isEmpty()) {
			throw new IllegalArgumentException("Cannot have a 0 page paginator");
		}

		if (0 > timeout) {
			throw new IllegalArgumentException("Timeout cannot be negative value");
		}

		this.pages = new ArrayList<>(pages);
		this.owner = ownerSnowflake;
		this.timeout = timeout;
		this.pageCount = pages.size();
	}

	/**
	 * @return the paginator's current page
	 */
	public int getCurrentPage() {
		return this.currentPage;
	}

	/**
	 * @return the amount of pages the paginator has
	 */
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
	 * @return a future letting us know when the message is displayed
	 */
	public CompletableFuture<Message> send(TextChannel channel) {
		Objects.requireNonNull(channel, "Channel cannot be null");

		return send0(channel);
	}

	/**
	 * Moves the paginator to the next page.
	 *
	 * @return a future letting us know whether the page was successfully changed. If false the page was not changed.
	 */
	public CompletableFuture<Boolean> nextPage() {
		if (!this.sent) {
			return CompletableFuture.failedFuture(new UnsupportedOperationException("Cannot move unsent paginator to the next page"));
		}

		if (this.message != null) {
			if (this.getCurrentPage() + 1 >= this.pageCount) {
				return CompletableFuture.completedFuture(false);
			}

			this.currentPage++;

			// TODO: Test?
			return this.message.edit(this.getEmbed())
					.thenApply(ignored -> true);
		}

		return CompletableFuture.completedFuture(false);
	}

	/**
	 * Moves the paginator to the previous page.
	 *
	 * @return a future letting us know whether the page was successfully changed. If false the page was not changed.
	 */
	public CompletableFuture<Boolean> previousPage() {
		if (!this.sent) {
			return CompletableFuture.failedFuture(new UnsupportedOperationException("Cannot move unsent paginator to the previous page"));
		}

		if (this.message != null) {
			if (this.getCurrentPage() > 0) {
				this.currentPage--;

				// TODO: Test?
				return this.message.edit(this.getEmbed())
						.thenApply(ignored -> true);
			}
		}

		return CompletableFuture.completedFuture(false);
	}

	/**
	 * Destroys the paginator.
	 *
	 * <p>When the paginator is destroyed, the page will no longer be changeable and the emotes to move page or destroy the message will be removed.
	 *
	 * @return a future letting us know when the paginator has been destroyed
	 */
	public CompletableFuture<Void> destroy() {
		if (!this.sent) {
			return CompletableFuture.failedFuture(new UnsupportedOperationException("Cannot destroy unsent paginator"));
		}

		if (this.message != null) {
			return this.message.removeAllReactions().thenApply(_v -> {
				this.message = null;
				return null;
			});
		}

		return CompletableFuture.completedFuture(null);
	}

	private CompletableFuture<Message> send0(TextChannel channel) {
		if (this.sent) {
			return CompletableFuture.failedFuture(new UnsupportedOperationException("Cannot display paginator again!"));
		}

		this.sent = true;
		this.currentPage = 0;

		// Send the message to create the paginator on first page
		return channel.sendMessage(this.getEmbed()).thenCompose(message -> {
			this.message = message;

			// Add the control emotes and then setup the listeners for said emotes
			return CompletableFuture.allOf(
					message.addReaction(ARROW_BACKWARDS_EMOTE),
					message.addReaction(X_EMOTE),
					message.addReaction(ARROW_FORWARDS_EMOTE)
			).thenApply(_v -> {
				message.addReactionAddListener(this::reactionAdded).removeAfter(this.timeout, TimeUnit.SECONDS).addRemoveHandler(this::destroy);
				return message;
			});
		}).exceptionally(e -> {
			this.logger.error("Failed to setup paginator", e);
			return null;
		});
	}

	private void reactionAdded(ReactionAddEvent event) {
		// Let ourselves add the emojis for controls without removal
		if (event.getApi().getYourself().getId() == event.getUserId()) {
			return;
		}

		if (event.getUserId() == this.getOwnerSnowflake()) {
			final Emoji emoji = event.getEmoji();

			if (emoji.equalsEmoji(ARROW_BACKWARDS_EMOTE)) {
				this.previousPage().exceptionally(e -> {
					this.logger.error("Failed to move paginator to previous page", e);
					return null;
				});
			} else if (emoji.equalsEmoji(ARROW_FORWARDS_EMOTE)) {
				this.nextPage().exceptionally(e -> {
					this.logger.error("Failed to move paginator to next page", e);
					return null;
				});
			} else if (emoji.equalsEmoji(X_EMOTE)) {
				this.destroy().exceptionally(e -> {
					this.logger.error("Failed to destroy paginator", e);
					return null;
				});
			}
		}

		event.removeReaction().exceptionally(e -> {
			this.logger.error("Failed to remove reaction from paginator event", e);
			return null;
		});
	}

	private EmbedBuilder getEmbed() {
		final String content = this.pages.get(this.getCurrentPage());

		return new EmbedBuilder()
				.setDescription(content)
				.setFooter("Page %s/%s".formatted(this.getCurrentPage() + 1, this.getPageCount()));
	}
}
