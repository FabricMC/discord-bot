package net.fabricmc.discord.ioimpl.javacord;

import org.javacord.api.DiscordApi;

import net.fabricmc.discord.io.Discord;
import net.fabricmc.discord.io.GlobalEventHolder;

public class DiscordImpl implements Discord {
	private final DiscordApi wrapped;
	private final GlobalEventHolder globalEvents;
	private volatile UserImpl yourself;

	DiscordImpl(DiscordApi wrapped, GlobalEventHolder globalEvents) {
		this.wrapped = wrapped;
		this.globalEvents = globalEvents;
	}

	@Override
	public GlobalEventHolder getGlobalEvents() {
		return globalEvents;
	}

	@Override
	public ServerImpl getServer(long id) {
		return ServerImpl.wrap(wrapped.getServerById(id).orElse(null), this);
	}

	@Override
	public UserImpl getUser(long id, boolean fetch) {
		return UserImpl.wrap(fetch ? wrapped.getUserById(id).join() : wrapped.getCachedUserById(id).orElse(null), this);
	}

	@Override
	public UserImpl getYourself() {
		UserImpl ret = yourself;

		if (ret == null) {
			ret = UserImpl.wrap(wrapped.getYourself(), this);
			yourself = ret;
		}

		return ret;
	}

	@Override
	public void setActivity(String activity) {
		wrapped.updateActivity(activity);
	}

	DiscordApi unwrap() {
		return wrapped;
	}
}
