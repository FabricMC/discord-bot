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

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

import net.fabricmc.discord.bot.util.CommonEmotes;
import net.fabricmc.discord.bot.util.DiscordUtil;
import net.fabricmc.discord.io.Channel;
import net.fabricmc.discord.io.DiscordException;
import net.fabricmc.discord.io.Emoji;
import net.fabricmc.discord.io.GlobalEventHolder.MessageReactionAddHandler;
import net.fabricmc.discord.io.GlobalEventHolder.TemporaryRegistration;
import net.fabricmc.discord.io.Message;
import net.fabricmc.discord.io.MessageEmbed;
import net.fabricmc.discord.io.User;

/**
 * A utility to create paginated messages.
 *
 * <p>These messages contain emotes for controlling the paginator to allow switching pages and destroying the paginated message.
 * Paginated messages will expire after a certain amount of time and will destroy all page controls.
 */
public final class Paginator implements MessageReactionAddHandler {
	private static final Logger LOGGER = LogManager.getLogger(Paginator.class);

	private final Logger logger;
	private final @Nullable String title;
	private final @Nullable String footer;
	private final List<Page> pages;
	private final int timeout; // in s
	private final long owner;
	private final boolean deleteOnFinish;
	/**
	 * The current page.
	 * Like all things in java, this is zero indexed
	 */
	private int currentPage;
	/**
	 * The message the paginator is bound to.
	 */
	@Nullable
	private volatile Message message;
	private volatile TemporaryRegistration tempEventReg;

	/**
	 * Creates a new paginator.
	 *
	 * @param logger the logger to log error messages to
	 * @param pages the content of each page. This list cannot be empty
	 * @param timeout the timeout in which the paginator will automatically be destroyed
	 * @param ownerSnowflake the snowflake of the user who is allowed to interface with the paginator
	 */
	private Paginator(Logger logger, String title, String footer, List<Page> pages, int timeout, long ownerSnowflake, boolean deleteOnFinish) {
		this.logger = logger;

		if (pages.isEmpty()) {
			throw new IllegalArgumentException("Cannot have a 0 page paginator");
		}

		if (0 > timeout) {
			throw new IllegalArgumentException("Timeout cannot be negative value");
		}

		this.title = title;
		this.footer = footer;
		this.pages = pages;
		this.owner = ownerSnowflake;
		this.timeout = timeout;
		this.deleteOnFinish = deleteOnFinish;
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
		return pages.size();
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
	public Message send(Channel channel) {
		Objects.requireNonNull(channel, "Channel cannot be null");
		if (!channel.getType().text) throw new IllegalArgumentException("channel is not a text channel");

		return send0(channel);
	}

	/**
	 * Moves the paginator to the next page.
	 *
	 * @return a future letting us know whether the page was successfully changed. If false the page was not changed.
	 */
	public boolean nextPage(boolean repost) {
		Message message = this.message;

		if (message != null && pages.size() > 1) {
			currentPage = (currentPage + 1) % pages.size();

			return update(repost);
		}

		return false;
	}

	/**
	 * Moves the paginator to the previous page.
	 *
	 * @return a future letting us know whether the page was successfully changed. If false the page was not changed.
	 */
	public boolean previousPage(boolean repost) {
		Message message = this.message;

		if (message != null && pages.size() > 1) {
			currentPage = (currentPage + pages.size() - 1) % pages.size();

			return update(repost);
		}

		return false;
	}

	private boolean update(boolean repost) {
		Message message = this.message;
		if (message == null) return false;

		if (repost) {
			message.delete("page update");
			send0(message.getChannel());
		} else {
			message.edit(getEmbed());
		}

		return true;
	}

	/**
	 * Destroys the paginator.
	 *
	 * <p>When the paginator is destroyed, the page will no longer be changeable and the emotes to move page or destroy the message will be removed.
	 *
	 * @return a future letting us know when the paginator has been destroyed
	 */
	public void destroy() {
		Message message = this.message;

		if (message != null) {
			TemporaryRegistration tempEventReg = this.tempEventReg;
			if (tempEventReg != null) tempEventReg.cancel();

			if (deleteOnFinish) {
				message.delete("paginator destroyed");
			} else if (DiscordUtil.canRemoveReactions(message.getChannel())) {
				message.removeAllReactions();
			}

			this.message = null;
		}
	}

	private Message send0(Channel channel) {
		// Send the message to create the paginator on first page
		Message ret = channel.send(this.getEmbed());

		if (pages.size() > 1) {
			this.message = ret;

			List<Emoji> emotes = new ArrayList<>(3);

			// add control emotes
			emotes.add(Emoji.fromUnicode(CommonEmotes.ARROW_BACKWARDS));

			if (deleteOnFinish || DiscordUtil.canRemoveReactions(ret.getChannel())) {
				emotes.add(Emoji.fromUnicode(CommonEmotes.X));
			}

			emotes.add(Emoji.fromUnicode(CommonEmotes.ARROW_FORWARDS));

			tempEventReg = channel.getDiscord().getGlobalEvents().registerTemporary(MessageReactionAddHandler.class,
					this,
					this::destroy,
					Duration.ofSeconds(timeout));

			ret.addReactions(emotes);
		}

		return ret;
	}

	@Override
	public void onMessageReactionAdd(long messageId, Emoji emoji, long userId, Channel channel) {
		Message message = this.message;
		if (message == null || messageId != message.getId()) return;

		// Let ourselves add the emojis for controls without removal
		if (emoji.getDiscord().getYourself().getId() == userId) {
			return;
		}

		boolean canRemoveReactions = DiscordUtil.canRemoveReactions(channel);

		if (userId == this.getOwnerSnowflake()) {
			switch (emoji.getName()) {
			case CommonEmotes.ARROW_BACKWARDS -> previousPage(!canRemoveReactions);
			case CommonEmotes.ARROW_FORWARDS -> nextPage(!canRemoveReactions);
			case CommonEmotes.X -> destroy();
			}
		}

		if (canRemoveReactions) { // requires the user to remove reactions manually (double click to advance)
			message.removeReaction(emoji, userId);
		}
	}

	private MessageEmbed getEmbed() {
		final Page page = this.pages.get(this.getCurrentPage());

		MessageEmbed.Builder ret = new MessageEmbed.Builder()
				.description(page.content)
				.footer("Page %s/%s%s%s".formatted(this.getCurrentPage() + 1, this.getPageCount(), footer != null ? " - " : "", footer != null ? footer : ""));

		if (title != null) ret.title(title);
		if (page.thumbnailUrl != null) ret.thumbnail(page.thumbnailUrl);

		return ret.build();
	}

	public static final class Builder {
		private Logger logger = LOGGER;
		private @Nullable String title;
		private @Nullable String footer;
		private final List<Page> pages = new ArrayList<>();
		private int timeout = 200; // in s
		private final long ownerId;
		private boolean deleteOnFinish;

		public Builder(User owner) {
			this.ownerId = owner.getId();
		}

		public Builder(long ownerId) {
			this.ownerId = ownerId;
		}

		public Builder title(@Nullable String title) {
			this.title = title;

			return this;
		}

		public Builder title(String format, Object... args) {
			return title(String.format(format, args));
		}

		public Builder footer(@Nullable String footer) {
			this.footer = footer;

			return this;
		}

		public Builder footer(String format, Object... args) {
			return footer(String.format(format, args));
		}

		public Builder pages(Collection<Page> pages) {
			pages.addAll(pages);

			return this;
		}

		public Builder plainPages(Collection<String> contents) {
			for (String content : contents) {
				pages.add(new Page(content));
			}

			return this;
		}

		public Builder page(Page page) {
			Objects.requireNonNull(page, "page cannot be null");

			pages.add(page);

			return this;
		}

		public Builder page(Page.Builder pageBuilder) {
			return page(pageBuilder.build());
		}

		public Builder page(CharSequence content) {
			pages.add(new Page(content.toString()));

			return this;
		}

		public Builder page(String format, Object... args) {
			return page(String.format(format, args));
		}

		public Builder logger(Logger logger) {
			Objects.requireNonNull(logger, "logger cannot be null");

			this.logger = logger;

			return this;
		}

		public Builder timeoutSec(int timeoutSec) {
			this.timeout = timeoutSec;

			return this;
		}

		public Builder deleteOnFinish(boolean value) {
			this.deleteOnFinish = value;

			return this;
		}

		public Paginator build() {
			return new Paginator(logger, title, footer, pages, timeout, ownerId, deleteOnFinish);
		}

		public void buildAndSend(Channel channel) throws DiscordException {
			build().send(channel);
		}
	}

	public static final class Page {
		public final String content;
		public final @Nullable String thumbnailUrl;

		private Page(String content) {
			this(content, null);
		}

		private Page(String content, String thumbnailUrl) {
			this.content = content;
			this.thumbnailUrl = thumbnailUrl;
		}

		public static final class Builder {
			private final String content;
			private String thumbnailUrl;

			public Builder(CharSequence content) {
				this.content = content.toString();
			}

			public Builder(String format, Object... args) {
				this.content = String.format(format, args);
			}

			public Builder thumbnail(@Nullable String url) {
				this.thumbnailUrl = url;

				return this;
			}

			public Page build() {
				return new Page(content, thumbnailUrl);
			}
		}
	}
}
