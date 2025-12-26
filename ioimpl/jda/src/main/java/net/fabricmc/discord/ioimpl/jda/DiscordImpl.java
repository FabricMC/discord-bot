package net.fabricmc.discord.ioimpl.jda;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Activity;

import net.fabricmc.discord.io.Discord;
import net.fabricmc.discord.io.GlobalEventHolder;

public class DiscordImpl implements Discord {
	private final JDA wrapped;
	private final GlobalEventHolder globalEvents;
	private volatile UserImpl yourself;

	DiscordImpl(JDA wrapped, GlobalEventHolder globalEvents) {
		this.wrapped = wrapped;
		this.globalEvents = globalEvents;
	}

	@Override
	public GlobalEventHolder getGlobalEvents() {
		return globalEvents;
	}

	@Override
	public ServerImpl getServer(long id) {
		return ServerImpl.wrap(wrapped.getGuildById(id), this);
	}

	@Override
	public UserImpl getUser(long id, boolean fetch) {
		return UserImpl.wrap(fetch ? wrapped.retrieveUserById(id).complete() : wrapped.getUserById(id), this);
	}

	@Override
	public UserImpl getYourself() {
		UserImpl ret = yourself;

		if (ret == null) {
			ret = UserImpl.wrap(wrapped.getSelfUser(), this);
			yourself = ret;
		}

		return ret;
	}

	@Override
	public void setActivity(String activity) {
		wrapped.getPresence().setActivity(Activity.customStatus(activity));
	}

	JDA unwrap() {
		return wrapped;
	}
}
