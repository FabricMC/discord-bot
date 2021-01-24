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

package net.fabricmc.discord.bot.database.query;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

import org.jetbrains.annotations.Nullable;

import net.fabricmc.discord.bot.database.Database;

public final class UserQueries {
	/**
	 * Create/update db entries for every supplied discord user.
	 *
	 * This will record metadata and name/nick history, update last seen timestamps and presence states and create internal user entries.
	 *
	 * @param lastActiveTime last time when a previously present user is assumed to having been around with its recorded properties OR 0 to use the recorded lastseen time
	 */
	public static void updateNewUsers(Database db, Collection<SessionDiscordUserData> users, boolean isCompleteList, long lastActiveTime) throws SQLException {
		if (db == null) throw new NullPointerException("null db");
		if (users == null) throw new NullPointerException("null users");

		long time = System.currentTimeMillis();

		try (Connection conn = db.getConnection();
				PreparedStatement updatePresence = conn.prepareStatement("UPDATE discorduser SET lastseen = ?, present = '0' WHERE present = '1'");
				PreparedStatement psGetDU = conn.prepareStatement("SELECT user_id, username, discriminator, nickname, firstseen, lastseen, lastnickchange, present FROM discorduser WHERE id = ?");
				PreparedStatement psAddDU = conn.prepareStatement("INSERT INTO discorduser (id, user_id, username, discriminator, nickname, firstseen, lastseen, lastnickchange, present) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)");
				PreparedStatement psUpdateDuTimePresent = conn.prepareStatement("UPDATE discorduser SET lastseen = ?, present = ? WHERE id = ?");
				PreparedStatement psUpdateDuNameTimePresent = conn.prepareStatement("UPDATE discorduser SET username = ?, discriminator = ?, nickname = ?, lastseen = ?, lastNickChange = ?, present = ? WHERE id = ?");
				PreparedStatement psAddUser = conn.prepareStatement("INSERT INTO user (stickyname) VALUES (?)", Statement.RETURN_GENERATED_KEYS);
				PreparedStatement psGetLastNameChange = conn.prepareStatement("SELECT MAX(lastused) FROM discorduser_namelog WHERE discorduser_id = ?");
				PreparedStatement psRecordNameChange = conn.prepareStatement("INSERT INTO discorduser_namelog (discorduser_id, username, discriminator, firstused, lastused, duration) VALUES (?, ?, ?, ?, ?, ?) ON CONFLICT (discorduser_id, username, discriminator) DO UPDATE SET lastused = excluded.lastused, duration = duration + excluded.duration, count = count + 1");
				PreparedStatement psRecordNickChange = conn.prepareStatement("INSERT INTO discorduser_nicklog (discorduser_id, nickname, firstused, lastused, duration) VALUES (?, ?, ?, ?, ?) ON CONFLICT (discorduser_id, nickname) DO UPDATE SET lastused = excluded.lastused, duration = duration + excluded.duration, count = count + 1")) {
			conn.setAutoCommit(false);

			if (isCompleteList) {
				assert lastActiveTime != 0;
				updatePresence.setLong(1, lastActiveTime);
				updatePresence.executeUpdate();
			}

			for (SessionDiscordUserData user : users) {
				psGetDU.setLong(1, user.id);

				try (ResultSet res = psGetDU.executeQuery()) {
					if (!res.next()) { // missing entry
						// create internal user first to obtain its autoincrement id
						psAddUser.setString(1, user.username);
						psAddUser.executeUpdate();
						int userId;

						try (ResultSet res2 = psAddUser.getGeneratedKeys()) {
							if (!res2.next()) throw new IllegalStateException();
							userId = res2.getInt(1);
						}

						psAddDU.setLong(1, user.id);
						psAddDU.setInt(2, userId);
						psAddDU.setString(3, user.username);
						psAddDU.setString(4, user.discriminator);
						psAddDU.setString(5, user.nickname);
						psAddDU.setLong(6, time); // firstseen
						psAddDU.setLong(7, time); // lastseen
						psAddDU.setLong(8, time); // lastNickChange
						psAddDU.setBoolean(9, user.present);
						psAddDU.addBatch();
					} else {
						String oldUsername = res.getString(2);
						String oldDiscriminator = res.getString(3);
						String oldNickname = res.getString(4);
						boolean usernameChanged = !oldUsername.equals(user.username) || !oldDiscriminator.equals(user.discriminator);
						boolean nickChanged = !Objects.equals(oldNickname, user.nickname);

						if (usernameChanged || nickChanged) { // names changed
							long firstSeen = res.getLong(5);
							long lastSeen = res.getLong(6);
							long lastNickChange = res.getLong(7);
							boolean oldPresent = res.getBoolean(8);
							long lastUsed = oldPresent && lastActiveTime != 0 ? lastActiveTime : lastSeen;

							// update name, discriminator, last seen
							psUpdateDuNameTimePresent.setString(1, user.username);
							psUpdateDuNameTimePresent.setString(2, user.discriminator);
							psUpdateDuNameTimePresent.setString(3, user.nickname);
							psUpdateDuNameTimePresent.setLong(4, time); // lastseen
							psUpdateDuNameTimePresent.setLong(5, nickChanged ? lastUsed : lastNickChange); // lastNickChange
							psUpdateDuNameTimePresent.setBoolean(6, user.present);
							psUpdateDuNameTimePresent.setLong(7, user.id);
							psUpdateDuNameTimePresent.addBatch();

							if (usernameChanged) {
								// store old username
								psGetLastNameChange.setLong(1, user.id);
								long firstUsed = firstSeen;

								try (ResultSet res2 = psGetLastNameChange.executeQuery()) {
									if (res2.next()) firstUsed = res2.getLong(1);
								}

								psRecordNameChange.setLong(1, user.id);
								psRecordNameChange.setString(2, oldUsername);
								psRecordNameChange.setString(3, oldDiscriminator);
								psRecordNameChange.setLong(4, firstUsed); // firstused
								psRecordNameChange.setLong(5, lastUsed); // lastused
								psRecordNameChange.setLong(6, lastUsed - firstUsed); // duration
								psRecordNameChange.addBatch();
							}

							if (nickChanged && oldNickname != null) {
								// store old nickname
								psRecordNickChange.setLong(1, user.id);
								psRecordNickChange.setString(2, oldNickname);
								psRecordNickChange.setLong(3, lastNickChange); // firstused
								psRecordNickChange.setLong(4, lastUsed); // lastused
								psRecordNickChange.setLong(5, lastUsed - lastNickChange); // duration
								psRecordNickChange.addBatch();
							}
						} else { // existing user, same names
							// update last seen
							psUpdateDuTimePresent.setLong(1, time); // lastseen
							psUpdateDuTimePresent.setBoolean(2, user.present);
							psUpdateDuTimePresent.setLong(3, user.id);
							psUpdateDuTimePresent.addBatch();
						}
					}
				}
			}

			psAddDU.executeBatch();
			psUpdateDuNameTimePresent.executeBatch();
			psUpdateDuTimePresent.executeBatch();
			psRecordNameChange.executeBatch();
			psRecordNickChange.executeBatch();
			conn.commit();
		}
	}

	public record SessionDiscordUserData(long id, String username, String discriminator, @Nullable String nickname, boolean present) { }

	public static int getUserId(Database db, long discordUserId) throws SQLException {
		if (db == null) throw new NullPointerException("null db");

		try (Connection conn = db.getConnection();
				PreparedStatement ps = conn.prepareStatement("SELECT user_id FROM discorduser WHERE id = ?")) {
			ps.setLong(1, discordUserId);

			try (ResultSet res = ps.executeQuery()) {
				if (!res.next()) return -1;

				return res.getInt(1);
			}
		}
	}

	public static List<Long> getDiscordUserIds(Database db, int userId) throws SQLException {
		if (db == null) throw new NullPointerException("null db");

		try (Connection conn = db.getConnection();
				PreparedStatement ps = conn.prepareStatement("SELECT id FROM discorduser WHERE user_id = ?")) {
			ps.setInt(1, userId);

			try (ResultSet res = ps.executeQuery()) {
				List<Long> ret = new ArrayList<>();

				while (res.next()) {
					ret.add(res.getLong(1));
				}

				return ret;
			}
		}
	}

	public static List<Integer> getUserIds(Database db, String username, String discriminator) throws SQLException {
		if (db == null) throw new NullPointerException("null db");

		try (Connection conn = db.getConnection();
				PreparedStatement ps = conn.prepareStatement("""
						SELECT * FROM (
						SELECT du.user_id AS id FROM discorduser_namelog nl, discorduser du WHERE nl.username = ? AND nl.discriminator = ? AND du.id = nl.discorduser_id
						UNION
						SELECT user_id AS id FROM discorduser WHERE username = ? AND discriminator = ?
						) GROUP BY id""")) {
			List<Integer> ret = new ArrayList<>();
			ps.setString(1, username);
			ps.setString(2, discriminator);
			ps.setString(3, username);
			ps.setString(4, discriminator);

			try (ResultSet res = ps.executeQuery()) {
				while (res.next()) {
					ret.add(res.getInt(1));
				}
			}

			return ret;
		}
	}

	public static List<Integer> getUserIdsByUsername(Database db, String username) throws SQLException {
		if (db == null) throw new NullPointerException("null db");

		try (Connection conn = db.getConnection();
				PreparedStatement ps = conn.prepareStatement("""
						SELECT * FROM (
						SELECT du.user_id AS id FROM discorduser_namelog nl, discorduser du WHERE nl.username = ? AND du.id = nl.discorduser_id
						UNION
						SELECT user_id AS id FROM discorduser WHERE username = ?
						) GROUP BY id""")) {
			List<Integer> ret = new ArrayList<>();
			ps.setString(1, username);
			ps.setString(2, username);

			try (ResultSet res = ps.executeQuery()) {
				while (res.next()) {
					ret.add(res.getInt(1));
				}
			}

			return ret;
		}
	}

	public static List<Integer> getUserIdsByNickname(Database db, String nickname) throws SQLException {
		if (db == null) throw new NullPointerException("null db");

		try (Connection conn = db.getConnection();
				PreparedStatement ps = conn.prepareStatement("""
						SELECT * FROM (
						SELECT du.user_id AS id FROM discorduser_nicklog nl, discorduser du WHERE nl.nickname = ? AND du.id = nl.discorduser_id
						UNION
						SELECT user_id AS id FROM discorduser WHERE nickname = ?
						) GROUP BY id""")) {
			List<Integer> ret = new ArrayList<>();
			ps.setString(1, nickname);
			ps.setString(2, nickname);

			try (ResultSet res = ps.executeQuery()) {
				while (res.next()) {
					ret.add(res.getInt(1));
				}
			}

			return ret;
		}
	}

	public static UserData getUserData(Database db, int userId, boolean fetchNameHistory, boolean fetchNickHistory) throws SQLException {
		if (db == null) throw new NullPointerException("null db");

		try (Connection conn = db.getConnection();
				PreparedStatement psUser = conn.prepareStatement("SELECT stickyName FROM user WHERE id = ?");
				PreparedStatement psDU = conn.prepareStatement("SELECT id, username, discriminator, nickname, firstseen, lastseen FROM discorduser WHERE user_id = ?");
				PreparedStatement psNameLog = conn.prepareStatement("SELECT username, discriminator, firstused, lastused, duration, count FROM discorduser_namelog WHERE discorduser_id = ?");
				PreparedStatement psNickLog = conn.prepareStatement("SELECT nickname, firstused, lastused, duration, count FROM discorduser_nicklog WHERE discorduser_id = ?")) {
			UserData ret;
			psUser.setInt(1, userId);

			try (ResultSet res = psUser.executeQuery()) {
				if (!res.next()) return null;

				ret = new UserData(userId, res.getString(1), new ArrayList<>());
			}

			psDU.setInt(1, userId);

			try (ResultSet res = psDU.executeQuery()) {
				while (res.next()) {
					long discordUserId = res.getLong(1);
					List<DiscordNameHistory> nameLog = fetchNameHistory ? new ArrayList<>() : null;
					List<DiscordNickHistory> nickLog = fetchNickHistory ? new ArrayList<>() : null;
					DiscordUserData data = new DiscordUserData(discordUserId, res.getString(2), res.getString(3), res.getString(4), res.getLong(5), res.getLong(6), nameLog, nickLog);
					ret.discordUsers.add(data);

					if (fetchNameHistory) {
						psNameLog.setLong(1, discordUserId);

						try (ResultSet res2 = psNameLog.executeQuery()) {
							while (res2.next()) {
								nameLog.add(new DiscordNameHistory(res2.getString(1), res2.getString(2), res2.getLong(3), res2.getLong(4), res2.getLong(5), res2.getInt(6)));
							}
						}
					}

					if (fetchNickHistory) {
						psNickLog.setLong(1, discordUserId);

						try (ResultSet res2 = psNickLog.executeQuery()) {
							while (res2.next()) {
								nickLog.add(new DiscordNickHistory(res2.getString(1), res2.getLong(2), res2.getLong(3), res2.getLong(4), res2.getInt(5)));
							}
						}
					}
				}
			}

			return ret;
		}
	}

	public record UserData(int id, String stickyName, Collection<DiscordUserData> discordUsers) { }
	public record DiscordUserData(long id, String username, String discriminator, String nickname, long firstSeen, long lastSeen,
			@Nullable Collection<DiscordNameHistory> nameHistory, @Nullable Collection<DiscordNickHistory> nickHistory) { }
	public record DiscordNameHistory(String username, String discriminator, long firstUsed, long lastUsed, long duration, int count) { }
	public record DiscordNickHistory(String nickname, long firstUsed, long lastUsed, long duration, int count) { }

	public static Collection<String> getDirectGroups(Database db, int userId) throws SQLException {
		if (db == null) throw new NullPointerException("null db");

		try (Connection conn = db.getConnection();
				PreparedStatement ps = conn.prepareStatement("SELECT `group`.id, group.name FROM user_group, `group` WHERE user_group.user_id = ? AND `group`.id = user_group.group_id")) {
			ps.setInt(1, userId);

			try (ResultSet res = ps.executeQuery()) {
				List<String> ret = new ArrayList<>();

				while (res.next()) {
					ret.add(res.getString(1));
				}

				return ret;
			}
		}
	}

	public static boolean addToGroup(Database db, int userId, String group) throws SQLException {
		if (db == null) throw new NullPointerException("null db");
		if (group == null) throw new NullPointerException("null group");

		try (Connection conn = db.getConnection();
				PreparedStatement psGetGroup = conn.prepareStatement("SELECT id FROM `group` WHERE name = ?");
				PreparedStatement psAddGroup = conn.prepareStatement("INSERT OR IGNORE INTO `group` (name) VALUES (?)");
				PreparedStatement psAdd = conn.prepareStatement("INSERT OR IGNORE INTO user_group (user_id, group_id) VALUES (?, ?)")) {
			conn.setAutoCommit(false);

			psGetGroup.setString(1, group);
			int groupId;

			try (ResultSet res = psGetGroup.executeQuery()) {
				if (res.next()) {
					groupId = res.getInt(1);
				} else {
					psAddGroup.setString(1, group);
					psAddGroup.executeUpdate();

					try (ResultSet res2 = psGetGroup.executeQuery()) {
						if (!res2.next()) throw new IllegalStateException();
						groupId = res2.getInt(1);
					}
				}
			}

			psAdd.setInt(1, userId);
			psAdd.setInt(2, groupId);
			boolean ret = psAdd.executeUpdate() > 0;

			conn.commit();

			return ret;
		}
	}

	public static boolean removeFromGroup(Database db, int userId, String group) throws SQLException {
		if (db == null) throw new NullPointerException("null db");
		if (group == null) throw new NullPointerException("null group");

		try (Connection conn = db.getConnection();
				PreparedStatement ps = conn.prepareStatement("DELETE FROM user_group WHERE user_id = ? AND group_id = (SELECT id FROM `group` WHERE name = ?)")) {
			ps.setInt(1, userId);
			ps.setString(2, group);

			return ps.executeUpdate() > 0;
		}
	}

	public static Collection<String> getGroups(Database db) throws SQLException {
		if (db == null) throw new NullPointerException("null db");

		try (Connection conn = db.getConnection();
				PreparedStatement ps = conn.prepareStatement("SELECT name FROM `group`")) {
			try (ResultSet res = ps.executeQuery()) {
				List<String> ret = new ArrayList<>();

				while (res.next()) {
					ret.add(res.getString(1));
				}

				return ret;
			}
		}
	}

	public static boolean addGroup(Database db, String name) throws SQLException {
		if (db == null) throw new NullPointerException("null db");
		if (name == null) throw new NullPointerException("null name");

		try (Connection conn = db.getConnection();
				PreparedStatement ps = conn.prepareStatement("INSERT OR IGNORE INTO `group` (name) VALUES (?)")) {
			ps.setString(1, name);

			return ps.executeUpdate() > 0;
		}
	}

	public static boolean removeGroup(Database db, String name) throws SQLException {
		if (db == null) throw new NullPointerException("null db");

		try (Connection conn = db.getConnection();
				PreparedStatement psGroup = conn.prepareStatement("SELECT `id` FROM `group` WHERE `name` = ?");
				PreparedStatement psUserGroup = conn.prepareStatement("DELETE FROM `user_group` WHERE `group_id` = ?");
				PreparedStatement psGroupInherit = conn.prepareStatement("DELETE FROM `group_inheritance` WHERE `parent_id` = ? OR `child_id` = ?");
				PreparedStatement psGroupPerm = conn.prepareStatement("DELETE FROM `group_permission` WHERE `group_id` = ?")) {
			conn.setAutoCommit(false);

			psGroup.setString(1, name);
			int id;

			try (ResultSet res = psGroup.executeQuery()) {
				if (!res.next()) return false;
				id = res.getInt(1);
				res.deleteRow();
			}

			psUserGroup.setInt(1, id);
			psUserGroup.executeUpdate();

			psGroupInherit.setInt(1, id);
			psGroupInherit.setInt(2, id);
			psGroupInherit.executeUpdate();

			psGroupPerm.setInt(1, id);
			psGroupPerm.executeUpdate();

			conn.commit();

			return true;
		}
	}

	public static Collection<String> getGroupChildren(Database db, String group) throws SQLException {
		if (db == null) throw new NullPointerException("null db");

		try (Connection conn = db.getConnection();
				PreparedStatement ps = conn.prepareStatement("SELECT gb.name FROM `group` ga, group_inheritance gi, `group` gb WHERE ga.name = ? AND gi.parent_id = ga.id AND gb.id = gi.child_id")) {
			ps.setString(1, group);

			try (ResultSet res = ps.executeQuery()) {
				List<String> ret = new ArrayList<>();

				while (res.next()) {
					ret.add(res.getString(1));
				}

				return ret;
			}
		}
	}

	public static boolean addGroupChild(Database db, String parent, String child) throws SQLException {
		if (db == null) throw new NullPointerException("null db");

		try (Connection conn = db.getConnection();
				PreparedStatement ps = conn.prepareStatement("INSERT OR IGNORE INTO group_inheritance (parent_id, child_id) SELECT a.id, b.id FROM `group` a, `group` b WHERE a.name = ? AND b.name = ?")) {
			ps.setString(1, parent);
			ps.setString(2, child);

			return ps.executeUpdate() > 0;
		}
	}

	public static boolean removeGroupChild(Database db, String parent, String child) throws SQLException {
		if (db == null) throw new NullPointerException("null db");

		try (Connection conn = db.getConnection();
				PreparedStatement ps = conn.prepareStatement("DELETE FROM `group_inheritance` WHERE `parent_id` = (SELECT id FROM `group` WHERE name = ?) AND `child_id` = (SELECT id FROM `group` WHERE name = ?)")) {
			ps.setString(1, parent);
			ps.setString(2, child);

			return ps.executeUpdate() > 0;
		}
	}

	public static Collection<String> getDirectGroupPermissions(Database db, String group) throws SQLException {
		if (db == null) throw new NullPointerException("null db");

		try (Connection conn = db.getConnection();
				PreparedStatement ps = conn.prepareStatement("SELECT gp.permission FROM `group` g, group_permission gp WHERE g.name = ? AND gp.group_id = g.id")) {
			ps.setString(1, group);

			try (ResultSet res = ps.executeQuery()) {
				List<String> ret = new ArrayList<>();

				while (res.next()) {
					ret.add(res.getString(1));
				}

				return ret;
			}
		}
	}

	public static boolean addGroupPermission(Database db, String group, String permission) throws SQLException {
		if (db == null) throw new NullPointerException("null db");
		if (permission == null) throw new NullPointerException("null permission");

		try (Connection conn = db.getConnection();
				PreparedStatement ps = conn.prepareStatement("INSERT OR IGNORE INTO group_permission (group_id, permission) VALUES ((SELECT id FROM `group` WHERE name = ?), ?)")) {
			ps.setString(1, group);
			ps.setString(2, permission);

			return ps.executeUpdate() > 0;
		}
	}

	public static boolean removeGroupPermission(Database db, String group, String permission) throws SQLException {
		if (db == null) throw new NullPointerException("null db");
		if (permission == null) throw new NullPointerException("null permission");

		try (Connection conn = db.getConnection();
				PreparedStatement ps = conn.prepareStatement("DELETE FROM `group_permission` WHERE `group_id` = (SELECT id FROM `group` WHERE name = ?) AND `permission` = ?")) {
			ps.setString(1, group);
			ps.setString(2, permission);

			return ps.executeUpdate() > 0;
		}
	}

	public static boolean discordUserHasPermission(Database db, long discordUserId, String permissionA, String permissionB) throws SQLException {
		if (db == null) throw new NullPointerException("null db");
		if (permissionA == null) throw new NullPointerException("null permissionA");
		if (permissionB == null) throw new NullPointerException("null permissionB");

		try (Connection conn = db.getConnection();
				PreparedStatement ps = conn.prepareStatement("""
						WITH RECURSIVE groups_cte (id) AS (
						SELECT user_group.group_id FROM discorduser, user_group WHERE discorduser.id = ? AND user_group.user_id = discorduser.user_id
						UNION
						SELECT group_inheritance.child_id FROM group_inheritance INNER JOIN groups_cte ON group_inheritance.parent_id = groups_cte.id
						)
						SELECT COUNT(*)
						FROM groups_cte, group_permission
						WHERE
						group_permission.group_id = groups_cte.id
						AND (group_permission.permission = ? OR group_permission.permission = ?)
						LIMIT 1
						"""
						/*"""
						SELECT COUNT(*)
						FROM discorduser, user_group, group_permission
						WHERE
						discorduser.id = ?
						AND user_group.user_id = discorduser.user_id
						AND group_permission.group_id = user_group.group_id
						AND group_permission.permission = ?
						"""*/)) {
			ps.setLong(1, discordUserId);
			ps.setString(2, permissionA);
			ps.setString(3, permissionB);

			try (ResultSet res = ps.executeQuery()) {
				if (!res.next()) throw new IllegalStateException();

				return res.getInt(1) > 0;
			}
		}
	}

	public static boolean hasAnyPermittedUser(Database db, String permission) throws SQLException {
		if (db == null) throw new NullPointerException("null db");
		if (permission == null) throw new NullPointerException("null permission");

		try (Connection conn = db.getConnection();
				PreparedStatement ps = conn.prepareStatement("""
						WITH RECURSIVE groups_cte (id) AS (
						SELECT user_group.group_id
						FROM group_permission, user_group
						WHERE
						group_permission.permission = ?
						AND user_group.group_id = group_permission.group_id
						UNION
						SELECT group_inheritance.parent_id FROM group_inheritance INNER JOIN groups_cte ON group_inheritance.child_id = groups_cte.id
						)
						SELECT COUNT(*)
						FROM groups_cte, user_group, discorduser
						WHERE
						user_group.group_id = groups_cte.id
						AND discorduser.user_id = user_group.user_id
						LIMIT 1
						""")) {
			ps.setString(1, permission);

			try (ResultSet res = ps.executeQuery()) {
				if (!res.next()) throw new IllegalStateException();

				return res.getInt(1) > 0;
			}
		}
	}
}
