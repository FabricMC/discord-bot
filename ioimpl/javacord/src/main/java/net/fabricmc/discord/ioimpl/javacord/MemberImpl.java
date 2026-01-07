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

import static net.fabricmc.discord.ioimpl.javacord.DiscordProviderImpl.urlToString;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import org.javacord.api.entity.permission.PermissionType;

import net.fabricmc.discord.io.Channel;
import net.fabricmc.discord.io.DiscordImplUtil;
import net.fabricmc.discord.io.Member;
import net.fabricmc.discord.io.Permission;
import net.fabricmc.discord.io.Role;

public class MemberImpl implements Member {
	private final UserImpl user;
	private final ServerImpl server;

	MemberImpl(UserImpl user, ServerImpl server) {
		this.user = user;
		this.server = server;
	}

	@Override
	public ServerImpl getServer() {
		return server;
	}

	@Override
	public UserImpl getUser() {
		return user;
	}

	@Override
	public String getDisplayName() {
		return user.unwrap().getDisplayName(server.unwrap());
	}

	@Override
	public String getNickname() {
		return user.unwrap().getNickname(server.unwrap()).orElse(null);
	}

	@Override
	public void setNickName(String newNick, String reason) {
		server.unwrap().updateNickname(user.unwrap(), newNick, reason);
	}

	@Override
	public Instant getJoinTime() {
		return user.unwrap().getJoinedAtTimestamp(server.unwrap()).orElse(null);
	}

	@Override
	public Status getStatus() {
		return Status.fromName(user.unwrap().getStatus().getStatusString());
	}

	@Override
	public String getAvatarUrl() {
		return urlToString(user.unwrap().getEffectiveAvatar(server.unwrap()).getUrl());
	}

	@Override
	public List<RoleImpl> getRoles() {
		return DiscordImplUtil.wrap(user.unwrap().getRoles(server.unwrap()), r -> RoleImpl.wrap(r, server.getDiscord(), server));
	}

	@Override
	public void addRole(Role role, String reason) {
		server.unwrap().addRoleToUser(user.unwrap(), ((RoleImpl) role).unwrap(), reason);
	}

	@Override
	public void removeRole(Role role, String reason) {
		server.unwrap().removeRoleFromUser(user.unwrap(), ((RoleImpl) role).unwrap(), reason);
	}

	@Override
	public Set<Permission> getPermissions() {
		return Permission.fromMask(server.unwrap().getPermissions(user.unwrap()).getAllowedBitmask());
	}

	@Override
	public Set<Permission> getPermissions(Channel channel) {
		return channel.getPermissions(user);
	}

	static PermissionType translatePermission(Permission perm) {
		long req = perm.mask();

		for (PermissionType p : PermissionType.values()) {
			if (p.getValue() == req) return p;
		}

		return null;
	}

	@Override
	public void kick(String reason) {
		server.unwrap().kickUser(user.unwrap(), reason).join();
	}

	@Override
	public void ban(Duration messageDeleteionTimeframe, String reason) {
		server.unwrap().banUser(user.unwrap(), messageDeleteionTimeframe, reason).join();
	}

	static MemberImpl wrap(org.javacord.api.entity.user.User user, ServerImpl server) {
		if (user == null) return null;

		return new MemberImpl(UserImpl.wrap(user, server.getDiscord()), server);
	}
}
