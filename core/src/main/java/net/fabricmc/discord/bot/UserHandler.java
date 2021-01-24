/*
 * Copyright (c) 2021 FabricMC
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

package net.fabricmc.discord.bot;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

import org.javacord.api.entity.server.Server;
import org.javacord.api.entity.user.User;
import org.javacord.api.event.server.ServerBecomesAvailableEvent;
import org.javacord.api.event.server.member.ServerMemberJoinEvent;
import org.javacord.api.event.server.member.ServerMemberLeaveEvent;
import org.javacord.api.event.user.UserChangeNameEvent;
import org.javacord.api.event.user.UserChangeNicknameEvent;
import org.javacord.api.listener.ChainableGloballyAttachableListenerManager;
import org.javacord.api.listener.server.ServerBecomesAvailableListener;
import org.javacord.api.listener.server.member.ServerMemberJoinListener;
import org.javacord.api.listener.server.member.ServerMemberLeaveListener;
import org.javacord.api.listener.user.UserChangeNameListener;
import org.javacord.api.listener.user.UserChangeNicknameListener;

import net.fabricmc.discord.bot.database.query.UserQueries;
import net.fabricmc.discord.bot.database.query.UserQueries.SessionDiscordUserData;

public final class UserHandler implements ServerBecomesAvailableListener, ServerMemberJoinListener, ServerMemberLeaveListener, UserChangeNameListener, UserChangeNicknameListener {
	public static final String ADMIN_PERMISSION = "admin";

	private final DiscordBot bot;
	private final long serverId;

	public UserHandler(DiscordBot bot, long serverId) {
		this.bot = bot;
		this.serverId = serverId;

		try {
			if (!UserQueries.hasAnyPermittedUser(bot.getDatabase(), ADMIN_PERMISSION)) {
				System.err.println("no admin account configured!"); // TODO: show more visibly
			}
		} catch (SQLException e) {
			throw new RuntimeException(e);
		}
	}

	public boolean hasPermission(User user, Server server, String permission) {
		try {
			return UserQueries.discordUserHasPermission(bot.getDatabase(), user.getId(), permission, ADMIN_PERMISSION)
					|| permission.equals(ADMIN_PERMISSION) && server.isAdmin(user) && !UserQueries.hasAnyPermittedUser(bot.getDatabase(), permission);
		} catch (SQLException e) {
			throw new RuntimeException(e);
		}
	}

	public int getUserId(String user, Server server) {
		long ret = parseUserId(user, server);

		if (ret == -1 || !isDiscordUserId(ret)) {
			return (int) ret;
		} else {
			try {
				return UserQueries.getUserId(bot.getDatabase(), ret);
			} catch (SQLException e) {
				throw new RuntimeException(e);
			}
		}
	}

	public long getDiscordUserId(String user, Server server) {
		long ret = parseUserId(user, server);

		if (ret == -1 || isDiscordUserId(ret)) {
			return ret;
		} else {
			try {
				List<Long> matches = UserQueries.getDiscordUserIds(bot.getDatabase(), (int) ret);
				return matches.isEmpty() ? -1 : matches.get(matches.size() - 1);
			} catch (SQLException e) {
				throw new RuntimeException(e);
			}
		}
	}

	private long parseUserId(String user, Server server) {
		try {
			if (user.startsWith("<@") && user.endsWith(">")) {
				char next = user.charAt(2);
				int start = next >= '0' && next <= '9' ? 2 : 3;

				return Long.parseLong(user.substring(start, user.length() - 1));
			}

			return Long.parseLong(user);
		} catch (NumberFormatException e) { }

		int pos = user.indexOf('#');

		if (pos >= 0) {
			String username = user.substring(0, pos);
			String discriminator = user.substring(pos);
			User res = server.getMemberByNameAndDiscriminator(username, discriminator).orElse(null);
			if (res != null) return res.getId();

			try {
				List<Integer> matches = UserQueries.getUserIds(bot.getDatabase(), username, discriminator);
				return matches.isEmpty() ? -1 : matches.get(matches.size() - 1);
			} catch (SQLException e) {
				throw new RuntimeException(e);
			}
		}

		Collection<User> users = server.getMembersByNickname(user);
		if (users.isEmpty()) users = server.getMembersByName(user);

		if (!users.isEmpty()) {
			return users.iterator().next().getId();
		}

		try {
			List<Integer> matches = UserQueries.getUserIdsByNickname(bot.getDatabase(), user);
			if (matches.isEmpty()) matches = UserQueries.getUserIdsByUsername(bot.getDatabase(), user);

			return matches.isEmpty() ? -1 : matches.get(matches.size() - 1);
		} catch (SQLException e) {
			throw new RuntimeException(e);
		}
	}

	private static boolean isDiscordUserId(long val) {
		return val < 0 || val > Integer.MAX_VALUE; // a snowflake in 0..2^31-1 would required creation within the first 511 ms of its defined time span
	}

	public void registerEarlyHandlers(ChainableGloballyAttachableListenerManager src) {
		src.addServerBecomesAvailableListener(this);
		src.addServerMemberJoinListener(this);
		src.addServerMemberLeaveListener(this);
		src.addUserChangeNameListener(this);
		src.addUserChangeNicknameListener(this);
	}

	public void init(Server server) {
		assert server.getId() == serverId;
		assert server.hasAllMembersInCache();

		Collection<User> users = server.getMembers();
		Collection<SessionDiscordUserData> dbUsers = new ArrayList<>(users.size());

		for (User user : users) {
			dbUsers.add(toDbUser(user, server));
		}

		try {
			UserQueries.updateNewUsers(bot.getDatabase(), dbUsers);
		} catch (SQLException e) {
			throw new RuntimeException(e);
		}
	}

	@Override
	public void onServerBecomesAvailable(ServerBecomesAvailableEvent event) {
		Server server = event.getServer();

		if (server.getId() == serverId) {
			init(server);
		}
	}

	@Override
	public void onServerMemberJoin(ServerMemberJoinEvent event) {
		if (event.getServer().getId() == serverId) {
			refreshUser(event.getUser(), event.getServer());
		}
	}

	@Override
	public void onServerMemberLeave(ServerMemberLeaveEvent event) {
		if (event.getServer().getId() == serverId) {
			refreshUser(event.getUser(), event.getServer());
		}
	}

	@Override
	public void onUserChangeName(UserChangeNameEvent event) {
		Server server = null;

		for (Server s : event.getUser().getMutualServers()) {
			if (s.getId() == serverId) {
				server = s;
				break;
			}
		}

		if (server != null) {
			refreshUser(event.getUser(), server);
		}
	}

	@Override
	public void onUserChangeNickname(UserChangeNicknameEvent event) {
		if (event.getServer().getId() == serverId) {
			refreshUser(event.getUser(), event.getServer());
		}
	}

	private void refreshUser(User user, Server server) {
		SessionDiscordUserData dbUser = toDbUser(user, server);

		try {
			UserQueries.updateNewUsers(bot.getDatabase(), Collections.singletonList(dbUser));
		} catch (SQLException e) {
			throw new RuntimeException(e);
		}
	}

	private static SessionDiscordUserData toDbUser(User user, Server server) {
		return new SessionDiscordUserData(user.getId(), user.getName(), user.getDiscriminator(), user.getNickname(server).orElse(null));
	}
}
