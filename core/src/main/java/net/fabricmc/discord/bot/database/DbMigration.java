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
			int count;

			try (ResultSet res = st.executeQuery("SELECT COUNT(*) FROM `sqlite_schema` WHERE `type` = 'table' AND `name` = 'config'")) {
				if (!res.next()) throw new IllegalStateException();
				count = res.getInt(1);
			}

			int version;

			if (count == 0) {
				st.executeUpdate("CREATE TABLE `config` (`key` TEXT, `value` TEXT, PRIMARY KEY (`key`))");
				st.executeUpdate("INSERT INTO `config` VALUES ('dbVersion', '1')");
				version = 1;
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

			switch (version) {

			}
		} catch (SQLException | NumberFormatException e) {
			throw new RuntimeException("Error initializing/migrating DB", e);
		}
	}
}
