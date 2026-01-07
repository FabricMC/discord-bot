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

package net.fabricmc.discord.bot.module.joinlog;

import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import net.fabricmc.discord.bot.DiscordBot;
import net.fabricmc.discord.bot.Module;
import net.fabricmc.discord.bot.UserHandler;
import net.fabricmc.discord.bot.config.ConfigKey;
import net.fabricmc.discord.bot.config.ValueSerializers;
import net.fabricmc.discord.bot.util.DiscordUtil;
import net.fabricmc.discord.bot.util.FormatUtil;
import net.fabricmc.discord.io.Channel;
import net.fabricmc.discord.io.Discord;
import net.fabricmc.discord.io.GlobalEventHolder.MemberJoinHandler;
import net.fabricmc.discord.io.Member;
import net.fabricmc.discord.io.Server;
import net.fabricmc.discord.io.User;

public final class JoinLogModule implements Module, MemberJoinHandler {
	private static final Logger LOGGER = LogManager.getLogger(JoinLogModule.class);

	private static final ConfigKey<Long> JOINLOG_CHANNEL = new ConfigKey<>("joinlog.channel", ValueSerializers.LONG);

	private DiscordBot bot;
	private volatile Server server;
	private volatile Channel channel;

	@Override
	public String getName() {
		return "joinlog";
	}

	@Override
	public void registerConfigEntries(DiscordBot bot) {
		bot.registerConfigEntry(JOINLOG_CHANNEL, -1L);
	}

	@Override
	public void onConfigValueChanged(ConfigKey<?> key, Object value) {
		if (key == JOINLOG_CHANNEL) {
			channel = getChannel(server, "joinlog", (Long) value);
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

		bot.getActiveHandler().registerReadyHandler(this::onReady);
		bot.getActiveHandler().registerGoneHandler(this::onGone);
		discord.getGlobalEvents().registerMemberJoin(this);
	}

	private void onReady(Server server, long prevActive) {
		this.server = server;
		this.channel = getChannel(server, "joinlog", bot.getConfigEntry(JOINLOG_CHANNEL));
	}

	private void onGone(Server server) {
		this.server = null;
		this.channel = null;
	}

	@Override
	public void onMemberJoin(Member member) {
		User user = member.getUser();
		if (user.isBot()) return;

		Channel channel = this.channel;
		if (channel == null) return;

		DiscordUtil.sendMentionlessMessage(channel, String.format("%s %s",
				UserHandler.formatDiscordUser(user),
				FormatUtil.formatDuration(DiscordUtil.getCreationTime(user.getId()).until(Instant.now(), ChronoUnit.MILLIS), 2)));
	}
}
