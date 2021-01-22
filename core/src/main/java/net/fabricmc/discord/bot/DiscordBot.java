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
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.javacord.api.DiscordApi;
import org.javacord.api.DiscordApiBuilder;

import net.fabricmc.discord.bot.command.CommandContext;
import net.fabricmc.discord.bot.config.ConfigKey;
import net.fabricmc.discord.bot.config.ValueSerializer;
import net.fabricmc.discord.bot.database.Database;
import net.fabricmc.discord.bot.database.query.ConfigQueries;

public final class DiscordBot {
	public static void start(String[] args) throws IOException {
		new DiscordBot(args);
	}

	private final Logger logger = LogManager.getLogger(DiscordBot.class);
	private final Map<ConfigKey<?>, Supplier<?>> configEntryRegistry = new ConcurrentHashMap<>();
	// COW for concurrent access
	private volatile Map<ConfigKey<?>, Object> configValues;
	private final BotConfig config;
	private final Database database;
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
		this.database = new Database(config.getDatabaseUrl());

		new DiscordApiBuilder()
		.setToken(this.config.getToken())
		.login()
		.thenAccept(api -> this.setup(api, dataDir))
		.exceptionally(exc -> {
			this.logger.error("Error occured while initializing bot", exc);
			return null;
		});
	}

	public Database getDatabase() {
		return database;
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

	public <V> void registerConfigEntry(ConfigKey<V> key, Supplier<V> defaultValue) {
		if (this.configEntryRegistry.putIfAbsent(key, defaultValue) != null) {
			throw new IllegalArgumentException("Already registered config value for key %s".formatted(key));
		}
	}

	public <V> V getConfigEntry(ConfigKey<V> key) {
		if (!this.configEntryRegistry.containsKey(key)) {
			throw new IllegalArgumentException("Tried to get the config value of an unregistered config key %s".formatted(key.name()));
		}

		// Thread Safety: the map is COW when a config value is changed
		// noinspection unchecked
		return (V) this.configValues.get(key);
	}

	public <V> boolean setConfigEntry(ConfigKey<V> key, V value) {
		if (!this.configEntryRegistry.containsKey(key)) {
			throw new IllegalArgumentException("Tried to set the config value of an unregistered config key %s".formatted(key.name()));
		}

		// Verify we can parse the value
		String serialized;

		try {
			serialized = key.valueSerializer().serialize(value);
		} catch (IllegalArgumentException e) {
			return false;
		}

		// COW
		final Map<ConfigKey<?>, Object> configValues = new HashMap<>(this.configValues);

		// Replace the value
		configValues.remove(key);
		configValues.put(key, value);
		// Set
		this.configValues = configValues;

		// Propagate value change to db
		try {
			ConfigQueries.set(this.database, key.name(), serialized);
		} catch (SQLException throwables) {
			throwables.printStackTrace();
		}

		// Notify modules of the change
		for (Module module : this.getModules()) {
			module.onConfigValueChanged(key, value);
		}

		return true;
	}

	public Set<ConfigKey<?>> getConfigEntries() {
		return this.configEntryRegistry.keySet();
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

		final ServiceLoader<Module> modules = ServiceLoader.load(Module.class);

		for (final Module module : modules) {
			// Take the config's word over the module setup
			if (this.config.getDisabledModules().contains(module.getName())) {
				this.logger.info("Not loading module due to config override {}", module.getName());
				continue;
			}

			if (module.shouldLoad()) {
				this.logger.info("Loading module {}", module.getName());
				this.modules.add(module);
				module.registerConfigEntries(this);
			}
		}

		this.loadRuntimeConfig();

		for (Module module : modules) {
			module.setup(this, api, LogManager.getLogger(module.getName()), dataDir);
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

	/**
	 * Loads all config values, setting any unset values to their default values.
	 */
	private void loadRuntimeConfig() {
		final Map<ConfigKey<?>, Object> configValues = new HashMap<>();

		// Verify all the config values exist - setting non-existent values to their default values
		for (Map.Entry<ConfigKey<?>, Supplier<?>> entry : this.configEntryRegistry.entrySet()) {
			final String name = entry.getKey().name();

			try {
				final String serializedValue = ConfigQueries.get(this.database, name);
				@SuppressWarnings("unchecked")
				final ValueSerializer<Object> valueSerializer = (ValueSerializer<Object>) entry.getKey().valueSerializer();

				Object value;

				if (serializedValue == null) {
					// Set the default value as the current value
					final Object defaultValue = entry.getValue().get();
					final String serialized = valueSerializer.serialize(defaultValue);

					ConfigQueries.set(this.database, name, serialized);
					value = defaultValue;
				} else {
					value = valueSerializer.deserialize(serializedValue);
				}

				configValues.put(entry.getKey(), value);
			} catch (SQLException throwables) {
				throwables.printStackTrace();
			}
		}

		// Set the values
		this.configValues = configValues;
	}

	void tryHandleCommand(CommandContext context) {
		// Don't dispatch commands if the bot is the sender
		if (context.author().isYourself()) {
			return;
		}

		// TODO:
	}
}
