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
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.ServiceLoader;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

import org.javacord.api.DiscordApi;
import org.javacord.api.DiscordApiBuilder;
import org.spongepowered.configurate.hocon.HoconConfigurationLoader;
import org.spongepowered.configurate.objectmapping.ObjectMapper;

import net.fabricmc.discord.bot.command.CommandContext;
import net.fabricmc.discord.bot.command.CommandResponder;
import net.fabricmc.discord.bot.config.BotConfig;
import net.fabricmc.discord.bot.serialization.BotTypeSerializers;

public final class DiscordBot {
	public static void main(String[] args) throws IOException {
		new DiscordBot(args);
	}

	private final BotConfig config;
	/**
	 * A list of all enabled modules.
	 */
	private final List<Module> modules = new ArrayList<>();
	private final ExecutorService serialExecutor = Executors.newSingleThreadExecutor(r -> {
		Thread ret = new Thread(r, "serial execution thread");
		ret.setDaemon(true);

		return ret;
	});

	private DiscordBot(String[] args) throws IOException {
		final Path configDir = Paths.get("").toAbsolutePath().resolve("config");
		final Path dataDir = Paths.get("").toAbsolutePath().resolve("data");

		this.config = this.loadConfig(configDir);

		new DiscordApiBuilder()
		.setToken(this.config.secrets().token())
		.login()
		.thenAccept(api -> this.setup(api, configDir, dataDir))
		.exceptionally(exc -> {
			exc.printStackTrace();
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
		return this.config.guild().commandPrefix();
	}

	public void registerCommand() {
	}

	private BotConfig loadConfig(Path configDir) throws IOException {
		System.out.printf("Loading config in %s%n", configDir);

		// Setup a default config if the config is not present
		if (Files.notExists(configDir.resolve("core.conf"))) {
			Files.createDirectories(configDir);
			Files.createFile(configDir.resolve("core.conf"));

			try (InputStream input = this.getClass().getClassLoader().getResourceAsStream("core.conf")) {
				if (input == null) {
					throw new RuntimeException(); // TODO: Msg
				}

				try (OutputStream output = Files.newOutputStream(configDir.resolve("core.conf"))) {
					output.write(input.readAllBytes());
				}
			}
		}

		final HoconConfigurationLoader configLoader = HoconConfigurationLoader.builder()
				.defaultOptions(options -> options.serializers(BotTypeSerializers.SERIALIZERS))
				.path(configDir.resolve("core.conf"))
				.build();

		return ObjectMapper.factory()
				.get(BotConfig.class)
				.load(configLoader.load());
	}

	private void setup(DiscordApi api, Path configDir, Path dataDir) {
		final BuiltinModule builtin = new BuiltinModule();
		this.modules.add(builtin);

		builtin.setup(this, api, configDir, dataDir);

		final ServiceLoader<Module> modules = ServiceLoader.load(Module.class);

		for (final Module module : modules) {
			// Take the config's word over the module setup
			if (this.config.modules().disabled().contains(module.getName())) {
				continue;
			}

			if (module.setup(this, api, configDir, dataDir)) {
				this.modules.add(module);
			}
		}
	}

	void tryHandleCommand(CommandContext context, CommandResponder responder) {
		// Don't dispatch commands if the bot is the sender
		if (context.author().isYourself()) {
			return;
		}

		// TODO:
	}
}
