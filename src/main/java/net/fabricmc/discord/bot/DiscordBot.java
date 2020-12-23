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
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Supplier;

import org.javacord.api.DiscordApi;
import org.javacord.api.DiscordApiBuilder;
import org.spongepowered.configurate.hocon.HoconConfigurationLoader;
import org.spongepowered.configurate.objectmapping.ObjectMapper;

import net.fabricmc.discord.bot.command.CommandContext;
import net.fabricmc.discord.bot.command.CommandResponder;
import net.fabricmc.discord.bot.config.BotConfig;

public final class DiscordBot {
	public static void main(String[] args) throws IOException {
		new DiscordBot(args);
	}

	private final BotConfig config;
	/**
	 * A list of all enabled modules.
	 */
	private final List<Module> modules = new ArrayList<>();
	private final BotThread thread = new BotThread();

	private DiscordBot(String[] args) throws IOException {
		final Path configDir = Paths.get("").toAbsolutePath().resolve("config");

		this.config = this.loadConfig(configDir);
		this.thread.start();

		new DiscordApiBuilder().setToken(this.config.secrets().token())
				.login()
				.thenAccept(api -> this.setup(api, configDir));
	}

	public Collection<Module> getModules() {
		return Collections.unmodifiableCollection(this.modules);
	}

	/**
	 * Submits a task to be run on the bot's executor thread.
	 *
	 * Per discussion on Javacord discord it should be noted that all events are called on their own thread and any amount of threads.
	 * Nor are events all guaranteed to fire on the same thread, the only guarantee provided by Javacord is that all events are fired in the order in which they occur.
	 * Therefore if the bot requires any state to track things such as paginators or lookup mappings it should be done on this thread.
	 *
	 * @param task the task to run on the bot's executor thread
	 * @param <T> the type of value returned by the task
	 * @return a future which is completed when the task has returned a value.
	 */
	public <T> CompletableFuture<T> submit(Supplier<T> task) {
		// If we are already on bot thread then execute immediately
		if (Thread.currentThread() == this.thread) {
			return CompletableFuture.completedFuture(task.get());
		}

		final CompletableFuture<T> future = new CompletableFuture<>();

		// Add a task to the queue.
		this.thread.tasks.add(() -> {
			future.complete(task.get());
		});

		// Notify the thread that there are tasks ready to execute
		this.thread.notify();

		return future;
	}

	private BotConfig loadConfig(Path configDir) throws IOException {
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
				.path(configDir.resolve("core.conf"))
				.build();

		return ObjectMapper.factory()
				.get(BotConfig.class)
				.load(configLoader.load());
	}

	private void setup(DiscordApi api, Path configDir) {
		final BuiltinModule builtin = new BuiltinModule();
		this.modules.add(builtin);

		builtin.setup(this, api, configDir);

		final ServiceLoader<Module> modules = ServiceLoader.load(Module.class);

		for (final Module module : modules) {
			// Take the config's word over the module setup
			if (this.config.modules().disabled().contains(module.getName())) {
				continue;
			}

			if (module.setup(this, api, configDir)) {
				this.modules.add(module);
			}
		}
	}

	void tryHandleCommand(CommandContext context, CommandResponder responder) {
		// Don't dispatch commands if the bot is the sender
		if (context.author().isYourself()) {
			return;
		}

		if (context.content().startsWith(this.config.commandPrefix())) {

		}

		// TODO:
	}

	// For Player: This is very questionable - please make a proper fix
	private static final class BotThread extends Thread {
		private volatile boolean running;
		private final ConcurrentLinkedQueue<Runnable> tasks = new ConcurrentLinkedQueue<>();

		BotThread() {
			this.setName("Bot Executor Thread");
		}

		@Override
		public void run() {
			while (true) {
				synchronized (this) {
					if (!this.running) {
						break;
					}

					this.tasks.removeIf(task -> {
						task.run();
						return true;
					});

					// Wait for tasks if there are no more tasks to execute.
					// The thread will be woken up when new tasks are added.
					if (this.tasks.isEmpty()) {
						try {
							this.wait();
						} catch (InterruptedException e) {
							e.printStackTrace(); // TODO: Better spot?
							break;
						}
					}
				}
			}
		}

		void stopRunning() {
			synchronized (this) {
				this.running = false;
			}

			// Notify the thread to break out of the infinite loop
			this.notify();
		}
	}
}
