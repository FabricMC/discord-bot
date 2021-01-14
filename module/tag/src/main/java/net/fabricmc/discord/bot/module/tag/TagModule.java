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
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
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
import org.jetbrains.annotations.Nullable;
import org.spongepowered.configurate.ConfigurateException;
import org.spongepowered.configurate.hocon.HoconConfigurationLoader;
import org.spongepowered.configurate.serialize.TypeSerializerCollection;

import net.fabricmc.discord.bot.DiscordBot;
import net.fabricmc.discord.bot.Module;
import net.fabricmc.discord.bot.message.Mentions;
import net.fabricmc.discord.bot.serialization.BotTypeSerializers;
import net.fabricmc.tag.TagData;

public final class TagModule implements Module, MessageCreateListener {
	private final ScheduledExecutorService asyncGitExecutor = Executors.newScheduledThreadPool(1, task -> {
		Thread ret = new Thread(task, "Tag reload thread");
		ret.setDaemon(true);

		return ret;
	});
	private final TypeSerializerCollection serializers = TypeSerializerCollection.builder()
			.registerAll(BotTypeSerializers.SERIALIZERS)
			.register(TagData.class, TagData.SERIALIZER)
			.build();
	private volatile Map<String, Tag> tags = new HashMap<>(); // Concurrent event access
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
	public boolean setup(DiscordBot bot, DiscordApi api, Logger logger, Path dataDir) {
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
			e.printStackTrace();
			return false;
		}

		api.addMessageCreateListener(this);
		// TODO: Configurable delay? - set to 90s for now
		this.asyncGitExecutor.scheduleWithFixedDelay(this::reloadTags, 0L, 90L, TimeUnit.SECONDS);

		return true;
	}

	// Always called async
	private void reloadTags() {
		this.logger.info("Trying to reload tags from git");

		try {
			if (Files.notExists(this.gitDir)) {
				final CloneCommand cloneCommand = Git.cloneRepository()
						.setURI("https://github.com/FabricMC/community/") // FIXME: Hardcoded - Point to a new repo for testing
						.setDirectory(this.gitDir.toFile());

				this.logger.info("Cloning git repo");
				cloneCommand.call();
			}

			final PullResult result = this.git.pull().call();

			if (result.getMergeResult().getMergeStatus() == MergeResult.MergeStatus.ALREADY_UP_TO_DATE) {
				this.logger.info("Git repo is up to date.");

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
		final Map<String, TagData> loadedData = new HashMap<>();

		try {
			Files.walkFileTree(tagsDir, new SimpleFileVisitor<>() {
				@Override
				public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
					try {
						if (file.getFileName().endsWith(".tag")) {
							logger.debug("Loading tag {}", file.getFileName());

							final TagData data = HoconConfigurationLoader.builder()
									.path(file)
									.defaultOptions(options -> options.serializers(serializers))
									.build()
									.load()
									.get(TagData.class);

							if (data == null) {
								return FileVisitResult.CONTINUE;
							}

							loadedData.put(data.name(), data);
						}
					} catch (ConfigurateException e) {
						e.printStackTrace();
					}

					return FileVisitResult.CONTINUE;
				}
			});
		} catch (IOException e) {
			e.printStackTrace();
		}

		final Map<FailureReason, Set<TagData>> removedTags = new EnumMap<>(FailureReason.class);
		final Map<String, Tag> resolved = new HashMap<>();

		// Resolve all normal tags
		loadedData.values().removeIf(data -> {
			if (data instanceof TagData.Alias) {
				return false;
			}

			Tag tag;

			if (data instanceof TagData.Text text) {
				tag = new Tag.Text(text.name(), text.text());
			} else {
				tag = new Tag.Embed(data.name(), ((TagData.Embed) data).embed());
			}

			// Do not replace tags
			if (resolved.putIfAbsent(data.name(), tag) != null) {
				removedTags.computeIfAbsent(FailureReason.DUPLICATE, _k -> new HashSet<>()).add(data);
			}

			return true;
		});

		// Resolve aliases
		loadedData.values().removeIf(tag -> {
			if (tag instanceof TagData.Alias) {
				if (resolved.containsKey(((TagData.Alias) tag).target())) {
					@Nullable final Tag delegate = resolved.get(((TagData.Alias) tag).target());

					if (delegate == null) {
						removedTags.computeIfAbsent(FailureReason.NO_ALIAS_TARGET, _k -> new HashSet<>()).add(tag);
						return true;
					}

					if (resolved.putIfAbsent(tag.name(), new Tag.Alias(tag.name(), delegate)) != null) {
						removedTags.computeIfAbsent(FailureReason.DUPLICATE, _k -> new HashSet<>()).add(tag);
					}
				}
			} else {
				removedTags.computeIfAbsent(FailureReason.UNKNOWN, _k -> new HashSet<>()).add(tag);
			}

			return true;
		});

		// Apply reload on serial executor thread
		this.bot.getSerialExecutor().execute(() -> {
			this.logger.info("Applying {} tag(s)", resolved.size());

			synchronized (this) {
				this.tags.clear();
				this.tags.putAll(resolved);
			}
		});

		// TODO: List the fatalities
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
		final Tag tag = this.tags.get(tagName);

		if (tag != null) {
			tag.send(author, channel, arguments);
			return;
		}

		// TODO: Improve message
		// TODO: Remove sender's message and this message after time to replicate current logic
		channel.sendMessage("%s: Could not find tag of name %s".formatted(Mentions.createUserMention(author.getId()), tagName));
	}

	private enum FailureReason {
		DUPLICATE,
		NO_ALIAS_TARGET,
		UNKNOWN
	}
}
