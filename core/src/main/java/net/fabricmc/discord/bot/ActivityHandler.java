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

package net.fabricmc.discord.bot;

import org.javacord.api.DiscordApi;
import org.javacord.api.entity.activity.ActivityType;
import org.javacord.api.entity.server.Server;

import net.fabricmc.discord.bot.config.ConfigKey;
import net.fabricmc.discord.bot.config.ValueSerializers;

public final class ActivityHandler {
	private static final ConfigKey<String> ACTIVITY = new ConfigKey<>("activity", ValueSerializers.STRING);

	private final DiscordBot bot;
	private volatile DiscordApi api;

	ActivityHandler(DiscordBot bot) {
		this.bot = bot;

		bot.registerConfigEntry(ACTIVITY, () -> "");

		bot.getActiveHandler().registerReadyHandler(this::onReady);
		bot.getActiveHandler().registerGoneHandler(this::onGone);
	}

	private void onReady(Server server, long prevActive) {
		String activity = bot.getConfigEntry(ACTIVITY);
		DiscordApi api = server.getApi();

		updateActivity(api, activity);

		this.api = api;
	}

	private void onGone(Server server) {
		api = null;
	}

	void onConfigValueChanged(ConfigKey<?> key, Object value) {
		if (key != ACTIVITY) return;

		DiscordApi api = this.api;
		if (api != null) updateActivity(api, (String) value);
	}

	private static void updateActivity(DiscordApi api, String activity) {
		String value = activity.isEmpty() ? null : activity;
		api.updateActivity(ActivityType.PLAYING, value); // CUSTOM isn't available to bots yet
	}
}
