package net.fabricmc.discord.ioimpl.jda;

import net.fabricmc.discord.io.Role;

public class RoleImpl implements Role {
	private final net.dv8tion.jda.api.entities.Role wrapped;
	private final ServerImpl server;

	RoleImpl(net.dv8tion.jda.api.entities.Role wrapped, ServerImpl server) {
		this.wrapped = wrapped;
		this.server = server;
	}

	@Override
	public ServerImpl getServer() {
		return server;
	}

	@Override
	public long getId() {
		return wrapped.getIdLong();
	}

	@Override
	public boolean isEveryone() {
		return wrapped.isPublicRole();
	}

	static RoleImpl wrap(net.dv8tion.jda.api.entities.Role role, DiscordImpl discord, ServerImpl server) {
		if (role == null) return null;

		if (server == null) server = ServerImpl.wrap(role.getGuild(), discord);

		return new RoleImpl(role, server);
	}

	net.dv8tion.jda.api.entities.Role unwrap() {
		return wrapped;
	}
}
