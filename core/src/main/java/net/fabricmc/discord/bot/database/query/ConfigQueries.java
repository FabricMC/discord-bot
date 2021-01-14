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
import java.util.HashMap;
import java.util.Map;

import net.fabricmc.discord.bot.database.Database;

public final class ConfigQueries {
	public static Map<String, String> getAll(Database db) throws SQLException {
		if (db == null) throw new NullPointerException("null db");

		try (Connection conn = db.getConnection();
				PreparedStatement ps = conn.prepareStatement("""
						SELECT `key`, `value` FROM `config`
						""")) {
			try (ResultSet res = ps.executeQuery()) {
				Map<String, String> ret = new HashMap<>();

				while (res.next()) {
					ret.put(res.getString(1), res.getString(2));
				}

				return ret;
			}
		}
	}

	public static String get(Database db, String key) throws SQLException {
		if (db == null) throw new NullPointerException("null db");
		if (key == null) throw new NullPointerException("null key");

		try (Connection conn = db.getConnection();
				PreparedStatement ps = conn.prepareStatement("""
						SELECT `value` FROM `config` WHERE `key` = ?
						""")) {
			ps.setString(1, key);

			try (ResultSet res = ps.executeQuery()) {
				if (!res.next()) return null;

				return res.getString(1);
			}
		}
	}

	public static void set(Database db, String key, String value) throws SQLException {
		if (db == null) throw new NullPointerException("null db");
		if (key == null) throw new NullPointerException("null key");
		if (value == null) throw new NullPointerException("null value");

		try (Connection conn = db.getConnection();
				PreparedStatement ps = conn.prepareStatement("""
						REPLACE INTO `config` (`key`, `value`) VALUES (?, ?)
						""")) {
			ps.setString(1, key);
			ps.setString(2, value);

			ps.executeUpdate();
		}
	}

	public static boolean remove(Database db, String key) throws SQLException {
		if (db == null) throw new NullPointerException("null db");
		if (key == null) throw new NullPointerException("null key");

		try (Connection conn = db.getConnection();
				PreparedStatement ps = conn.prepareStatement("""
						DELETE FROM `config` WHERE `key` = ?
						""")) {
			ps.setString(1, key);

			return ps.executeUpdate() > 0;
		}
	}
}
