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
import net.fabricmc.discord.bot.database.IdArmor;

public final class UserConfigQueries {
	public static Map<String, String> getAll(Database db, int userId) throws SQLException {
		if (db == null) throw new NullPointerException("null db");

		int rawUserId = IdArmor.decodeOrThrow(userId, "user id");

		try (Connection conn = db.getConnection();
				PreparedStatement ps = conn.prepareStatement("SELECT `key`, `value` FROM `userconfig` WHERE `user_id` = ?")) {
			ps.setInt(1, rawUserId);

			try (ResultSet res = ps.executeQuery()) {
				Map<String, String> ret = new HashMap<>();

				while (res.next()) {
					ret.put(res.getString(1), res.getString(2));
				}

				return ret;
			}
		}
	}

	public static String get(Database db, int userId, String key) throws SQLException {
		if (db == null) throw new NullPointerException("null db");
		if (key == null) throw new NullPointerException("null key");

		int rawUserId = IdArmor.decodeOrThrow(userId, "user id");

		try (Connection conn = db.getConnection();
				PreparedStatement ps = conn.prepareStatement("SELECT `value` FROM `userconfig` WHERE `user_id` = ? AND `key` = ?")) {
			ps.setInt(1, rawUserId);
			ps.setString(2, key);

			try (ResultSet res = ps.executeQuery()) {
				if (!res.next()) return null;

				return res.getString(1);
			}
		}
	}

	public static boolean set(Database db, int userId, String key, String value) throws SQLException {
		if (db == null) throw new NullPointerException("null db");
		if (key == null) throw new NullPointerException("null key");
		if (value == null) throw new NullPointerException("null value");

		int rawUserId = IdArmor.decodeOrThrow(userId, "user id");

		try (Connection conn = db.getConnection();
				PreparedStatement ps = conn.prepareStatement("REPLACE INTO `userconfig` (`user_id`, `key`, `value`) VALUES (?, ?, ?)")) {
			ps.setInt(1, rawUserId);
			ps.setString(2, key);
			ps.setString(3, value);

			return ps.executeUpdate() > 0;
		}
	}

	public static boolean remove(Database db, int userId, String key) throws SQLException {
		if (db == null) throw new NullPointerException("null db");
		if (key == null) throw new NullPointerException("null key");

		int rawUserId = IdArmor.decodeOrThrow(userId, "user id");

		try (Connection conn = db.getConnection();
				PreparedStatement ps = conn.prepareStatement("DELETE FROM `userconfig` WHERE `user_id` = ? AND `key` = ?")) {
			ps.setInt(1, rawUserId);
			ps.setString(2, key);

			return ps.executeUpdate() > 0;
		}
	}

	public static boolean removeAll(Database db, int userId) throws SQLException {
		if (db == null) throw new NullPointerException("null db");

		int rawUserId = IdArmor.decodeOrThrow(userId, "user id");

		try (Connection conn = db.getConnection();
				PreparedStatement ps = conn.prepareStatement("DELETE FROM `userconfig` WHERE `user_id` = ?")) {
			ps.setInt(1, rawUserId);

			return ps.executeUpdate() > 0;
		}
	}
}
