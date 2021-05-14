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

package net.fabricmc.discord.bot.database;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.HashMap;
import java.util.Map;

import net.fabricmc.discord.bot.command.mod.ActionTargetKind;

final class DbMigration {
	public static void run(Database db) {
		try (Connection conn = db.getConnection();
				Statement st = conn.createStatement()) {
			conn.setAutoCommit(false);
			int count;

			try (ResultSet res = st.executeQuery("SELECT COUNT(*) FROM `sqlite_schema` WHERE `type` = 'table' AND `name` = 'config'")) {
				if (!res.next()) throw new IllegalStateException();
				count = res.getInt(1);
			}

			int version;

			if (count == 0) {
				st.executeUpdate("CREATE TABLE `config` (`key` TEXT PRIMARY KEY, `value` TEXT)");
				st.executeUpdate("INSERT INTO `config` VALUES ('dbVersion', '1')");
				version = 1;
				conn.commit();
			} else {
				try (ResultSet res = st.executeQuery("SELECT `value` FROM `config` WHERE `key` = 'dbVersion'")) {
					if (!res.next()) throw new IllegalStateException();
					version = Integer.parseInt(res.getString(1));
				}
			}

			if (version == Database.currentVersion) return;

			if (version > Database.currentVersion) {
				throw new RuntimeException("DB version "+version+" is newer than current version "+Database.currentVersion);
			}

			switch (version) { // fall-through for continuous migration
			case 1: migrate_1_2(st);
			case 2: migrate_2_3(st);
			case 3: migrate_3_4(st);
			case 4: migrate_4_5(st);
			case 5: migrate_5_6(st);
			case 6: migrate_6_7(st);
			case 7: migrate_7_8(st);
			case 8: migrate_8_9(st);
			}

			st.executeUpdate(String.format("REPLACE INTO `config` VALUES ('dbVersion', '%d')", Database.currentVersion));
			conn.commit();
		} catch (SQLException | NumberFormatException e) {
			throw new RuntimeException("Error initializing/migrating DB", e);
		}
	}

	private static void migrate_1_2(Statement st) throws SQLException {
		st.executeUpdate("CREATE TABLE `discorduser` (`id` INTEGER PRIMARY KEY, `user_id` INTEGER, `username` TEXT, `discriminator` TEXT, `nickname` TEXT, `firstseen` INTEGER, `lastseen` INTEGER, `lastnickchange` INTEGER)");
		st.executeUpdate("CREATE INDEX `discorduser_user_id` ON `discorduser` (`user_id`)");
		st.executeUpdate("CREATE INDEX `discorduser_username` ON `discorduser` (`username`)");
		st.executeUpdate("CREATE INDEX `discorduser_nickname` ON `discorduser` (`nickname`)");
		st.executeUpdate("CREATE TABLE `discorduser_namelog` (`discorduser_id` INTEGER, `username` TEXT, `discriminator` TEXT, `firstused` INTEGER, `lastused` INTEGER, `duration` INTEGER, `count` INTEGER DEFAULT 1, UNIQUE(`discorduser_id`, `username`, `discriminator`))");
		st.executeUpdate("CREATE INDEX `discorduser_namelog_id` ON `discorduser_namelog` (`discorduser_id`)");
		st.executeUpdate("CREATE INDEX `discorduser_namelog_username` ON `discorduser_namelog` (`username`)");
		st.executeUpdate("CREATE TABLE `discorduser_nicklog` (`discorduser_id` INTEGER, `nickname` TEXT, `firstused` INTEGER, `lastused` INTEGER, `duration` INTEGER, `count` INTEGER DEFAULT 1, UNIQUE(`discorduser_id`, `nickname`))");
		st.executeUpdate("CREATE INDEX `discorduser_nicklog_id` ON `discorduser_nicklog` (`discorduser_id`)");
		st.executeUpdate("CREATE INDEX `discorduser_nicklog_nickname` ON `discorduser_nicklog` (`nickname`)");

		st.executeUpdate("CREATE TABLE `user` (`id` INTEGER PRIMARY KEY AUTOINCREMENT, `stickyname` TEXT)");
		st.executeUpdate("CREATE TABLE `user_group` (`user_id` INTEGER, `group_id` INTEGER, UNIQUE(`user_id`, `group_id`))");
		st.executeUpdate("CREATE INDEX `user_group_user_id` ON `user_group` (`user_id`)");

		st.executeUpdate("CREATE TABLE `group` (`id` INTEGER PRIMARY KEY AUTOINCREMENT, `name` TEXT UNIQUE)");
		st.executeUpdate("CREATE TABLE `group_inheritance` (`parent_id` INTEGER, `child_id` INTEGER, UNIQUE(`parent_id`, `child_id`))");
		st.executeUpdate("CREATE INDEX `group_inheritance_parent_id` ON `group_inheritance` (`parent_id`)");
		st.executeUpdate("CREATE TABLE `group_permission` (`group_id` INTEGER, `permission` TEXT, UNIQUE(`group_id`, `permission`))");
		st.executeUpdate("CREATE INDEX `group_permission_group_id` ON `group_permission` (`group_id`)");
	}

	private static void migrate_2_3(Statement st) throws SQLException {
		st.executeUpdate("ALTER TABLE `discorduser` ADD COLUMN `present` INTEGER DEFAULT 1");
	}

	private static void migrate_3_4(Statement st) throws SQLException {
		st.executeUpdate("CREATE TABLE `action` (`id` INTEGER PRIMARY KEY AUTOINCREMENT, `type` TEXT, `target_user_id` INTEGER, `actor_user_id` INTEGER, `creation` INTEGER, `expiration` INTEGER, `reason` TEXT, `prev_id` INTEGER)");
		st.executeUpdate("CREATE INDEX `action_target_user_id` ON `action` (`target_user_id`)");
		st.executeUpdate("CREATE TABLE `actionsuspension` (`action_id` INTEGER PRIMARY KEY, `suspender_user_id` INTEGER, `time` INTEGER, `reason` TEXT)");
		st.executeUpdate("CREATE TABLE `actionexpiration` (`action_id` INTEGER PRIMARY KEY, `time` INTEGER)");
		st.executeUpdate("CREATE INDEX `actionexpiration_time` ON `actionexpiration` (`time`)");
		st.executeUpdate("CREATE TABLE `activeaction` (`action_id` INTEGER PRIMARY KEY, `target_user_id` INTEGER)");
		st.executeUpdate("CREATE INDEX `activeaction_target_user_id` ON `activeaction` (`target_user_id`)");
	}

	private static void migrate_4_5(Statement st) throws SQLException {
		st.executeUpdate("CREATE TABLE `note` (`id` INTEGER PRIMARY KEY AUTOINCREMENT, `target_user_id` INTEGER, `actor_user_id` INTEGER, `creation` INTEGER, `content` TEXT)");
		st.executeUpdate("CREATE INDEX `note_target_user_id` ON `note` (`target_user_id`)");
	}

	private static void migrate_5_6(Statement st) throws SQLException {
		st.executeUpdate("CREATE TABLE `nicklock` (`discorduser_id` INTEGER PRIMARY KEY, `nick` TEXT)");

		st.executeUpdate("CREATE TABLE `channelaction` (`id` INTEGER PRIMARY KEY AUTOINCREMENT, `type` TEXT, `channel_id` INTEGER, `data` INTEGER, `resetdata` INTEGER, `actor_user_id` INTEGER, `creation` INTEGER, `expiration` INTEGER, `reason` TEXT, `prev_id` INTEGER)");
		st.executeUpdate("CREATE INDEX `channelaction_channel_id` ON `channelaction` (`channel_id`)");
		st.executeUpdate("CREATE TABLE `channelactionsuspension` (`channelaction_id` INTEGER PRIMARY KEY, `suspender_user_id` INTEGER, `time` INTEGER, `reason` TEXT)");
		st.executeUpdate("CREATE TABLE `channelactionexpiration` (`channelaction_id` INTEGER PRIMARY KEY, `time` INTEGER)");
		st.executeUpdate("CREATE INDEX `channelactionexpiration_time` ON `channelactionexpiration` (`time`)");
		st.executeUpdate("CREATE TABLE `activechannelaction` (`channelaction_id` INTEGER PRIMARY KEY, `channel_id` INTEGER)");
		st.executeUpdate("CREATE INDEX `activechannelaction_channel_id` ON `activechannelaction` (`channel_id`)");
	}

	private static void migrate_6_7(Statement st) throws SQLException {
		st.executeUpdate("CREATE TABLE `filter` (`id` INTEGER PRIMARY KEY, `type` TEXT, `pattern` TEXT, `filtergroup_id` INTEGER, `hits` INTEGER DEFAULT 0)");
		st.executeUpdate("CREATE TABLE `filtergroup` (`id` INTEGER PRIMARY KEY, `name` TEXT UNIQUE, `description` TEXT, `filteraction_id` INTEGER)");
		st.executeUpdate("CREATE TABLE `filteraction` (`id` INTEGER PRIMARY KEY, `name` TEXT UNIQUE, `description` TEXT, `action` TEXT, `actiondata` TEXT)");
	}

	private static void migrate_7_8(Statement st) throws SQLException {
		st.executeUpdate("ALTER TABLE `action` ADD COLUMN `targetkind` TEXT DEFAULT 'user'");

		st.executeUpdate("ALTER TABLE `action` RENAME COLUMN `target_user_id` TO `target_id`");
		st.executeUpdate("DROP INDEX action_target_user_id");
		st.executeUpdate("CREATE INDEX `action_target_id` ON `action` (`target_id`)");

		st.executeUpdate("ALTER TABLE `activeaction` RENAME COLUMN `target_user_id` TO `target_id`");
		st.executeUpdate("DROP INDEX activeaction_target_user_id");
		st.executeUpdate("CREATE INDEX `activeaction_target_id` ON `activeaction` (`target_id`)");

		st.executeUpdate("CREATE TABLE `actiondata` (`action_id` INTEGER PRIMARY KEY, `data` INTEGER, `resetdata` INTEGER)");

		Map<Integer, Integer> idMap = new HashMap<>();

		try (ResultSet res = st.executeQuery("SELECT id, type, channel_id, data, resetdata, actor_user_id, creation, expiration, reason FROM channelaction");
				PreparedStatement ps = st.getConnection().prepareStatement("INSERT INTO action (targetkind, type, target_id, actor_user_id, creation, expiration, reason, prev_id) VALUES (?, ?, ?, ?, ?, ?, ?, '-1')", Statement.RETURN_GENERATED_KEYS);
				PreparedStatement psData = st.getConnection().prepareStatement("INSERT INTO actiondata (action_id, data, resetdata) VALUES (?, ?, ?)")) {
			while (res.next()) {
				ps.setString(1, ActionTargetKind.CHANNEL.id); // targetKind
				ps.setString(2, res.getString(2)); // type
				ps.setLong(3, res.getLong(3)); // targetId
				ps.setInt(4, res.getInt(6)); // actorUserId
				ps.setLong(5, res.getLong(7)); // creation
				ps.setLong(6, res.getLong(8)); // expiration
				ps.setString(7, res.getString(9)); // reason

				ps.executeUpdate();

				int newId;

				try (ResultSet res2 = ps.getGeneratedKeys()) {
					if (!res2.next()) throw new IllegalStateException();
					newId = res2.getInt(1);
				}

				idMap.put(res.getInt(1), newId);

				Integer data = res.getInt(4);
				if (res.wasNull()) data = null;

				Integer resetData = res.getInt(5);
				if (res.wasNull()) resetData = null;

				if (data != null || resetData != null) {
					psData.setInt(1, newId);

					if (data != null) {
						psData.setInt(2, data);
					} else {
						psData.setNull(2, Types.INTEGER);
					}

					if (resetData != null) {
						psData.setInt(3, resetData);
					} else {
						psData.setNull(3, Types.INTEGER);
					}

					psData.executeUpdate();
				}
			}
		}

		try (ResultSet res = st.executeQuery("SELECT id, prev_id FROM channelaction WHERE prev_id != '-1'");
				PreparedStatement ps = st.getConnection().prepareStatement("UPDATE action SET prev_id = ? WHERE id = ?")) {
			while (res.next()) {
				Integer newId = idMap.get(res.getInt(1));
				Integer newPrevId = idMap.get(res.getInt(2));

				if (newId != null && newPrevId != null) {
					ps.setInt(1, newPrevId);
					ps.setInt(2, newId);
				}

				ps.addBatch();
			}

			ps.executeUpdate();
		}

		try (ResultSet res = st.executeQuery("SELECT channelaction_id, suspender_user_id, time, reason FROM channelactionsuspension");
				PreparedStatement ps = st.getConnection().prepareStatement("INSERT INTO actionsuspension (action_id, suspender_user_id, time, reason) VALUES (?, ?, ?, ?)")) {
			while (res.next()) {
				Integer newId = idMap.get(res.getInt(1));
				if (newId == null) continue;

				ps.setInt(1, newId); // actionId
				ps.setInt(2, res.getInt(2)); // suspenderUserId
				ps.setLong(3, res.getLong(3)); // time
				ps.setString(4, res.getString(4)); // reason

				ps.executeUpdate();
			}
		}

		try (ResultSet res = st.executeQuery("SELECT channelaction_id, time FROM channelactionexpiration");
				PreparedStatement ps = st.getConnection().prepareStatement("INSERT INTO actionexpiration (action_id, time) VALUES (?, ?)")) {
			while (res.next()) {
				Integer newId = idMap.get(res.getInt(1));
				if (newId == null) continue;

				ps.setInt(1, newId); // actionId
				ps.setLong(2, res.getLong(2)); // time

				ps.executeUpdate();
			}
		}

		try (ResultSet res = st.executeQuery("SELECT channelaction_id, channel_id FROM activechannelaction");
				PreparedStatement ps = st.getConnection().prepareStatement("INSERT INTO activeaction (action_id, target_id) VALUES (?, ?)")) {
			while (res.next()) {
				Integer newId = idMap.get(res.getInt(1));
				if (newId == null) continue;

				ps.setInt(1, newId); // actionId
				ps.setInt(2, res.getInt(2)); // targetId

				ps.executeUpdate();
			}
		}

		st.executeUpdate("DROP TABLE `channelaction`");
		st.executeUpdate("DROP TABLE `channelactionsuspension`");
		st.executeUpdate("DROP TABLE `channelactionexpiration`");
		st.executeUpdate("DROP TABLE `activechannelaction`");
	}

	private static void migrate_8_9(Statement st) throws SQLException {
		st.executeUpdate("CREATE TABLE `userconfig` (`user_id` INTEGER, `key` TEXT, `value` TEXT, UNIQUE(`user_id`, `key`))");
		st.executeUpdate("CREATE INDEX `userconfig_user_id` ON `userconfig` (`user_id`)");
	}
}
