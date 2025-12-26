/*
 * Copyright (c) 2021, 2022 FabricMC
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

package net.fabricmc.discord.bot.module.mcversion;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URISyntaxException;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.google.gson.stream.JsonReader;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import net.fabricmc.discord.bot.CachedMessage;
import net.fabricmc.discord.bot.DiscordBot;
import net.fabricmc.discord.bot.Module;
import net.fabricmc.discord.bot.config.ConfigKey;
import net.fabricmc.discord.bot.config.ValueSerializers;
import net.fabricmc.discord.bot.util.HttpUtil;
import net.fabricmc.discord.io.Channel;
import net.fabricmc.discord.io.Discord;
import net.fabricmc.discord.io.Message;
import net.fabricmc.discord.io.MessageEmbed;
import net.fabricmc.discord.io.Server;

/**
 * Checks for and announces new MC versions
 *
 * This works as follows:
 * <ol>
 * <li>grab latest MC version from the launcher metadata API
 * <li>require that the version is not the same as in the bot DB
 * <li>require that Fabric Meta's intermediary records don't already list the version, indicating a bogus detection
 * <li>require that the version hasn't been announced by the bot's current process
 * <li>announce the version to the configured channel
 * <li>publish that announcement if possible
 * <li>record new version to avoid repeats
 * </ol>
 */
public final class McVersionModule implements Module {
	private static final int UPDATE_DELAY = 30; // in s
	private static final int NEWS_UPDATE_CYCLES = 4; // check news every n updates
	private static final int LAUNCHER_NEWS_UPDATE_CYCLES = 2; // check launcher news every n updates

	private static final String MESSAGE = "A new Minecraft %s is out: %s"; // args: kind, version
	static final String KIND_RELEASE = "release";
	static final String KIND_SNAPSHOT = "snapshot";
	static final String KIND_PENDING = "pending";

	private static final String FABRIC_VERSION_HOST = "meta.fabricmc.net";
	private static final String FABRIC_VERSION_PATH = "/v2/versions/intermediary/%s";

	private static final Logger LOGGER = LogManager.getLogger(McVersionModule.class);

	// global properties
	private static final ConfigKey<Long> ANNOUNCE_CHANNEL = new ConfigKey<>("mcversion.announceChannel", ValueSerializers.LONG);
	private static final ConfigKey<Long> UPDATE_CHANNEL = new ConfigKey<>("mcversion.updateChannel", ValueSerializers.LONG);
	private static final ConfigKey<String> ANNOUNCED_RELEASE_VERSION = new ConfigKey<>("mcversion.announcedReleaseVersion", ValueSerializers.STRING);
	private static final ConfigKey<String> ANNOUNCED_SNAPSHOT_VERSION = new ConfigKey<>("mcversion.announcedSnapshotVersion", ValueSerializers.STRING);
	private static final ConfigKey<String> ANNOUNCED_PENDING_VERSION = new ConfigKey<>("mcversion.announcedPendingVersion", ValueSerializers.STRING);

	// user properties
	static final ConfigKey<String> DEFAULT_VERSION = new ConfigKey<>("mcVersion.defaultVersion", ValueSerializers.STRING);

	private DiscordBot bot;
	private McVersionRepo repo;
	private Future<?> future;
	private volatile Server server;
	private volatile Channel announceChannel;
	private volatile Channel updateChannel;
	private int newsCycleCouter;

	private final Set<String> announcedVersions = Collections.newSetFromMap(new ConcurrentHashMap<>());

	final MetaFetcher metaFetcher = new MetaFetcher(this);
	final NewsFetcher newsFetcher = new NewsFetcher(this);
	final LauncherNewsFetcher launcherNewsFetcher = new LauncherNewsFetcher(this);

	@Override
	public String getName() {
		return "mcversion";
	}

	@Override
	public void registerConfigEntries(DiscordBot bot) {
		bot.registerConfigEntry(ANNOUNCE_CHANNEL, () -> -1L);
		bot.registerConfigEntry(UPDATE_CHANNEL, () -> -1L);
		bot.registerConfigEntry(ANNOUNCED_RELEASE_VERSION, () -> "0");
		bot.registerConfigEntry(ANNOUNCED_SNAPSHOT_VERSION, () -> "0");
		bot.registerConfigEntry(ANNOUNCED_PENDING_VERSION, () -> "0");

		newsFetcher.register(bot);
	}

	@Override
	public void onConfigValueChanged(ConfigKey<?> key, Object value) {
		if (key == ANNOUNCE_CHANNEL) {
			announceChannel = getChannel(server, "announce", (long) value);
		} else if (key == UPDATE_CHANNEL) {
			updateChannel = getChannel(server, "update", (long) value);
		}
	}

	private static Channel getChannel(Server server, String type, long id) {
		if (server == null || id <= 0) return null;

		Channel channel = server.getTextChannel(id);

		if (channel == null) {
			LOGGER.warn("invalid {} channel: {}", type, id);
		}

		return channel;
	}

	@Override
	public void setup(DiscordBot bot, Discord discord, Logger logger, Path dataDir) {
		this.bot = bot;
		this.repo = new McVersionRepo(bot);

		bot.getActiveHandler().registerReadyHandler(this::onReady);
		bot.getActiveHandler().registerGoneHandler(this::onGone);

		bot.registerCommand(new McVersionCommand(this));
		bot.registerCommand(new SetMcVersionCommand(repo));
		bot.registerCommand(new LauncherNewsCommand(this));
	}

	DiscordBot getBot() {
		return bot;
	}

	McVersionRepo getRepo() {
		return repo;
	}

	Channel getAnnounceChannel() {
		return announceChannel;
	}

	Channel getUpdateChannel() {
		return updateChannel;
	}

	private void onReady(Server server, long prevActive) {
		this.server = server;
		this.announceChannel = getChannel(server, "announce", bot.getConfigEntry(ANNOUNCE_CHANNEL));
		this.updateChannel = getChannel(server, "update", bot.getConfigEntry(UPDATE_CHANNEL));

		newsFetcher.init(bot.getConfigEntry(ANNOUNCED_SNAPSHOT_VERSION));

		if (announceChannel != null || updateChannel != null) {
			future = bot.getScheduledExecutor().scheduleWithFixedDelay(this::update, 0, UPDATE_DELAY, TimeUnit.SECONDS);
		}
	}

	private void onGone(Server server) {
		this.server = null;
		announceChannel = null;
		updateChannel = null;

		if (future != null) {
			future.cancel(false);
			future = null;
		}
	}

	private void update() {
		if (announceChannel == null && updateChannel == null) return;

		try {
			metaFetcher.update();
			updateSpecific(KIND_RELEASE, metaFetcher.getLatestRelease(), ANNOUNCED_RELEASE_VERSION);
			updateSpecific(KIND_SNAPSHOT, metaFetcher.getLatestSnapshot(), ANNOUNCED_SNAPSHOT_VERSION, ANNOUNCED_RELEASE_VERSION);

			if (updateChannel != null
					&& newsCycleCouter++ % NEWS_UPDATE_CYCLES == 0) {
				newsFetcher.update();
			}

			if (updateChannel != null
					&& newsCycleCouter % LAUNCHER_NEWS_UPDATE_CYCLES == 0) {
				launcherNewsFetcher.update();
			}
		} catch (Throwable t) {
			HttpUtil.logError("mc version check failed", t, LOGGER);
		}
	}

	private void updateSpecific(String kind, String latestVersion, ConfigKey<String> announcedVersionKey) throws IOException, URISyntaxException, InterruptedException {
		updateSpecific(kind, latestVersion, announcedVersionKey, null);
	}

	private void updateSpecific(String kind, String latestVersion, ConfigKey<String> announcedVersionKey, ConfigKey<String> altAnnouncedVersionKey) throws IOException, URISyntaxException, InterruptedException {
		String oldVersion;

		if (latestVersion == null // no version specified
				|| latestVersion.equals(oldVersion = bot.getConfigEntry(announcedVersionKey)) // same as last announced
				|| isOldVersion(latestVersion) // already known to Fabric, mcmeta glitch
				|| announcedVersions.contains(latestVersion)) { // already posted by this instance
			return;
		}

		// suppress announcing release as snapshot
		if (altAnnouncedVersionKey != null && latestVersion.equals(bot.getConfigEntry(altAnnouncedVersionKey))) {
			bot.setConfigEntry(announcedVersionKey, latestVersion);
			return;
		}

		LOGGER.info("Announcing MC-Meta {} {} -> {}", kind, oldVersion, latestVersion);

		String msgText = MESSAGE.formatted(kind, latestVersion);

		if (!sendAnnouncement(announceChannel, msgText)
				& !sendAnnouncement(updateChannel, msgText)) { // deliberate & to force evaluating both
			return; // skip announced version record update
		}

		announcedVersions.add(latestVersion);
		bot.setConfigEntry(announcedVersionKey, latestVersion);
	}

	static boolean isOldVersion(String version) throws IOException, URISyntaxException, InterruptedException {
		if (version.indexOf('/') >= 0)  throw new IllegalArgumentException("invalid mc version: "+version);

		HttpResponse<InputStream> response = HttpUtil.makeRequest(HttpUtil.toUri(FABRIC_VERSION_HOST, FABRIC_VERSION_PATH.formatted(version)));

		if (response.statusCode() != 200) {
			LOGGER.warn("MC version verification request against Fabric Meta failed: {}", response.statusCode());
			response.body().close();
			return false;
		}

		try (JsonReader reader = new JsonReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
			reader.beginArray();
			return reader.hasNext();
		}
	}

	boolean sendAnnouncement(Channel channel, String msg) {
		if (channel == null) return false;

		for (CachedMessage m : bot.getMessageIndex().getAllByAuthor(bot.getUserHandler().getBotDiscordUserId(), channel, false)) {
			if (m.getContent().equals(msg)) {
				LOGGER.warn("Duplicate announcement to {}: {}", channel.getId(), msg);
				return true; // pretend it succeeded, avoids retries and it was already announced in the past anyway
			}
		}

		Message message;

		try {
			message = channel.send(msg);
		} catch (Throwable t) {
			LOGGER.warn("Announcement failed", t);
			return false;
		}

		//if (channel.getType() == ChannelType.SERVER_NEWS_CHANNEL) { TODO: check once javacord exposes this properly
		try {
			message.crosspost();
		} catch (Exception e) {
			LOGGER.warn("Message crossposting failed: "+e); // fails with MissingPermissionsException for non-news channel
		}
		//}

		return true;
	}

	boolean sendAnnouncement(Channel channel, MessageEmbed embed) {
		if (channel == null) return false;

		Message message;

		try {
			message = channel.send(embed);
		} catch (Throwable t) {
			LOGGER.warn("Announcement failed", t);
			return false;
		}

		try {
			message.crosspost();
		} catch (Exception e) {
			LOGGER.warn("Message crossposting failed: "+e); // fails with MissingPermissionsException for non-news channel
		}

		return true;
	}
}
