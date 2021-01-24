package net.fabricmc.discord.bot.util;

import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

public final class DaemonThreadFactory implements ThreadFactory {
	public DaemonThreadFactory(String name) {
		this.name = name;
	}

	@Override
	public Thread newThread(Runnable r) {
		Thread ret = new Thread(r, name+" #"+counter.incrementAndGet());
		ret.setDaemon(true);
		return ret;
	}

	private final String name;
	private final AtomicInteger counter = new AtomicInteger();
}
