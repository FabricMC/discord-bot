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

package net.fabricmc.discord.bot;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Properties;
import java.util.ServiceLoader;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.javacord.api.DiscordApi;
import org.javacord.api.DiscordApiBuilder;

import net.fabricmc.discord.bot.command.CommandContext;
import net.fabricmc.discord.bot.command.CommandResponder;

public final class DiscordBot {
	public static void start(String[] args) throws IOException {
		new DiscordBot(args);
	}

	private final Logger logger = LogManager.getLogger(DiscordBot.class);
	private final BotConfig config;
	/**
	 * A list of all enabled modules.
	 */
	private final List<Module> modules = new ArrayList<>();
	private final ExecutorService serialExecutor = Executors.newSingleThreadExecutor(r -> {
		Thread ret = new Thread(r, "Serial execution thread");
		ret.setDaemon(true);

		return ret;
	});

	private DiscordBot(String[] args) throws IOException {
		final Path configDir = Paths.get("").toAbsolutePath().resolve("bot.properties");
		final Path dataDir = Paths.get("").toAbsolutePath().resolve("data");

		this.config = this.loadConfig(configDir);

		new DiscordApiBuilder()
		.setToken(this.config.getToken())
		.login()
		.thenAccept(api -> this.setup(api, dataDir))
		.exceptionally(exc -> {
			this.logger.error("Error occured while initializing bot", exc);
			return null;
		});
	}

	public Collection<Module> getModules() {
		return Collections.unmodifiableCollection(this.modules);
	}

	/**
	 * Gets the bot's serial executor.
	 *
	 * <p>The serial executor should be used for accessing or doing operations on objects which are not thread safe.
	 *
	 * @return the serial executor
	 */
	public Executor getSerialExecutor() {
		return this.serialExecutor;
	}

	public String getCommandPrefix() {
		return this.config.getCommandPrefix();
	}

	public void registerCommand() {
	}

	private BotConfig loadConfig(Path configPath) throws IOException {
		if (Files.notExists(configPath)) {
			this.logger.info("Creating bot config");

			try (final OutputStream output = Files.newOutputStream(configPath, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
				try (final InputStream input = BotConfig.class.getClassLoader().getResourceAsStream("bot.properties")) {
					if (input == null) {
						throw new RuntimeException("Failed to find config in bot's jar!");
					}

					output.write(input.readAllBytes());
				}
			}
		}

		final Properties properties = new Properties();

		try (InputStream input = Files.newInputStream(configPath)) {
			properties.load(input);
		}

		return BotConfig.load(properties);
	}

	private void setup(DiscordApi api, Path dataDir) {
		final BuiltinModule builtin = new BuiltinModule();
		this.modules.add(builtin);

		builtin.setup(this, api, this.logger, dataDir);

		final ServiceLoader<Module> modules = ServiceLoader.load(Module.class);

		for (final Module module : modules) {
			// Take the config's word over the module setup
			if (this.config.getDisabledModules().contains(module.getName())) {
				this.logger.info("Not loading module due to config override {}", module.getName());
				continue;
			}

			if (module.setup(this, api, LogManager.getLogger(module.getName()), dataDir)) {
				this.logger.info("Loaded module {}", module.getName());
				this.modules.add(module);
			}
		}

		final StringBuilder moduleList = new StringBuilder();

		final Iterator<Module> iterator = this.modules.iterator();

		while (iterator.hasNext()) {
			final Module module = iterator.next();
			moduleList.append(" - ").append(module.getName());

			if (iterator.hasNext()) {
				moduleList.append(",\n");
			}
		}

		this.logger.info("Loaded {} modules:\n{}", this.modules.size(), moduleList.toString());
	}

	void tryHandleCommand(CommandContext context, CommandResponder responder) {
		// Don't dispatch commands if the bot is the sender
		if (context.author().isYourself()) {
			return;
		}

		// TODO:
	}
}
