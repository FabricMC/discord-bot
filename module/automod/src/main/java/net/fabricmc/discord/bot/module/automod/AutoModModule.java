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

package net.fabricmc.discord.bot.module.automod;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.javacord.api.DiscordApi;
import org.javacord.api.entity.channel.TextChannel;
import org.javacord.api.entity.message.Message;
import org.javacord.api.entity.message.MessageType;
import org.javacord.api.entity.server.Server;

import net.fabricmc.discord.bot.CachedMessage;
import net.fabricmc.discord.bot.DiscordBot;
import net.fabricmc.discord.bot.MessageIndex.MessageCreateHandler;
import net.fabricmc.discord.bot.Module;
import net.fabricmc.discord.bot.command.mod.ActionUtil;
import net.fabricmc.discord.bot.command.mod.ActionUtil.UserMessageAction;
import net.fabricmc.discord.bot.command.mod.UserActionType;
import net.fabricmc.discord.bot.config.ConfigKey;
import net.fabricmc.discord.bot.config.ValueSerializers;
import net.fabricmc.discord.bot.util.DiscordUtil;

public final class AutoModModule implements Module, MessageCreateHandler {
	private static final Logger LOGGER = LogManager.getLogger(AutoModModule.class);

	private static final ConfigKey<List<Long>> REQUESTS_CHANNELS = new ConfigKey<>("automod.requestsChannels", ValueSerializers.LONG_LIST);
	private static final ConfigKey<String> REQUESTS_ACTION_REASON = new ConfigKey<>("automod.requestsActionReason", ValueSerializers.STRING);
	private static final ConfigKey<List<Long>> SHOWCASE_CHANNELS = new ConfigKey<>("automod.showcaseChannels", ValueSerializers.LONG_LIST);
	private static final ConfigKey<String> SHOWCASE_ACTION_REASON = new ConfigKey<>("automod.showcaseActionReason", ValueSerializers.STRING);

	private DiscordBot bot;
	private volatile Server server;
	private volatile List<TextChannel> requestsChannels;
	private volatile List<TextChannel> showcaseChannels;

	@Override
	public String getName() {
		return "automod";
	}

	@Override
	public void registerConfigEntries(DiscordBot bot) {
		bot.registerConfigEntry(REQUESTS_CHANNELS, () -> Collections.emptyList());
		bot.registerConfigEntry(REQUESTS_ACTION_REASON, () -> "please use the appropriate channel");
		bot.registerConfigEntry(SHOWCASE_CHANNELS, () -> Collections.emptyList());
		bot.registerConfigEntry(SHOWCASE_ACTION_REASON, () -> "please use the appropriate channel");
	}

	@SuppressWarnings("unchecked")
	@Override
	public void onConfigValueChanged(ConfigKey<?> key, Object value) {
		if (key == REQUESTS_CHANNELS) {
			requestsChannels = getChannels(server, "requests", (List<Long>) value);
		} else if (key == SHOWCASE_CHANNELS) {
			showcaseChannels = getChannels(server, "showcase", (List<Long>) value);
		}
	}

	private static List<TextChannel> getChannels(Server server, String type, List<Long> ids) {
		if (server == null || ids.isEmpty()) return null;

		List<TextChannel> ret = new ArrayList<>();

		for (long id : ids) {
			TextChannel channel = DiscordUtil.getTextChannel(server, id);

			if (channel == null) {
				LOGGER.warn("invalid {} channel: {}", type, id);
			} else {
				ret.add(channel);
			}
		}

		return !ret.isEmpty() ? ret : null;
	}

	@Override
	public void setup(DiscordBot bot, DiscordApi api, Logger logger, Path dataDir) {
		this.bot = bot;

		bot.getActiveHandler().registerReadyHandler(this::onReady);
		bot.getActiveHandler().registerGoneHandler(this::onGone);
		bot.getMessageIndex().registerCreateHandler(this);
	}

	private void onReady(Server server, long prevActive) {
		this.server = server;
		this.requestsChannels = getChannels(server, "requests", bot.getConfigEntry(REQUESTS_CHANNELS));
		this.showcaseChannels = getChannels(server, "showcase", bot.getConfigEntry(SHOWCASE_CHANNELS));
	}

	private void onGone(Server server) {
		this.server = null;
		this.requestsChannels = null;
		this.showcaseChannels = null;
	}

	@Override
	public void onMessageCreated(CachedMessage message, Server server) {
		if (message.getType() != MessageType.REPLY) return;

		if (bot.getUserHandler().hasImmunity(message.getAuthorDiscordId(), bot.getUserHandler().getBotUserId(), false)) {
			return;
		}

		if (!checkReply(message, server, requestsChannels, REQUESTS_ACTION_REASON)) {
			checkReply(message, server, showcaseChannels, SHOWCASE_ACTION_REASON);
		}
	}

	private boolean checkReply(CachedMessage message, Server server, List<TextChannel> channels, ConfigKey<String> reasonKey) {
		if (channels == null) return false;

		TextChannel channel = null;

		for (TextChannel c : channels) {
			if (message.getChannelId() == c.getId()) {
				channel = c;
				break;
			}
		}

		if (channel == null) return false;

		try {
			Message refMsg = message.toMessage(server).getReferencedMessage().orElse(null);

			if (refMsg == null
					|| refMsg.getAuthor().getId() == message.getAuthorDiscordId() // self-reply
					|| refMsg.getChannel() != channel) { // different channel
				return true; // already matched channel
			}

			int userId = bot.getUserHandler().getUserId(message.getAuthorDiscordId());

			ActionUtil.applyUserAction(UserActionType.DELETE_MESSAGE, 0, userId, null,
					"off-topic message in <#%d> detected, %s".formatted(channel.getId(), bot.getConfigEntry(reasonKey)),
					message, UserMessageAction.DELETE,
					true, "(reply to %d)".formatted(refMsg.getId()),
					bot, server, null, bot.getUserHandler().getBotDiscordUser(server), bot.getUserHandler().getBotUserId());
		} catch (Exception e) {
			LOGGER.warn("Automod reply handling failed ", e);
		}

		return true;
	}
}
