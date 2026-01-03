/*
 * Copyright (c) 2026 FabricMC
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
