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

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import net.fabricmc.discord.bot.config.ConfigKey;
import net.fabricmc.discord.bot.config.ValueSerializers;
import net.fabricmc.discord.io.GlobalEventHolder;
import net.fabricmc.discord.io.Server;

/**
 * Bot presence state handling
 */
public final class ActiveHandler {
	private static final int activeRefreshDelay = 10; // in s
	private static final ConfigKey<Long> LAST_ACTIVE = new ConfigKey<>("lastActive", ValueSerializers.LONG);

	private static final Logger LOGGER = LogManager.getLogger("ActiveHandler");

	private final DiscordBot bot;
	private final List<ReadyHandler> readyHandlers = new CopyOnWriteArrayList<>();
	private final List<GoneHandler> goneHandlers = new CopyOnWriteArrayList<>();
	private final AtomicBoolean activeRef = new AtomicBoolean();
	private Future<?> scheduledTask;
	private volatile long lastActiveTime;

	public ActiveHandler(DiscordBot bot) {
		this.bot = bot;
		bot.registerConfigEntry(LAST_ACTIVE, System::currentTimeMillis);
	}

	void init() {
		lastActiveTime = bot.getConfigEntry(LAST_ACTIVE);
	}

	public void registerReadyHandler(ReadyHandler handler) {
		readyHandlers.add(handler);
	}

	public void registerGoneHandler(GoneHandler handler) {
		goneHandlers.add(handler);
	}

	synchronized void onServerReady(Server server) {
		assert server.getId() == bot.getServerId();

		if (!activeRef.compareAndSet(false, true)) return;

		LOGGER.info("Server ready");

		long lastActive = lastActiveTime;
		readyHandlers.forEach(h -> h.onReady(server, lastActive));

		updateLastActive();
		scheduledTask = bot.getScheduledExecutor().scheduleWithFixedDelay(() -> updateLastActive(), activeRefreshDelay, activeRefreshDelay, TimeUnit.SECONDS);
	}

	synchronized void onServerGone(Server server) {
		assert server.getId() == bot.getServerId();

		if (!activeRef.compareAndSet(true, false)) return;

		LOGGER.info("Server gone");

		scheduledTask.cancel(false);
		scheduledTask = null;

		updateLastActive();

		goneHandlers.forEach(h -> h.onGone(server));
	}

	void registerEarlyHandlers(GlobalEventHolder holder) {
		holder.registerServerReady(server -> {
			if (server.getId() == bot.getServerId()) onServerReady(server);
		});
		holder.registerServerGone(server -> {
			if (server.getId() == bot.getServerId()) onServerGone(server);
		});
	}

	private void updateLastActive() {
		long time = System.currentTimeMillis();
		lastActiveTime = time;
		bot.setConfigEntry(LAST_ACTIVE, time);
	}

	public interface ReadyHandler {
		void onReady(Server server, long prevActive);
	}

	public interface GoneHandler {
		void onGone(Server server);
	}
}