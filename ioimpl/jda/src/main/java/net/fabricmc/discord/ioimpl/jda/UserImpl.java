package net.fabricmc.discord.ioimpl.jda;

import java.util.Collection;

import net.fabricmc.discord.io.Channel;
import net.fabricmc.discord.io.Discord;
import net.fabricmc.discord.io.DiscordImplUtil;
import net.fabricmc.discord.io.Server;
import net.fabricmc.discord.io.User;

public class UserImpl implements User {
	private final net.dv8tion.jda.api.entities.User wrapped;
	private final DiscordImpl discord;

	UserImpl(net.dv8tion.jda.api.entities.User wrapped, DiscordImpl discord) {
		this.wrapped = wrapped;
		this.discord = discord;
	}

	@Override
	public Discord getDiscord() {
		return discord;
	}

	@Override
	public long getId() {
		return wrapped.getIdLong();
	}

	@Override
	public String getName() {
		return wrapped.getName();
	}

	@Override
	public String getDiscriminator() {
		return wrapped.getDiscriminator();
	}

	@Override
	public String getGlobalNickname() {
		return wrapped.getGlobalName();
	}

	@Override
	public boolean isBot() {
		return wrapped.isBot();
	}

	@Override
	public boolean isYourself() {
		return wrapped.getId() == wrapped.getJDA().getSelfUser().getId();
	}

	@Override
	public Collection<Server> getMutualServers() {
		return DiscordImplUtil.wrap(wrapped.getMutualGuilds(), s -> ServerImpl.wrap(s, discord));
	}

	@Override
	public Channel dm() {
		return ChannelImpl.wrap(wrapped.openPrivateChannel().complete(), discord, this);
	}

	static UserImpl wrap(net.dv8tion.jda.api.entities.User user, DiscordImpl discord) {
		if (user == null) return null;

		return new UserImpl(user, discord);
	}

	net.dv8tion.jda.api.entities.User unwrap() {
		return wrapped;
	}
}
