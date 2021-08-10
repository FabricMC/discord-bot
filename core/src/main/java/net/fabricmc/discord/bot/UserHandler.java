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

import it.unimi.dsi.fastutil.longs.LongList;
import it.unimi.dsi.fastutil.longs.LongLists;
import org.javacord.api.entity.message.MessageAuthor;
import org.javacord.api.entity.server.Server;
import org.javacord.api.entity.user.User;
import org.javacord.api.event.server.member.ServerMemberJoinEvent;
import org.javacord.api.event.server.member.ServerMemberLeaveEvent;
import org.javacord.api.event.user.UserChangeNameEvent;
import org.javacord.api.event.user.UserChangeNicknameEvent;
import org.javacord.api.listener.ChainableGloballyAttachableListenerManager;
import org.javacord.api.listener.server.member.ServerMemberJoinListener;
import org.javacord.api.listener.server.member.ServerMemberLeaveListener;
import org.javacord.api.listener.user.UserChangeNameListener;
import org.javacord.api.listener.user.UserChangeNicknameListener;
import org.jetbrains.annotations.Nullable;

import net.fabricmc.discord.bot.database.query.UserQueries;
import net.fabricmc.discord.bot.database.query.UserQueries.DiscordUserData;
import net.fabricmc.discord.bot.database.query.UserQueries.SessionDiscordUserData;
import net.fabricmc.discord.bot.database.query.UserQueries.UserData;

public final class UserHandler implements ServerMemberJoinListener, ServerMemberLeaveListener, UserChangeNameListener, UserChangeNicknameListener {
	public static final String ADMIN_PERMISSION = "admin";
	public static final String IMMUNITY_PERMISSION = "immunity";
	public static final String BYPASS_IMMUNITY_PERMISSION = "bypassImmunity";

	private final DiscordBot bot;
	private int botUserId;
	private long botDiscordUserId;

	public UserHandler(DiscordBot bot) {
		this.bot = bot;

		bot.getActiveHandler().registerReadyHandler(this::init);

		try {
			if (!UserQueries.hasAnyPermittedUser(bot.getDatabase(), ADMIN_PERMISSION)) {
				System.err.println("no admin account configured!"); // TODO: show more visibly
			}
		} catch (SQLException e) {
			throw new RuntimeException(e);
		}
	}

	public int getBotUserId() {
		return botUserId;
	}

	public long getBotDiscordUserId() {
		return botDiscordUserId;
	}

	public User getBotDiscordUser(Server server) {
		return server.getMemberById(botDiscordUserId).orElse(null);
	}

	public boolean hasPermission(int userId, @Nullable Server server, String permission) {
		try {
			return UserQueries.userHasPermission(bot.getDatabase(), userId, permission, ADMIN_PERMISSION)
					|| hasPermissionFallback(userId, server, permission);
		} catch (SQLException e) {
			throw new RuntimeException(e);
		}
	}

	public boolean hasPermission(User user, @Nullable Server server, String permission) {
		return hasPermission(user.getId(), server, permission);
	}

	public boolean hasPermission(long discordUserId, @Nullable Server server, String permission) {
		try {
			return UserQueries.discordUserHasPermission(bot.getDatabase(), discordUserId, permission, ADMIN_PERMISSION)
					|| hasPermissionFallback(discordUserId, server, permission);
		} catch (SQLException e) {
			throw new RuntimeException(e);
		}
	}

	private boolean hasPermissionFallback(int userId, @Nullable Server server, String permission) throws SQLException {
		if (!canUsePermissionFallback(server, permission)) return false;

		for (User user : getDiscordUsers(userId, server)) {
			if (server.isAdmin(user)) return true;
		}

		return false;
	}

	private boolean hasPermissionFallback(long discordUserId, @Nullable Server server, String permission) throws SQLException {
		if (!canUsePermissionFallback(server, permission)) return false;

		User user = server.getMemberById(discordUserId).orElse(null);

		return user != null && server.isAdmin(user);
	}

	private boolean canUsePermissionFallback(@Nullable Server server, String permission) throws SQLException {
		return permission.equals(ADMIN_PERMISSION)
				&& server != null
				&& server.getId() == bot.getServerId()
				&& !UserQueries.hasAnyPermittedUser(bot.getDatabase(), permission);
	}

	public boolean hasImmunity(int targetUserId, int actingUserId, boolean allowBotTarget) {
		return !allowBotTarget && targetUserId == botUserId
				|| hasPermission(targetUserId, null, IMMUNITY_PERMISSION) && !hasPermission(actingUserId, null, BYPASS_IMMUNITY_PERMISSION);
	}

	public boolean hasImmunity(User targetUser, int actingUserId, boolean allowBotTarget) {
		return hasImmunity(targetUser.getId(), actingUserId, allowBotTarget);
	}

	public boolean hasImmunity(MessageAuthor targetAuthor, int actingUserId, boolean allowBotTarget) {
		return !targetAuthor.isUser() || hasImmunity(targetAuthor.getId(), actingUserId, allowBotTarget);
	}

	public boolean hasImmunity(long targetDiscordUserId, int actingUserId, boolean allowBotTarget) {
		return !allowBotTarget && targetDiscordUserId == botDiscordUserId
				|| hasPermission(targetDiscordUserId, null, IMMUNITY_PERMISSION) && !hasPermission(actingUserId, null, BYPASS_IMMUNITY_PERMISSION);
	}

	public int getUserId(String user, @Nullable Server server, boolean unique) {
		long ret = parseUserId(user, server, unique, true);

		if (ret == -1 || !isDiscordUserId(ret)) {
			return (int) ret;
		} else {
			return getUserId(ret);
		}
	}

	public long getDiscordUserId(String user, @Nullable Server server, boolean unique) {
		long ret = parseUserId(user, server, unique, true);

		if (ret == -1 || isDiscordUserId(ret)) {
			return ret;
		} else {
			LongList matches = getDiscordUserIds((int) ret);

			return matches.isEmpty() || unique && matches.size() > 1 ? -1 : matches.getLong(matches.size() - 1);
		}
	}

	public @Nullable User getDiscordUser(String user, Server server, boolean unique) {
		long id = getDiscordUserId(user, server, unique);

		return id >= 0 ? server.getMemberById(id).orElse(null) : null;
	}

	/*public @Nullable UserEntry getUserEntry(String user, Server server, boolean unique) {
		long id = parseUserId(user, server, unique, true);

		if (id == -1) {
			return null;
		} else if (isDiscordUserId(id)) {
			return new UserEntry(getUserId(id), server.getMemberById(id).orElse(null));
		} else {
			long discordId = getDiscordUserId((int) id, unique);

			return new UserEntry((int) id, discordId >= 0 ? server.getMemberById(discordId).orElse(null) : null);
		}
	}

	public record UserEntry(int id, List<User> discordUsers) { }*/

	public LongList getDiscordUserIds(String user, @Nullable Server server) {
		long ret = parseUserId(user, server, true, true);

		if (ret < 0) {
			return LongLists.emptyList();
		} else if (isDiscordUserId(ret)) {
			return LongLists.singleton(ret);
		} else {
			return getDiscordUserIds((int) ret);
		}
	}

	private long parseUserId(String user, @Nullable Server server, boolean unique, boolean searchOffline) {
		// find by id if applicable

		try {
			if (user.startsWith("<@") && user.endsWith(">")) { // <@userid> (name derived) or <@!userid> (nick derived)
				char next = user.charAt(2);
				int start = next >= '0' && next <= '9' ? 2 : 3;

				return Long.parseUnsignedLong(user.substring(start, user.length() - 1));
			}

			return Long.parseUnsignedLong(user);
		} catch (NumberFormatException e) { }

		// find by name#discriminator if applicable

		int pos = user.indexOf('#');

		if (pos >= 0) {
			String username = user.substring(0, pos);
			String discriminator = user.substring(pos + 1);

			if (server != null) {
				User res = server.getMemberByNameAndDiscriminator(username, discriminator).orElse(null);
				if (res != null) return res.getId();
			}

			if (!searchOffline) return -1;

			try {
				List<Integer> matches = UserQueries.getUserIds(bot.getDatabase(), username, discriminator);
				return matches.isEmpty() || unique && matches.size() > 1 ? -1 : matches.get(matches.size() - 1);
			} catch (SQLException e) {
				throw new RuntimeException(e);
			}
		}

		// find by name or nick

		if (server != null) {
			Collection<User> users = server.getMembersByNickname(user);
			if (users.isEmpty()) users = server.getMembersByName(user);
			if (users.isEmpty()) users = server.getMembersByDisplayNameIgnoreCase(user);
			if (users.isEmpty()) users = server.getMembersByNameIgnoreCase(user);

			if (!users.isEmpty()) {
				return unique && users.size() > 1 ? -1 : users.iterator().next().getId();
			}
		}

		if (!searchOffline) return -1;

		try {
			List<Integer> matches = UserQueries.getUserIdsByNickname(bot.getDatabase(), user);
			if (matches.isEmpty()) matches = UserQueries.getUserIdsByUsername(bot.getDatabase(), user);

			return matches.isEmpty() || unique && matches.size() > 1 ? -1 : matches.get(matches.size() - 1);
		} catch (SQLException e) {
			throw new RuntimeException(e);
		}
	}

	public int getUserId(User user) {
		return getUserId(user.getId());
	}

	public int getUserId(long discordId) {
		try {
			return UserQueries.getUserId(bot.getDatabase(), discordId);
		} catch (SQLException e) {
			throw new RuntimeException(e);
		}
	}

	public LongList getDiscordUserIds(int userId) {
		try {
			return UserQueries.getDiscordUserIds(bot.getDatabase(), userId);
		} catch (SQLException e) {
			throw new RuntimeException(e);
		}
	}

	public List<User> getDiscordUsers(int userId, Server server) {
		LongList ids = getDiscordUserIds(userId);
		if (ids.isEmpty()) return Collections.emptyList();

		List<User> ret = new ArrayList<>(ids.size());

		for (long id : ids) {
			User user = server.getMemberById(id).orElse(null);
			if (user != null) ret.add(user);
		}

		return ret;
	}

	private static boolean isDiscordUserId(long val) {
		return val < 0 || val > Integer.MAX_VALUE; // a snowflake in 0..2^31-1 would required creation within the first 511 ms of its defined time span
	}

	public @Nullable UserData getUserData(int userId, boolean fetchNameHistory, boolean fetchNickHistory) {
		try {
			return UserQueries.getUserData(bot.getDatabase(), userId, fetchNameHistory, fetchNickHistory);
		} catch (SQLException e) {
			throw new RuntimeException(e);
		}
	}

	public @Nullable DiscordUserData getDiscordUserData(long discordUserId, boolean fetchNameHistory, boolean fetchNickHistory) {
		try {
			return UserQueries.getDiscordUserData(bot.getDatabase(), discordUserId, fetchNameHistory, fetchNickHistory);
		} catch (SQLException e) {
			throw new RuntimeException(e);
		}
	}

	public String formatUser(int userId, @Nullable Server server) {
		LongList ids = getDiscordUserIds(userId);
		if (ids.isEmpty()) return Integer.toString(userId);

		if (server != null) {
			for (long id : ids) {
				User user = server.getMemberById(id).orElse(null);
				if (user != null) return formatDiscordUser(user);
			}
		}

		return formatDiscordUser(ids.getLong(0), server);
	}

	public String formatDiscordUser(long discordUserId, @Nullable Server server) {
		User user;
		DiscordUserData data;

		if (server != null && (user = server.getMemberById(discordUserId).orElse(null)) != null) {
			return formatDiscordUser(user);
		} else if ((data = getDiscordUserData(discordUserId, false, false)) != null) {
			return formatDiscordUser(data);
		} else {
			return formatDiscordUser(discordUserId, null, null);
		}
	}

	public static String formatDiscordUser(MessageAuthor author) {
		User user = author.asUser().orElse(null);

		return user != null ? formatDiscordUser(user) : "(unknown)";
	}

	public static String formatDiscordUser(User user) {
		return formatDiscordUser(user.getId(), user.getName(), user.getDiscriminator());
	}

	public static String formatDiscordUser(DiscordUserData user) {
		return formatDiscordUser(user.id(), user.username(), user.discriminator());
	}

	private static String formatDiscordUser(long discordUserId, String name, String discriminator) {
		if (name != null && discriminator != null) {
			return "<@!%d> (%s#%s / `%d`)".formatted(discordUserId, name, discriminator, discordUserId);
		} else {
			return "<@!%d> (`%d`)".formatted(discordUserId, discordUserId);
		}
	}

	void registerEarlyHandlers(ChainableGloballyAttachableListenerManager src) {
		src.addServerMemberJoinListener(this);
		src.addServerMemberLeaveListener(this);
		src.addUserChangeNameListener(this);
		src.addUserChangeNicknameListener(this);
	}

	private void init(Server server, long lastActiveTime) {
		assert server.getId() == bot.getServerId();
		assert server.hasAllMembersInCache();

		Collection<User> users = server.getMembers();
		Collection<SessionDiscordUserData> dbUsers = new ArrayList<>(users.size());

		for (User user : users) {
			dbUsers.add(toDbUser(user, server, true));
		}

		try {
			UserQueries.updateNewUsers(bot.getDatabase(), dbUsers, true, lastActiveTime);
		} catch (SQLException e) {
			throw new RuntimeException(e);
		}

		botDiscordUserId = server.getApi().getYourself().getId();
		botUserId = getUserId(botDiscordUserId);
	}

	@Override
	public void onServerMemberJoin(ServerMemberJoinEvent event) {
		Server server = event.getServer();
		if (server.getId() != bot.getServerId()) return;

		refreshUser(server, event.getUser(), true, false);
	}

	@Override
	public void onServerMemberLeave(ServerMemberLeaveEvent event) {
		Server server = event.getServer();
		if (server.getId() != bot.getServerId()) return;

		refreshUser(server, event.getUser(), false, true);
	}

	@Override
	public void onUserChangeName(UserChangeNameEvent event) {
		User user = event.getUser();
		Server server = null;

		for (Server s : user.getMutualServers()) {
			if (s.getId() == bot.getServerId()) {
				server = s;
				break;
			}
		}

		if (server == null) return;

		assert user.getName().equals(event.getNewName());

		if (!bot.getActionSyncHandler().applyNickLock(server, user)) { // only update db if nick lock didn't reject the name change
			refreshUser(server, user, true, true);
		}
	}

	@Override
	public void onUserChangeNickname(UserChangeNicknameEvent event) {
		Server server = event.getServer();
		if (server.getId() != bot.getServerId()) return;

		User user = event.getUser();

		assert user.getNickname(server).equals(event.getNewNickname());

		if (!bot.getActionSyncHandler().applyNickLock(server, user)) { // only update db if nick lock didn't reject the name change
			refreshUser(server, user, true, true);
		}
	}

	private void refreshUser(Server server, User user, boolean present, boolean wasPresent) {
		SessionDiscordUserData dbUser = toDbUser(user, server, present);

		try {
			UserQueries.updateNewUsers(bot.getDatabase(), Collections.singletonList(dbUser), false, wasPresent ? System.currentTimeMillis() : 0);
		} catch (SQLException e) {
			throw new RuntimeException(e);
		}
	}

	private static SessionDiscordUserData toDbUser(User user, Server server, boolean present) {
		return new SessionDiscordUserData(user.getId(), user.getName(), user.getDiscriminator(), user.getNickname(server).orElse(null), present);
	}
}
