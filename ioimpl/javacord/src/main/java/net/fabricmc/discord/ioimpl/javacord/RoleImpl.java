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

package net.fabricmc.discord.ioimpl.javacord;

import net.fabricmc.discord.io.Role;
import net.fabricmc.discord.io.Wrapper;

public class RoleImpl implements Role {
	private static final Wrapper<org.javacord.api.entity.permission.Role, RoleImpl> WRAPPER = new Wrapper<>();

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

		return WRAPPER.wrap(role, r -> new RoleImpl(r, server != null ? server : ServerImpl.wrap(r.getServer(), discord)));
	}

	org.javacord.api.entity.permission.Role unwrap() {
		return wrapped;
	}
}
