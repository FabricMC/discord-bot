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
import java.util.Collections;
import java.util.List;

import net.fabricmc.discord.bot.command.mod.ActionType;
import net.fabricmc.discord.bot.database.Database;
import net.fabricmc.discord.bot.database.IdArmor;

public final class ActionQueries {
	public static ActionEntry getAction(Database db, int actionId) throws SQLException {
		if (db == null) throw new NullPointerException("null db");

		int rawActionId = IdArmor.decodeOrThrow(actionId, "action id");

		try (Connection conn = db.getConnection();
				PreparedStatement ps = conn.prepareStatement("SELECT "
						+ "a.targetkind, a.type, d.data, d.resetdata, a.target_id, a.actor_user_id, a.creation, a.expiration, a.reason, a.prev_id, s.suspender_user_id, s.time, s.reason "
						+ "FROM `action` a "
						+ "LEFT JOIN `actiondata` d ON d.action_id = a.id "
						+ "LEFT JOIN `actionsuspension` s ON s.action_id = a.id "
						+ "WHERE a.id = ?")) {
			ps.setInt(1, rawActionId);

			try (ResultSet res = ps.executeQuery()) {
				if (!res.next()) return null;

				ActionType type = ActionType.get(res.getString(1), res.getString(2));

				int dataVal = res.getInt(3);
				ActionData data = res.wasNull() ? null : new ActionData(dataVal, res.getInt(4));

				int rawSuspenderUserId = res.getInt(11);
				if (res.wasNull()) rawSuspenderUserId = -1;

				long suspendTime = res.getLong(12);
				if (res.wasNull()) suspendTime = -1;

				return new ActionEntry(actionId, // id
						type, // type
						data, // data
						IdArmor.encodeCond(res.getLong(5), type.getKind().useEncodedTargetId), // targetId
						IdArmor.encode(res.getInt(6)), // actorUserId
						res.getLong(7), // creationTime
						res.getLong(8), // expirationTime
						res.getString(9), // reason
						IdArmor.encodeOptional(res.getInt(10)), // prevId
						IdArmor.encodeOptional(rawSuspenderUserId), // suspenderUserId
						suspendTime, // suspensionTime
						res.getString(13)); // suspendReason
			}
		}
	}

	public static Collection<ActionEntry> getActions(Database db, ActionType.Kind kind, long targetId) throws SQLException {
		if (db == null) throw new NullPointerException("null db");

		long rawTargetId = IdArmor.decodeOrThrowCond(targetId, kind.useEncodedTargetId, "target id");

		try (Connection conn = db.getConnection();
				PreparedStatement ps = conn.prepareStatement("SELECT a.id, a.targetkind, a.type, d.data, d.resetdata, a.actor_user_id, a.creation, a.expiration, a.reason, a.prev_id, s.suspender_user_id, s.time, s.reason "
						+ "FROM `action` a "
						+ "LEFT JOIN `actiondata` d ON d.action_id = a.id "
						+ "LEFT JOIN `actionsuspension` s ON s.action_id = a.id "
						+ "WHERE a.target_id = ? AND a.targetkind = ?")) {
			ps.setLong(1, rawTargetId);
			ps.setString(2, kind.id);

			try (ResultSet res = ps.executeQuery()) {
				List<ActionEntry> ret = new ArrayList<>();

				while (res.next()) {
					ActionType type = ActionType.get(res.getString(2), res.getString(3));

					int dataVal = res.getInt(4);
					ActionData data = res.wasNull() ? null : new ActionData(dataVal, res.getInt(5));

					int rawSuspenderUserId = res.getInt(11);
					if (res.wasNull()) rawSuspenderUserId = -1;

					long suspendTime = res.getLong(12);
					if (res.wasNull()) suspendTime = -1;

					ret.add(new ActionEntry(IdArmor.encode(res.getInt(1)), // id
							type, // type
							data, // data
							targetId, // targetId
							IdArmor.encode(res.getInt(6)), // actorUserId
							res.getLong(7), // creationTime
							res.getLong(8), // expirationTime
							res.getString(9), // reason
							IdArmor.encodeOptional(res.getInt(10)), // prevId
							IdArmor.encodeOptional(rawSuspenderUserId), // suspenderUserId
							suspendTime, // suspensionTime
							res.getString(13))); // suspendReason
				}

				return ret;
			}
		}
	}

	public static ActionEntry createAction(Database db, ActionType type, ActionData data,
			long targetId, int actorUserId, long durationMs, long creationTime, long expirationTime, String reason,
			int prevId) throws SQLException {
		if (db == null) throw new NullPointerException("null db");
		if (type == null) throw new NullPointerException("null type");
		if (targetId < 0) throw new IllegalArgumentException("invalid target id");
		if (actorUserId < 0) throw new IllegalArgumentException("invalid actor userid");
		if (durationMs == 0 && type.hasDuration()) throw new IllegalArgumentException("invalid zero duration");

		long rawTargetId = IdArmor.decodeOrThrowCond(targetId, type.getKind().useEncodedTargetId, "target id");
		int rawActorUserId = IdArmor.decodeOrThrow(actorUserId, "actor user id");
		int rawPrevId = IdArmor.decodeOptionalOrThrow(prevId, "prev id");

		try (Connection conn = db.getConnection();
				PreparedStatement ps = conn.prepareStatement("INSERT INTO `action` (targetkind, type, target_id, actor_user_id, creation, expiration, reason, prev_id) VALUES (?, ?, ?, ?, ?, ?, ?, ?)", Statement.RETURN_GENERATED_KEYS);
				PreparedStatement psExpire = conn.prepareStatement("INSERT INTO `actionexpiration` (action_id, time) VALUES (?, ?)");
				PreparedStatement psActive = conn.prepareStatement("INSERT INTO `activeaction` (action_id, target_id) VALUES (?, ?)")) {
			conn.setAutoCommit(false);

			ps.setString(1, type.getKind().id);
			ps.setString(2, type.getId());
			ps.setLong(3, rawTargetId);
			ps.setInt(4, rawActorUserId);
			ps.setLong(5, creationTime);
			ps.setLong(6, expirationTime);
			ps.setString(7, reason);
			ps.setInt(8, rawPrevId);
			ps.executeUpdate();

			int rawActionId;

			try (ResultSet res = ps.getGeneratedKeys()) {
				if (!res.next()) throw new IllegalStateException();
				rawActionId = res.getInt(1);
			}

			if (data != null) {
				try (PreparedStatement psData = conn.prepareStatement("INSERT INTO `actiondata` (action_id, data, resetdata) VALUES (?, ?, ?)")) {
					psData.setInt(1, rawActionId);
					psData.setInt(2, data.data);
					psData.setInt(3, data.resetData);
					psData.executeUpdate();
				}
			}

			if (type.hasDuration()) {
				if (expirationTime > 0) { // not permanent
					psExpire.setInt(1, rawActionId);
					psExpire.setLong(2, expirationTime);
					psExpire.executeUpdate();
				}

				psActive.setInt(1, rawActionId);
				psActive.setLong(2, rawTargetId);
				psActive.executeUpdate();
			}

			conn.commit();

			return new ActionEntry(IdArmor.encode(rawActionId), type, data, targetId, actorUserId,
					creationTime, expirationTime,
					reason, IdArmor.encodeOptional(prevId),
					-1, -1, null);
		}
	}

	public static boolean deleteAction(Database db, int actionId) throws SQLException {
		if (db == null) throw new NullPointerException("null db");

		int rawActionId = IdArmor.decodeOrThrow(actionId, "action id");

		try (Connection conn = db.getConnection();
				PreparedStatement psAction = conn.prepareStatement("DELETE FROM `action` WHERE action_id = ?");
				PreparedStatement psData = conn.prepareStatement("DELETE FROM `actiondata` WHERE action_id = ?");
				PreparedStatement psExpire = conn.prepareStatement("DELETE FROM `actionexpiration` WHERE action_id = ?");
				PreparedStatement psActive = conn.prepareStatement("DELETE FROM `activeaction` WHERE action_id = ?")) {
			conn.setAutoCommit(false);

			psAction.setInt(1, rawActionId);
			if (psAction.executeUpdate() == 0) return false;

			psData.setInt(1, rawActionId);
			psData.executeUpdate();

			psExpire.setInt(1, rawActionId);
			psExpire.executeUpdate();

			psActive.setInt(1, rawActionId);
			psActive.executeUpdate();

			conn.commit();

			return true;
		}
	}

	public record ActionEntry(int id, ActionType type, ActionData data, long targetId, int actorUserId,
			long creationTime, long expirationTime,
			String reason, int prevId,
			int suspenderUserId, long suspensionTime, String suspendReason) { }

	public record ActionData(int data, int resetData) { }

	public static Collection<ExpiringActionEntry> getExpiringActions(Database db, long maxTime) throws SQLException {
		if (db == null) throw new NullPointerException("null db");

		try (Connection conn = db.getConnection();
				PreparedStatement ps = conn.prepareStatement("SELECT "
						+ "a.id, a.targetkind, a.type, d.data, d.resetdata, a.target_id, a.expiration "
						+ "FROM `actionexpiration` e "
						+ "JOIN `action` a ON a.id = e.action_id "
						+ "LEFT JOIN `actiondata` d ON d.action_id = e.action_id "
						+ "WHERE e.time < ?")) {
			ps.setLong(1, maxTime);

			try (ResultSet res = ps.executeQuery()) {
				List<ExpiringActionEntry> ret = new ArrayList<>();

				while (res.next()) {
					ActionType type = ActionType.get(res.getString(2), res.getString(3));

					int dataVal = res.getInt(4);
					ActionData data = res.wasNull() ? null : new ActionData(dataVal, res.getInt(5));

					ret.add(new ExpiringActionEntry(IdArmor.encode(res.getInt(1)), // id
							type, // type
							data, // data
							IdArmor.encodeCond(res.getLong(6), type.getKind().useEncodedTargetId), // targetId
							res.getLong(7))); // expiration
				}

				return ret;
			}
		}
	}

	public record ExpiringActionEntry(int id, ActionType type, ActionData data, long targetId, long expirationTime) { }

	public static boolean isExpiringAction(Database db, int actionId) throws SQLException {
		if (db == null) throw new NullPointerException("null db");

		int rawActionId = IdArmor.decodeOrThrow(actionId, "action id");

		try (Connection conn = db.getConnection();
				PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*) FROM `actionexpiration` WHERE action_id = ?")) {
			ps.setInt(1, rawActionId);

			try (ResultSet res = ps.executeQuery()) {
				if (!res.next()) throw new IllegalStateException();

				return res.getInt(1) > 0;
			}
		}
	}

	public static boolean expireAction(Database db, int actionId) throws SQLException {
		if (db == null) throw new NullPointerException("null db");

		int rawActionId = IdArmor.decodeOrThrow(actionId, "action id");

		try (Connection conn = db.getConnection();
				PreparedStatement psExpire = conn.prepareStatement("DELETE FROM `actionexpiration` WHERE action_id = ?");
				PreparedStatement psActive = conn.prepareStatement("DELETE FROM `activeaction` WHERE action_id = ?")) {
			conn.setAutoCommit(false);
			psExpire.setInt(1, rawActionId);

			boolean ret = psExpire.executeUpdate() > 0;

			psActive.setInt(1, rawActionId);
			psActive.executeUpdate();

			conn.commit();

			return ret;
		}
	}

	public static boolean suspendAction(Database db, int actionId, int suspenderUserId, String reason) throws SQLException {
		if (db == null) throw new NullPointerException("null db");

		int rawActionId = IdArmor.decodeOrThrow(actionId, "action id");
		int rawSuspenderUserId = IdArmor.decodeOrThrow(suspenderUserId, "suspender user id");

		long time = System.currentTimeMillis();

		try (Connection conn = db.getConnection();
				PreparedStatement psSuspend = conn.prepareStatement("INSERT OR IGNORE INTO `actionsuspension` (`action_id`, `suspender_user_id`, `time`, `reason`) VALUES (?, ?, ?, ?)");
				PreparedStatement psExpire = conn.prepareStatement("DELETE FROM `actionexpiration` WHERE action_id = ?");
				PreparedStatement psActive = conn.prepareStatement("DELETE FROM `activeaction` WHERE action_id = ?")) {
			conn.setAutoCommit(false);

			psSuspend.setInt(1, rawActionId);
			psSuspend.setInt(2, rawSuspenderUserId);
			psSuspend.setLong(3, time);
			psSuspend.setString(4, reason);
			if (psSuspend.executeUpdate() == 0) return false;

			psExpire.setInt(1, rawActionId);
			psExpire.executeUpdate();

			psActive.setInt(1, rawActionId);
			psActive.executeUpdate();

			conn.commit();

			return true;
		}
	}

	public static ActiveActionEntry getActiveAction(Database db, long targetId, ActionType type) throws SQLException {
		if (db == null) throw new NullPointerException("null db");

		long rawTargetId = IdArmor.decodeOrThrowCond(targetId, type.getKind().useEncodedTargetId, "target id");

		try (Connection conn = db.getConnection();
				PreparedStatement ps = conn.prepareStatement("SELECT a.id, d.data, d.resetdata, a.expiration, a.reason "
						+ "FROM `activeaction` aa "
						+ "JOIN `action` a ON a.id = aa.action_id "
						+ "LEFT JOIN `actiondata` d ON d.action_id = aa.action_id "
						+ "WHERE aa.target_id = ? AND a.targetkind = ? AND a.type = ?")) {
			ps.setLong(1, rawTargetId);
			ps.setString(2, type.getKind().id);
			ps.setString(3, type.getId());

			try (ResultSet res = ps.executeQuery()) {
				if (!res.next()) return null;

				int dataVal = res.getInt(2);
				ActionData data = res.wasNull() ? null : new ActionData(dataVal, res.getInt(3));

				return new ActiveActionEntry(IdArmor.encode(res.getInt(1)), // id
						type, // type
						data, // data
						targetId, // targetId
						res.getLong(4), // expirationTime
						res.getString(5)); // reason
			}
		}
	}

	public static Collection<ActiveActionEntry> getActiveActions(Database db, ActionType.Kind kind, long targetId) throws SQLException {
		if (db == null) throw new NullPointerException("null db");

		long rawTargetId = IdArmor.decodeOrThrowCond(targetId, kind.useEncodedTargetId, "target id");

		try (Connection conn = db.getConnection();
				PreparedStatement ps = conn.prepareStatement("SELECT a.id, a.type, d.data, d.resetdata, a.expiration, a.reason "
						+ "FROM `activeaction` aa "
						+ "JOIN `action` a ON a.id = aa.action_id "
						+ "LEFT JOIN `actiondata` d ON d.action_id = aa.action_id "
						+ "WHERE aa.target_id = ? AND a.targetkind = ?")) {
			ps.setLong(1, rawTargetId);
			ps.setString(2, kind.id);

			try (ResultSet res = ps.executeQuery()) {
				if (!res.next()) return Collections.emptyList();

				List<ActiveActionEntry> ret = new ArrayList<>();

				do {
					int dataVal = res.getInt(3);
					ActionData data = res.wasNull() ? null : new ActionData(dataVal, res.getInt(4));

					ret.add(new ActiveActionEntry(IdArmor.encode(res.getInt(1)), // id
							ActionType.get(kind.id, res.getString(2)), // type
							data, // data
							targetId, // targetId
							res.getLong(5), // expirationTime
							res.getString(6))); // reason
				} while (res.next());

				return ret;
			}
		}
	}

	public static Collection<ActiveActionEntry> getActiveDiscordUserActions(Database db, long discordUserId) throws SQLException {
		if (db == null) throw new NullPointerException("null db");

		try (Connection conn = db.getConnection();
				PreparedStatement ps = conn.prepareStatement("SELECT a.id, a.type, a.target_id, a.expiration, a.reason "
						+ "FROM `discorduser` du "
						+ "JOIN `activeaction` aa ON aa.target_id = du.user_id "
						+ "JOIN `action` a ON a.id = aa.action_id "
						+ "WHERE du.id = ? AND a.targetkind = '"+ActionType.Kind.USER.id+"'")) {
			ps.setLong(1, discordUserId);

			try (ResultSet res = ps.executeQuery()) {
				if (!res.next()) return Collections.emptyList();

				List<ActiveActionEntry> ret = new ArrayList<>();

				do {
					ret.add(new ActiveActionEntry(IdArmor.encode(res.getInt(1)), // id
							ActionType.get(ActionType.Kind.USER.id, res.getString(2)), // type
							null, // data
							IdArmor.encode(res.getInt(3)), // targetId
							res.getLong(4), // expirationTime
							res.getString(5))); // reason
				} while (res.next());

				return ret;
			}
		}
	}

	public static Collection<ActiveActionEntry> getActiveActions(Database db) throws SQLException {
		if (db == null) throw new NullPointerException("null db");

		try (Connection conn = db.getConnection();
				PreparedStatement ps = conn.prepareStatement("SELECT a.id, a.targetkind, a.type, d.data, d.resetdata, a.target_id, du.id, a.expiration, a.reason "
						+ "FROM `activeaction` aa "
						+ "JOIN `action` a ON a.id = aa.action_id "
						+ "LEFT JOIN `actiondata` d ON d.action_id = aa.action_id "
						+ "LEFT JOIN `discorduser` du ON du.user_id = a.target_id AND a.targetkind = '"+ActionType.Kind.USER.id+"'")) {
			List<ActiveActionEntry> ret = new ArrayList<>();

			try (ResultSet res = ps.executeQuery()) {
				while (res.next()) {
					ActionType type = ActionType.get(res.getString(2), res.getString(3));

					int dataVal = res.getInt(4);
					ActionData data = res.wasNull() ? null : new ActionData(dataVal, res.getInt(5));

					ret.add(new ActiveActionEntry(IdArmor.encode(res.getInt(1)), // id
							type, // type
							data, // data
							IdArmor.encodeCond(res.getLong(6), type.getKind().useEncodedTargetId), // targetId
							res.getLong(8), // expirationTime
							res.getString(9))); // reason
				}
			}

			return ret;
		}
	}

	public record ActiveActionEntry(int id, ActionType type, ActionData data, long targetId, long expirationTime, String reason) { }

	public static String getLockedNick(Database db, long discordUserId) throws SQLException {
		if (db == null) throw new NullPointerException("null db");

		try (Connection conn = db.getConnection();
				PreparedStatement ps = conn.prepareStatement("SELECT nick FROM `nicklock` WHERE discorduser_id = ?")) {
			ps.setLong(1, discordUserId);

			try (ResultSet res = ps.executeQuery()) {
				if (!res.next()) return null;

				return res.getString(1);
			}
		}
	}

	public static boolean addNickLock(Database db, long discordUserId, String nickName) throws SQLException {
		if (db == null) throw new NullPointerException("null db");
		if (nickName == null) throw new NullPointerException("null nickName");

		try (Connection conn = db.getConnection();
				PreparedStatement ps = conn.prepareStatement("INSERT OR IGNORE INTO `nicklock` (`discorduser_id`, `nick`) VALUES (?, ?)")) {
			ps.setLong(1, discordUserId);
			ps.setString(2, nickName);

			return ps.executeUpdate() > 0;
		}
	}

	public static boolean removeNickLock(Database db, long discordUserId) throws SQLException {
		if (db == null) throw new NullPointerException("null db");

		try (Connection conn = db.getConnection();
				PreparedStatement ps = conn.prepareStatement("DELETE FROM `nicklock` WHERE discorduser_id.id = ?")) {
			ps.setLong(1, discordUserId);

			return ps.executeUpdate() > 0;
		}
	}

	public static boolean updateLockedNick(Database db, long discordUserId, String nick) throws SQLException {
		if (db == null) throw new NullPointerException("null db");

		try (Connection conn = db.getConnection();
				PreparedStatement ps = conn.prepareStatement("INSERT OR REPLACE INTO `nicklock` (`discorduser_id`, `nick`) "
						+ "SELECT du.id, ? "
						+ "FROM `discorduser` du "
						+ "JOIN `activeaction` aa ON aa.target_id = du.user_id AND aa.targetkind = '"+ActionType.Kind.USER.id+"' "
						+ "WHERE du.id = ?")) {
			ps.setString(1, nick);
			ps.setLong(2, discordUserId);

			return ps.executeUpdate() > 0;
		}
	}
}
