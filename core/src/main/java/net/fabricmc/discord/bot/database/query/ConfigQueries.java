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
