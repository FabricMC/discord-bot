package net.fabricmc.discord.ioimpl.javacord;

import java.util.Collection;

import net.fabricmc.discord.io.Channel;
import net.fabricmc.discord.io.DiscordImplUtil;
import net.fabricmc.discord.io.Server;
import net.fabricmc.discord.io.User;

public class UserImpl implements User {
	private final org.javacord.api.entity.user.User wrapped;
	private final DiscordImpl discord;

	UserImpl(org.javacord.api.entity.user.User wrapped, DiscordImpl discord) {
		this.wrapped = wrapped;
		this.discord = discord;
	}

	@Override
	public DiscordImpl getDiscord() {
		return discord;
	}

	@Override
	public long getId() {
		return wrapped.getId();
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
		return null; // not supported by javacord
	}

	@Override
	public boolean isBot() {
		return wrapped.isBot();
	}

	@Override
	public boolean isYourself() {
		return wrapped.isYourself();
	}

	@Override
	public Collection<Server> getMutualServers() {
		return DiscordImplUtil.wrap(wrapped.getMutualServers(), s -> ServerImpl.wrap(s, discord));
	}

	@Override
	public Channel dm() {
		return ChannelImpl.wrap(wrapped.openPrivateChannel().join(), discord, this);
	}

	static UserImpl wrap(org.javacord.api.entity.user.User user, DiscordImpl discord) {
		if (user == null) return null;

		return new UserImpl(user, discord);
	}

	org.javacord.api.entity.user.User unwrap() {
		return wrapped;
	}
}
