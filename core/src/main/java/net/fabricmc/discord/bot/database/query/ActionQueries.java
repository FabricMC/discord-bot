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
				PreparedStatement ps = conn.prepareStatement("SELECT a.type, a.target_user_id, a.actor_user_id, a.creation, a.expiration, a.reason, a.prev_id, s.suspender_user_id, s.time, s.reason "
						+ "FROM `action` a "
						+ "LEFT JOIN `actionsuspension` s ON s.action_id = a.id "
						+ "WHERE a.id = ?")) {
			ps.setInt(1, rawActionId);

			try (ResultSet res = ps.executeQuery()) {
				if (!res.next()) return null;

				int rawSuspenderUserId = res.getInt(8);
				if (res.wasNull()) rawSuspenderUserId = -1;

				long suspendTime = res.getLong(9);
				if (res.wasNull()) suspendTime = -1;

				return new ActionEntry(actionId, // id
						ActionType.get(res.getString(1)), // type
						IdArmor.encode(res.getInt(2)), // targetUserId
						IdArmor.encode(res.getInt(3)), // actorUserId
						res.getLong(4), // creationTime
						res.getLong(5), // expirationTime
						res.getString(6), // reason
						IdArmor.encodeOptional(res.getInt(7)), // prevId
						IdArmor.encodeOptional(rawSuspenderUserId), // suspenderUserId
						suspendTime, // suspensionTime
						res.getString(10)); // suspendReason
			}
		}
	}

	public static Collection<ActionEntry> getActions(Database db, int userId) throws SQLException {
		if (db == null) throw new NullPointerException("null db");

		int rawUserId = IdArmor.decodeOrThrow(userId, "user id");

		try (Connection conn = db.getConnection();
				PreparedStatement ps = conn.prepareStatement("SELECT a.id, a.type, a.actor_user_id, a.creation, a.expiration, a.reason, a.prev_id, s.suspender_user_id, s.time, s.reason "
						+ "FROM `action` a "
						+ "LEFT JOIN `actionsuspension` s ON s.action_id = a.id "
						+ "WHERE a.target_user_id = ?")) {
			ps.setInt(1, rawUserId);

			try (ResultSet res = ps.executeQuery()) {
				List<ActionEntry> ret = new ArrayList<>();

				while (res.next()) {
					int rawSuspenderUserId = res.getInt(8);
					if (res.wasNull()) rawSuspenderUserId = -1;

					long suspendTime = res.getLong(9);
					if (res.wasNull()) suspendTime = -1;

					ret.add(new ActionEntry(IdArmor.encode(res.getInt(1)), // id
							ActionType.get(res.getString(2)), // type
							userId, // targetUserId
							IdArmor.encode(res.getInt(3)), // actorUserId
							res.getLong(4), // creationTime
							res.getLong(5), // expirationTime
							res.getString(6), // reason
							IdArmor.encodeOptional(res.getInt(7)), // prevId
							IdArmor.encodeOptional(rawSuspenderUserId), // suspenderUserId
							suspendTime, // suspensionTime
							res.getString(10))); // suspendReason
				}

				return ret;
			}
		}
	}

	public static ActionEntry createAction(Database db, ActionType type, int targetUserId, int actorUserId, long durationMs, String reason, int prevId) throws SQLException {
		if (db == null) throw new NullPointerException("null db");
		if (type == null) throw new NullPointerException("null type");
		if (targetUserId < 0) throw new IllegalArgumentException("invalid target userid");
		if (actorUserId < 0) throw new IllegalArgumentException("invalid actor userid");
		if (durationMs == 0 && type.hasDuration) throw new IllegalArgumentException("invalid zero duration");

		int rawTargetUserId = IdArmor.decodeOrThrow(targetUserId, "target user id");
		int rawActorUserId = IdArmor.decodeOrThrow(actorUserId, "actor user id");
		int rawPrevId = IdArmor.decodeOptionalOrThrow(prevId, "prev id");

		long creationTime = System.currentTimeMillis();
		long expirationTime;

		if (type.hasDuration) {
			expirationTime = durationMs > 0 ? creationTime + durationMs : -1;
		} else {
			expirationTime = 0;
		}

		try (Connection conn = db.getConnection();
				PreparedStatement ps = conn.prepareStatement("INSERT INTO `action` (type, target_user_id, actor_user_id, creation, expiration, reason, prev_id) VALUES (?, ?, ?, ?, ?, ?, ?)", Statement.RETURN_GENERATED_KEYS);
				PreparedStatement psExpire = conn.prepareStatement("INSERT INTO `actionexpiration` (action_id, time) VALUES (?, ?)");
				PreparedStatement psActive = conn.prepareStatement("INSERT INTO `activeaction` (action_id, target_user_id) VALUES (?, ?)")) {
			conn.setAutoCommit(false);

			ps.setString(1, type.id);
			ps.setInt(2, rawTargetUserId);
			ps.setInt(3, rawActorUserId);
			ps.setLong(4, creationTime);
			ps.setLong(5, expirationTime);
			ps.setString(6, reason);
			ps.setInt(7, rawPrevId);
			ps.executeUpdate();

			int rawActionId;

			try (ResultSet res = ps.getGeneratedKeys()) {
				if (!res.next()) throw new IllegalStateException();
				rawActionId = res.getInt(1);
			}

			if (type.hasDuration) {
				if (expirationTime > 0) { // not permanent
					psExpire.setInt(1, rawActionId);
					psExpire.setLong(2, expirationTime);
					psExpire.executeUpdate();
				}

				psActive.setInt(1, rawActionId);
				psActive.setInt(2, rawTargetUserId);
				psActive.executeUpdate();
			}

			conn.commit();

			return new ActionEntry(IdArmor.encode(rawActionId), type, targetUserId, actorUserId,
					creationTime, expirationTime,
					reason, IdArmor.encodeOptional(prevId),
					-1, -1, null);
		}
	}

	public record ActionEntry(int id, ActionType type, int targetUserId, int actorUserId,
			long creationTime, long expirationTime,
			String reason, int prevId,
			int suspenderUserId, long suspensionTime, String suspendReason) { }

	public static Collection<ExpiringActionEntry> getExpiringActions(Database db, long maxTime) throws SQLException {
		if (db == null) throw new NullPointerException("null db");

		try (Connection conn = db.getConnection();
				PreparedStatement ps = conn.prepareStatement("SELECT a.id, a.type, a.target_user_id, a.expiration FROM `actionexpiration` e, `action` a WHERE e.time < ? AND a.id = e.action_id")) {
			ps.setLong(1, maxTime);

			try (ResultSet res = ps.executeQuery()) {
				List<ExpiringActionEntry> ret = new ArrayList<>();

				while (res.next()) {
					ret.add(new ExpiringActionEntry(IdArmor.encode(res.getInt(1)), ActionType.get(res.getString(2)), IdArmor.encode(res.getInt(3)), res.getLong(4)));
				}

				return ret;
			}
		}
	}

	public record ExpiringActionEntry(int id, ActionType type, int targetUserId, long expirationTime) { }

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

	public static int getActiveAction(Database db, int userId, ActionType type) throws SQLException {
		if (db == null) throw new NullPointerException("null db");

		int rawUserId = IdArmor.decodeOrThrow(userId, "user id");

		try (Connection conn = db.getConnection();
				PreparedStatement ps = conn.prepareStatement("SELECT a.id FROM `activeaction` aa, `action` a WHERE aa.target_user_id = ? AND a.id = aa.action_id AND a.type = ?")) {
			ps.setInt(1, rawUserId);
			ps.setString(2, type.id);

			try (ResultSet res = ps.executeQuery()) {
				if (!res.next()) return -1;

				return IdArmor.encode(res.getInt(1));
			}
		}
	}

	public static Collection<ActiveActionEntry> getActiveActions(Database db, long discordUserId) throws SQLException {
		return getActiveActions(db, Collections.singletonList(discordUserId));
	}

	public static Collection<ActiveActionEntry> getActiveActions(Database db, Collection<Long> discordUserIds) throws SQLException {
		if (db == null) throw new NullPointerException("null db");

		try (Connection conn = db.getConnection();
				PreparedStatement ps = conn.prepareStatement("SELECT a.id, a.type, a.target_user_id, a.expiration, a.reason "
						+ "FROM `discorduser` du "
						+ "JOIN `activeaction` aa ON aa.target_user_id = du.user_id "
						+ "JOIN `action` a ON a.id = aa.action_id "
						+ "WHERE du.id = ?")) {
			List<ActiveActionEntry> ret = new ArrayList<>();

			for (long discordUserId : discordUserIds) {
				ps.setLong(1, discordUserId);

				try (ResultSet res = ps.executeQuery()) {
					while (res.next()) {
						ret.add(new ActiveActionEntry(IdArmor.encode(res.getInt(1)),
								ActionType.get(res.getString(2)),
								IdArmor.encode(res.getInt(3)),
								discordUserId,
								res.getLong(4),
								res.getString(5)));
					}
				}
			}

			return ret;
		}
	}

	public record ActiveActionEntry(int id, ActionType type, int targetUserId, long targetDiscordUserId, long expirationTime, String reason) { }
}
