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

package net.fabricmc.discord.bot;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

import net.fabricmc.discord.bot.config.ConfigKey;
import net.fabricmc.discord.bot.config.ValueSerializers;
import net.fabricmc.discord.io.Channel;
import net.fabricmc.discord.io.Server;

public final class LogHandler {
	private static final Logger LOGGER = LogManager.getLogger(LogHandler.class);
	private static final ConfigKey<Long> LOG_CHANNEL = new ConfigKey<>("logChannel", ValueSerializers.LONG);

	private final DiscordBot bot;
	private volatile Channel logChannel;

	LogHandler(DiscordBot bot) {
		this.bot = bot;

		bot.registerConfigEntry(LOG_CHANNEL, () -> -1L);
		// TODO: subscribe to config changes

		bot.getActiveHandler().registerReadyHandler(this::onReady);
		bot.getActiveHandler().registerGoneHandler(this::onGone);
	}

	private void onReady(Server server, long prevActive) {
		long channelId = bot.getConfigEntry(LOG_CHANNEL);

		if (channelId >= 0) {
			Channel channel = server.getTextChannel(channelId);

			if (channel == null) {
				LOGGER.warn("invalid log channel: {}", channelId);
			} else {
				logChannel = channel;
			}
		}
	}

	private void onGone(Server server) {
		logChannel = null;
	}

	public @Nullable Channel getLogChannel() {
		return logChannel;
	}
}
