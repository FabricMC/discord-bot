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
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

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
}
