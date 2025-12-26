package net.fabricmc.discord.ioimpl.javacord;

import net.fabricmc.discord.io.Role;

public class RoleImpl implements Role {
	private final ServerImpl server;
	private final org.javacord.api.entity.permission.Role wrapped;

	RoleImpl(org.javacord.api.entity.permission.Role wrapped, ServerImpl server) {
		this.wrapped = wrapped;
		this.server = server;
	}

	@Override
	public ServerImpl getServer() {
		return server;
	}

	@Override
	public long getId() {
		return wrapped.getId();
	}

	@Override
	public boolean isEveryone() {
		return wrapped.isEveryoneRole();
	}

	static RoleImpl wrap(org.javacord.api.entity.permission.Role role, DiscordImpl discord, ServerImpl server) {
		if (role == null) return null;

		if (server == null) server = ServerImpl.wrap(role.getServer(), discord);

		return new RoleImpl(role, server);
	}

	org.javacord.api.entity.permission.Role unwrap() {
		return wrapped;
	}
}
