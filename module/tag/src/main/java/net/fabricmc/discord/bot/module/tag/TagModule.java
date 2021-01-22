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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.apache.logging.log4j.Logger;
import org.eclipse.jgit.api.CloneCommand;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.MergeResult;
import org.eclipse.jgit.api.PullResult;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.javacord.api.DiscordApi;
import org.javacord.api.entity.channel.TextChannel;
import org.javacord.api.entity.message.MessageAuthor;
import org.javacord.api.event.message.MessageCreateEvent;
import org.javacord.api.listener.message.MessageCreateListener;

import net.fabricmc.discord.bot.DiscordBot;
import net.fabricmc.discord.bot.Module;
import net.fabricmc.discord.bot.config.ConfigKey;
import net.fabricmc.discord.bot.config.ValueSerializers;
import net.fabricmc.discord.bot.message.Mentions;
import net.fabricmc.tag.TagLoadResult;
import net.fabricmc.tag.TagParser;

public final class TagModule implements Module, MessageCreateListener {
	public static final ConfigKey<String> GIT_REPO = new ConfigKey<>("tags.gitRepo", ValueSerializers.STRING);
	public static final ConfigKey<Integer> GIT_PULL_DELAY = new ConfigKey<>("tags.gitPullDelay", ValueSerializers.rangedInt(-1, Integer.MAX_VALUE));
	private final ScheduledExecutorService asyncGitExecutor = Executors.newScheduledThreadPool(1, task -> {
		Thread ret = new Thread(task, "Tag reload thread");
		ret.setDaemon(true);

		return ret;
	});
	private volatile Map<String, TagInstance> tags = new HashMap<>(); // Concurrent event access
	private DiscordBot bot;
	private Logger logger;
	private Path gitDir;
	private Git git;
	private volatile boolean firstRun = true;

	@Override
	public String getName() {
		return "tags";
	}

	@Override
	public boolean shouldLoad() {
		return true;
	}

	@Override
	public void registerConfigEntries(DiscordBot bot) {
		// 30 seconds default
		bot.registerConfigEntry(GIT_PULL_DELAY, () -> 30);
		bot.registerConfigEntry(GIT_REPO, () -> "https://github.com/FabricMC/community/");
	}

	@Override
	public void setup(DiscordBot bot, DiscordApi api, Logger logger, Path dataDir) {
		this.bot = bot;
		this.logger = logger;
		this.gitDir = dataDir.resolve("git");

		// Setup git
		try {
			this.git = new Git(new FileRepositoryBuilder()
					.setGitDir(this.gitDir.resolve(".git").toFile())
					.readEnvironment()
					.build());
		} catch (IOException e) {
			logger.error("Failed to setup tags module", e);
			return;
		}

		api.addMessageCreateListener(this);

		// Load tags
		this.reloadTags();
	}

	private void reloadTags() {
		// Schedule the next task
		this.asyncGitExecutor.schedule(this::reloadTags, bot.getConfigEntry(GIT_PULL_DELAY), TimeUnit.SECONDS);

		try {
			if (Files.notExists(this.gitDir)) {
				final CloneCommand cloneCommand = Git.cloneRepository()
						.setURI(this.bot.getConfigEntry(GIT_REPO))
						.setDirectory(this.gitDir.toFile());

				this.logger.info("Cloning git repo");
				cloneCommand.call();
			}

			final PullResult result = this.git.pull().call();

			if (result.getMergeResult().getMergeStatus() == MergeResult.MergeStatus.ALREADY_UP_TO_DATE) {
				if (!this.firstRun) {
					return; // All up to date - no need to reload tags
				}

				synchronized (this) {
					this.firstRun = false;
				}
			}
		} catch (GitAPIException e) {
			e.printStackTrace();
		}

		this.logger.info("Reloading tags");

		// Load all tags
		final Path tagsDir = this.gitDir.resolve("tags");

		try {
			final TagLoadResult result = TagParser.loadTags(this.logger, tagsDir);
			this.logger.info("Loaded: {}, Malformed: {}", result.loadedTags().size(), result.malformedTags().size());
			// TODO: Create tag instances
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	@Override
	public void onMessageCreate(MessageCreateEvent event) {
		if (event.getMessageAuthor().isBotUser()) {
			return; // Do not dispatch tags from bots
		}

		final String content = event.getMessageContent();

		// Check for the ?? prefix in message
		if (content.startsWith(this.bot.getCommandPrefix().repeat(2))) {
			int nextSpace = content.indexOf(' ');

			if (nextSpace == -1) {
				nextSpace = content.length();
			}

			final String tagName = content.substring(2, nextSpace);
			this.handleTag(event.getMessageAuthor(), event.getChannel(), tagName, content.substring(nextSpace));
		}
	}

	private void handleTag(MessageAuthor author, TextChannel channel, String tagName, String arguments) {
		final TagInstance tag = this.tags.get(tagName);

		if (tag == null) {
			// TODO: Improve message
			// TODO: Remove sender's message and this message after time to replicate current logic
			channel.sendMessage("%s: Could not find tag of name %s".formatted(Mentions.createUserMention(author.getId()), tagName));
			return;
		}

		tag.send(author, channel, arguments);
	}
}
