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
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import net.fabricmc.discord.bot.database.Database;
import net.fabricmc.discord.bot.database.IdArmor;
import net.fabricmc.discord.bot.filter.FilterAction;
import net.fabricmc.discord.bot.filter.FilterType;

public final class FilterQueries {
	public static Collection<FilterEntry> getFilters(Database db) throws SQLException {
		if (db == null) throw new NullPointerException("null db");

		try (Connection conn = db.getConnection();
				PreparedStatement ps = conn.prepareStatement("SELECT id, type, pattern, filtergroup_id FROM `filter`")) {
			try (ResultSet res = ps.executeQuery()) {
				List<FilterEntry> ret = new ArrayList<>();

				while (res.next()) {
					ret.add(new FilterEntry(IdArmor.encode(res.getInt(1)), FilterType.get(res.getString(2)), res.getString(3), IdArmor.encode(res.getInt(4))));
				}

				return ret;
			}
		}
	}

	public static FilterData handleFilterHit(Database db, FilterEntry filter) throws SQLException {
		if (db == null) throw new NullPointerException("null db");
		if (filter == null) throw new NullPointerException("null filter");

		int rawFilterId = IdArmor.decodeOrThrow(filter.id(), "filter id");
		int rawGroupId = IdArmor.decodeOrThrow(filter.groupId(), "group id");

		try (Connection conn = db.getConnection();
				PreparedStatement psIncHits = conn.prepareStatement("UPDATE `filter` SET hits = hits + 1 WHERE `id` = ?");
				PreparedStatement psGetData = conn.prepareStatement("SELECT g.name, a.action, a.actiondata FROM filtergroup g, filteraction a WHERE g.id = ? AND a.id = g.filteraction_id")) {
			conn.setAutoCommit(false);

			psIncHits.setInt(1, rawFilterId);
			if (psIncHits.executeUpdate() == 0) return null;

			psGetData.setInt(1, rawGroupId);

			try (ResultSet res = psGetData.executeQuery()) {
				if (!res.next()) return null;

				FilterData ret = new FilterData(res.getString(1), // groupName
						FilterAction.get(res.getString(2)), // action
						res.getString(3)); // actionData

				conn.commit();

				return ret;
			}
		}
	}

	public record FilterEntry(int id, FilterType type, String pattern, int groupId) { }
	public record FilterData(String groupName, FilterAction action, String actionData) { }

	public static FilterEntry getFilter(Database db, int id) throws SQLException {
		if (db == null) throw new NullPointerException("null db");

		int rawId = IdArmor.decodeOrThrow(id, "filter id");

		try (Connection conn = db.getConnection();
				PreparedStatement ps = conn.prepareStatement("SELECT type, pattern, filtergroup_id FROM `filter` WHERE id = ?")) {
			ps.setInt(1, rawId);

			try (ResultSet res = ps.executeQuery()) {
				if (!res.next()) return null;

				return new FilterEntry(id, FilterType.get(res.getString(1)), res.getString(2), IdArmor.encode(res.getInt(3)));
			}
		}
	}

	public static Collection<FilterEntry> getFilters(Database db, String group) throws SQLException {
		if (db == null) throw new NullPointerException("null db");
		if (group == null) throw new NullPointerException("null group");

		try (Connection conn = db.getConnection();
				PreparedStatement ps = conn.prepareStatement("SELECT f.id, f.type, f.pattern, f.filtergroup_id FROM `filtergroup` g, `filter` f WHERE g.name = ? AND f.filtergroup_id = g.id")) {
			ps.setString(1, group);

			try (ResultSet res = ps.executeQuery()) {
				List<FilterEntry> ret = new ArrayList<>();

				while (res.next()) {
					ret.add(new FilterEntry(IdArmor.encode(res.getInt(1)), FilterType.get(res.getString(2)), res.getString(3), IdArmor.encode(res.getInt(4))));
				}

				return ret;
			}
		}
	}

	public static boolean addFilter(Database db, FilterType type, String pattern, String group) throws SQLException {
		if (db == null) throw new NullPointerException("null db");
		if (type == null) throw new NullPointerException("null type");
		if (pattern == null) throw new NullPointerException("null pattern");
		if (group == null) throw new NullPointerException("null group");

		try (Connection conn = db.getConnection();
				PreparedStatement psGetGroupId = conn.prepareStatement("SELECT id FROM `filtergroup` WHERE name = ?");
				PreparedStatement psAdd = conn.prepareStatement("INSERT INTO `filter` (type, pattern, filtergroup_id) VALUES (?, ?, ?)")) {
			psGetGroupId.setString(1, group);
			int rawGroupId;

			try (ResultSet res = psGetGroupId.executeQuery()) {
				if (!res.next()) return false;

				rawGroupId = res.getInt(1);
			}

			psAdd.setString(1, type.id);
			psAdd.setString(2, pattern);
			psAdd.setInt(3, rawGroupId);

			return psAdd.executeUpdate() > 0;
		}
	}

	public static boolean removeFilter(Database db, int id) throws SQLException {
		if (db == null) throw new NullPointerException("null db");

		int rawId = IdArmor.decodeOrThrow(id, "filter id");

		try (Connection conn = db.getConnection();
				PreparedStatement ps = conn.prepareStatement("DELETE FROM `filter` WHERE id = ?")) {
			ps.setInt(1, rawId);

			return ps.executeUpdate() > 0;
		}
	}

	public static boolean setFilterPattern(Database db, int id, String pattern) throws SQLException {
		if (db == null) throw new NullPointerException("null db");

		int rawId = IdArmor.decodeOrThrow(id, "filter id");

		try (Connection conn = db.getConnection();
				PreparedStatement ps = conn.prepareStatement("UPDATE `filter` SET pattern = ? WHERE id = ?")) {
			ps.setString(1, pattern);
			ps.setInt(2, rawId);

			return ps.executeUpdate() > 0;
		}
	}

	public static boolean setFilterGroup(Database db, int id, String group) throws SQLException {
		if (db == null) throw new NullPointerException("null db");

		int rawId = IdArmor.decodeOrThrow(id, "filter id");

		try (Connection conn = db.getConnection();
				PreparedStatement psGetGroupId = conn.prepareStatement("SELECT id FROM `filtergroup` WHERE name = ?");
				PreparedStatement psUpdate = conn.prepareStatement("UPDATE `filter` SET filtergroup_id = ? WHERE id = ?")) {
			psGetGroupId.setString(1, group);
			int rawGroupId;

			try (ResultSet res = psGetGroupId.executeQuery()) {
				if (!res.next()) return false;

				rawGroupId = res.getInt(1);
			}

			psUpdate.setInt(1, rawGroupId);
			psUpdate.setInt(2, rawId);

			return psUpdate.executeUpdate() > 0;
		}
	}

	public static Collection<FilterGroupEntry> getGroups(Database db) throws SQLException {
		if (db == null) throw new NullPointerException("null db");

		try (Connection conn = db.getConnection();
				PreparedStatement ps = conn.prepareStatement("SELECT g.id, g.name, g.description, a.name FROM `filtergroup` g, `filteraction` a WHERE a.id = g.filteraction_id")) {
			try (ResultSet res = ps.executeQuery()) {
				List<FilterGroupEntry> ret = new ArrayList<>();

				while (res.next()) {
					ret.add(new FilterGroupEntry(IdArmor.encode(res.getInt(1)), res.getString(2), res.getString(3), res.getString(4)));
				}

				return ret;
			}
		}
	}

	public static boolean addGroup(Database db, String name, String description, String action) throws SQLException {
		if (db == null) throw new NullPointerException("null db");
		if (name == null) throw new NullPointerException("null name");
		if (description == null) throw new NullPointerException("null description");
		if (action == null) throw new NullPointerException("null action");

		try (Connection conn = db.getConnection();
				PreparedStatement psGetActionId = conn.prepareStatement("SELECT id FROM `filteraction` WHERE name = ?");
				PreparedStatement psAdd = conn.prepareStatement("INSERT OR IGNORE INTO `filtergroup` (name, description, filteraction_id) VALUES (?, ?, ?)")) {
			psGetActionId.setString(1, action);
			int rawActionId;

			try (ResultSet res = psGetActionId.executeQuery()) {
				if (!res.next()) return false;

				rawActionId = res.getInt(1);
			}

			psAdd.setString(1, name);
			psAdd.setString(2, description);
			psAdd.setInt(3, rawActionId);

			return psAdd.executeUpdate() > 0;
		}
	}

	public static boolean removeGroup(Database db, String group) throws SQLException {
		if (db == null) throw new NullPointerException("null db");
		if (group == null) throw new NullPointerException("null group");

		try (Connection conn = db.getConnection();
				PreparedStatement psGetGroupId = conn.prepareStatement("SELECT id FROM `filtergroup` WHERE name = ?");
				PreparedStatement psFilter = conn.prepareStatement("DELETE FROM `filter` WHERE filtergroup_id = ?");
				PreparedStatement psGroup = conn.prepareStatement("DELETE FROM `filtergroup` WHERE id = ?")) {
			conn.setAutoCommit(false);

			psGetGroupId.setString(1, group);
			int rawGroupId;

			try (ResultSet res = psGetGroupId.executeQuery()) {
				if (!res.next()) return false;

				rawGroupId = res.getInt(1);
			}

			psFilter.setInt(1, rawGroupId);
			psFilter.executeUpdate();

			psGroup.setInt(1, rawGroupId);
			boolean ret = psGroup.executeUpdate() > 0;

			conn.commit();

			return ret;
		}
	}

	public static boolean setGroupAction(Database db, String group, String action) throws SQLException {
		if (db == null) throw new NullPointerException("null db");
		if (group == null) throw new NullPointerException("null group");
		if (action == null) throw new NullPointerException("null action");

		try (Connection conn = db.getConnection();
				PreparedStatement psGetActionId = conn.prepareStatement("SELECT id FROM `filteraction` WHERE name = ?");
				PreparedStatement ps = conn.prepareStatement("UPDATE `filtergroup` SET action = ? WHERE name = ?")) {
			psGetActionId.setString(1, action);
			int rawActionId;

			try (ResultSet res = psGetActionId.executeQuery()) {
				if (!res.next()) return false;

				rawActionId = res.getInt(1);
			}

			ps.setInt(1, rawActionId);
			ps.setString(2, group);

			return ps.executeUpdate() > 0;
		}
	}

	public static boolean setGroupDescription(Database db, String group, String description) throws SQLException {
		if (db == null) throw new NullPointerException("null db");
		if (group == null) throw new NullPointerException("null group");
		if (description == null) throw new NullPointerException("null description");

		try (Connection conn = db.getConnection();
				PreparedStatement ps = conn.prepareStatement("UPDATE `filtergroup` SET description = ? WHERE name = ?")) {
			ps.setString(1, description);
			ps.setString(2, group);

			return ps.executeUpdate() > 0;
		}
	}

	public record FilterGroupEntry(int id, String name, String description, String action) { }

	public static Collection<FilterActionEntry> getActions(Database db) throws SQLException {
		if (db == null) throw new NullPointerException("null db");

		try (Connection conn = db.getConnection();
				PreparedStatement ps = conn.prepareStatement("SELECT id, name, description, action, actiondata FROM `filteraction`")) {
			try (ResultSet res = ps.executeQuery()) {
				List<FilterActionEntry> ret = new ArrayList<>();

				while (res.next()) {
					ret.add(new FilterActionEntry(IdArmor.encode(res.getInt(1)), res.getString(2), res.getString(3), FilterAction.get(res.getString(4)), res.getString(5)));
				}

				return ret;
			}
		}
	}

	public static boolean addAction(Database db, String name, String description, FilterAction action, String actionData) throws SQLException {
		if (db == null) throw new NullPointerException("null db");
		if (name == null) throw new NullPointerException("null name");
		if (description == null) throw new NullPointerException("null description");
		if (action == null) throw new NullPointerException("null action");

		try (Connection conn = db.getConnection();
				PreparedStatement ps = conn.prepareStatement("INSERT OR IGNORE INTO `filteraction` (name, description, action, actiondata) VALUES (?, ?, ?, ?)")) {
			ps.setString(1, name);
			ps.setString(2, description);
			ps.setString(3, action.id);
			ps.setString(4, actionData);

			return ps.executeUpdate() > 0;
		}
	}

	public static boolean removeAction(Database db, String name) throws SQLException {
		if (db == null) throw new NullPointerException("null db");
		if (name == null) throw new NullPointerException("null name");

		try (Connection conn = db.getConnection();
				PreparedStatement psGetActionId = conn.prepareStatement("SELECT id FROM `filteraction` WHERE name = ?");
				PreparedStatement psGetGroupId = conn.prepareStatement("SELECT id FROM `filtergroup` WHERE filteraction_id = ?");
				PreparedStatement psFilter = conn.prepareStatement("DELETE FROM `filter` WHERE filtergroup_id = ?");
				PreparedStatement psGroup = conn.prepareStatement("DELETE FROM `filtergroup` WHERE filteraction_id = ?");
				PreparedStatement psAction = conn.prepareStatement("DELETE FROM `filteraction` WHERE id = ?")) {
			conn.setAutoCommit(false);

			psGetActionId.setString(1, name);
			int rawActionId;

			try (ResultSet res = psGetActionId.executeQuery()) {
				if (!res.next()) return false;

				rawActionId = res.getInt(1);
			}

			psGetGroupId.setInt(1, rawActionId);

			try (ResultSet res = psGetGroupId.executeQuery()) {
				while (res.next()) {
					psFilter.setInt(1, res.getInt(1));
					psFilter.addBatch();
				}
			}

			psFilter.executeBatch();

			psGroup.setInt(1, rawActionId);
			psGroup.executeUpdate();

			psAction.setInt(1, rawActionId);
			boolean ret = psAction.executeUpdate() > 0;

			conn.commit();

			return ret;
		}
	}

	public static boolean setActionAction(Database db, String name, FilterAction action, String actionData) throws SQLException {
		if (db == null) throw new NullPointerException("null db");
		if (name == null) throw new NullPointerException("null name");
		if (action == null) throw new NullPointerException("null action");

		try (Connection conn = db.getConnection();
				PreparedStatement ps = conn.prepareStatement("UPDATE `filteraction` SET action = ?, actiondata = ? WHERE name = ?")) {
			ps.setString(1, action.id);
			ps.setString(2, actionData);
			ps.setString(3, name);

			return ps.executeUpdate() > 0;
		}
	}

	public static boolean setActionDescription(Database db, String name, String description) throws SQLException {
		if (db == null) throw new NullPointerException("null db");
		if (name == null) throw new NullPointerException("null name");
		if (description == null) throw new NullPointerException("null description");

		try (Connection conn = db.getConnection();
				PreparedStatement ps = conn.prepareStatement("UPDATE `filteraction` SET description = ? WHERE name = ?")) {
			ps.setString(1, description);
			ps.setString(2, name);

			return ps.executeUpdate() > 0;
		}
	}

	public record FilterActionEntry(int id, String name, String description, FilterAction action, String actionData) { }
}
