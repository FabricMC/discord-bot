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
import org.javacord.api.DiscordApi;
import org.javacord.api.entity.channel.TextChannel;
import org.javacord.api.entity.message.Message;
import org.javacord.api.entity.server.Server;

import net.fabricmc.discord.bot.DiscordBot;
import net.fabricmc.discord.bot.Module;
import net.fabricmc.discord.bot.config.ConfigKey;
import net.fabricmc.discord.bot.config.ValueSerializers;
import net.fabricmc.discord.bot.util.HttpUtil;

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
	private static final String MC_VERSION_HOST = "launchermeta.mojang.com";
	private static final String MC_VERSION_PATH = "/mc/game/version_manifest.json";
	private static final String FABRIC_VERSION_HOST = "meta.fabricmc.net";
	private static final String FABRIC_VERSION_PATH = "/v2/versions/intermediary/%s";
	private static final String NESSAGE = "A new Minecraft %s is out: %s"; // args: kind, version
	private static final String KIND_RELEASE = "release";
	private static final String KIND_SNAPSHOT = "snapshot";

	private static final Logger LOGGER = LogManager.getLogger(McVersionModule.class);
	private static final ConfigKey<Long> ANNOUNCE_CHANNEL = new ConfigKey<>("mcversion.announceChannel", ValueSerializers.LONG);
	private static final ConfigKey<String> ANNOUNCED_RELEASE_VERSION = new ConfigKey<>("mcversion.announcedReleaseVersion", ValueSerializers.STRING);
	private static final ConfigKey<String> ANNOUNCED_SNAPSHOT_VERSION = new ConfigKey<>("mcversion.announcedSnapshotVersion", ValueSerializers.STRING);

	private DiscordBot bot;
	private Future<?> future;
	private volatile TextChannel channel;
	private final Set<String> announcedVersions = Collections.newSetFromMap(new ConcurrentHashMap<>());

	@Override
	public String getName() {
		return "mcversion";
	}

	@Override
	public boolean shouldLoad() {
		return true;
	}

	@Override
	public void registerConfigEntries(DiscordBot bot) {
		bot.registerConfigEntry(ANNOUNCE_CHANNEL, () -> -1L);
		// TODO: subscribe to config changes

		bot.registerConfigEntry(ANNOUNCED_RELEASE_VERSION, () -> "0");
		bot.registerConfigEntry(ANNOUNCED_SNAPSHOT_VERSION, () -> "0");
	}

	@Override
	public void setup(DiscordBot bot, DiscordApi api, Logger logger, Path dataDir) {
		this.bot = bot;

		bot.getActiveHandler().registerReadyHandler(this::onReady);
		bot.getActiveHandler().registerGoneHandler(this::onGone);
	}

	private void onReady(Server server, long prevActive) {
		long channelId = bot.getConfigEntry(ANNOUNCE_CHANNEL);
		if (channelId < 0) return;

		TextChannel channel = server.getTextChannelById(channelId).orElse(null);

		if (channel == null) {
			LOGGER.warn("invalid announce channel: {}", channelId);
			return;
		}

		this.channel = channel;
		future = bot.getScheduledExecutor().scheduleWithFixedDelay(this::update, 0, UPDATE_DELAY, TimeUnit.SECONDS);
	}

	private void onGone(Server server) {
		channel = null;

		if (future != null) {
			future.cancel(false);
			future = null;
		}
	}

	private void update() {
		try {
			update0();
		} catch (Throwable t) {
			LOGGER.warn("mc version check failed", t);
		}
	}

	private void update0() throws IOException, URISyntaxException, InterruptedException {
		HttpResponse<InputStream> response = HttpUtil.makeRequest(MC_VERSION_HOST, MC_VERSION_PATH);
		if (response.statusCode() != 200) throw new IOException("request failed with code "+response.statusCode());

		String latestRelease = null;
		String latestSnapshot = null;

		try (JsonReader reader = new JsonReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
			reader.beginObject();

			while (reader.hasNext()) {
				if (!reader.nextName().equals("latest")) {
					reader.skipValue();
					continue;
				}

				reader.beginObject();

				while (reader.hasNext()) {
					switch (reader.nextName()) {
					case "release" -> latestRelease = reader.nextString();
					case "snapshot" -> latestSnapshot = reader.nextString();
					default -> reader.skipValue();
					}
				}

				break;
			}
		}

		updateSpecific(KIND_RELEASE, latestRelease, ANNOUNCED_RELEASE_VERSION);
		updateSpecific(KIND_SNAPSHOT, latestSnapshot, ANNOUNCED_SNAPSHOT_VERSION);
	}

	private void updateSpecific(String kind, String latestVersion, ConfigKey<String> announcedVersionKey) throws IOException, URISyntaxException, InterruptedException {
		if (latestVersion == null // no version specified
				|| latestVersion.equals(bot.getConfigEntry(announcedVersionKey)) // same as last announced
				|| isOldVersion(latestVersion)) { // already known to Fabric, mcmeta glitch
			return;
		}

		TextChannel channel = this.channel;
		if (channel == null) return;

		if (announcedVersions.add(latestVersion)) { // already posted by this bot process, e.g. as a different kind or due to a mcmeta glitch
			Message message = channel.sendMessage(NESSAGE.formatted(kind, latestVersion)).join();
			//message.crossPost(); TODO: pending release of https://github.com/Javacord/Javacord/pull/650
		}

		bot.setConfigEntry(announcedVersionKey, latestVersion);
	}

	private static boolean isOldVersion(String version) throws IOException, URISyntaxException, InterruptedException {
		if (version.indexOf('/') >= 0)  throw new IllegalArgumentException("invalid mc version: "+version);

		HttpResponse<InputStream> response = HttpUtil.makeRequest(FABRIC_VERSION_HOST, FABRIC_VERSION_PATH.formatted(version));
		if (response.statusCode() != 200) return false;

		try (JsonReader reader = new JsonReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
			reader.beginArray();
			return reader.hasNext();
		}
	}
}
