package net.fabricmc.discord.bot;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;

import org.javacord.api.entity.server.Server;
import org.javacord.api.listener.ChainableGloballyAttachableListenerManager;

import net.fabricmc.discord.bot.config.ConfigKey;
import net.fabricmc.discord.bot.config.ValueSerializers;

/**
 * Bot presence state handling
 */
public final class ActiveHandler {
	private static final int activeRefreshDelay = 10; // in s
	private static final ConfigKey<Long> LAST_ACTIVE = new ConfigKey<>("lastActive", ValueSerializers.LONG);

	private final DiscordBot bot;
	private final List<ReadyHandler> readyHandlers = new CopyOnWriteArrayList<>();
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

	public void register(ReadyHandler handler) {
		readyHandlers.add(handler);
	}

	synchronized void onServerReady(Server server) {
		assert server.getId() == bot.getServerId();

		if (!activeRef.compareAndSet(false, true)) return;

		long lastActive = lastActiveTime;
		readyHandlers.forEach(h -> h.onReady(server, lastActive));

		updateLastActive();
		scheduledTask = bot.getScheduledExecutor().scheduleWithFixedDelay(() -> updateLastActive(), activeRefreshDelay, activeRefreshDelay, TimeUnit.SECONDS);
	}

	synchronized void onServerGone(Server server) {
		assert server.getId() == bot.getServerId();

		if (!activeRef.compareAndSet(true, false)) return;

		while (!scheduledTask.cancel(false)) {
			LockSupport.parkNanos(10_000);
		}

		scheduledTask = null;

		updateLastActive();
	}

	void registerEarlyHandlers(ChainableGloballyAttachableListenerManager src) {
		src.addServerBecomesAvailableListener(api -> {
			Server server = api.getServer();
			if (server.getId() == bot.getServerId()) onServerReady(server);
		});
		src.addServerBecomesUnavailableListener(api -> {
			Server server = api.getServer();
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
}