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

import net.fabricmc.discord.bot.command.mod.ChannelActionType;
import net.fabricmc.discord.bot.database.Database;
import net.fabricmc.discord.bot.database.IdArmor;

public final class ChannelActionQueries {
	public static ChannelActionEntry getAction(Database db, int actionId) throws SQLException {
		if (db == null) throw new NullPointerException("null db");

		int rawActionId = IdArmor.decodeOrThrow(actionId, "action id");

		try (Connection conn = db.getConnection();
				PreparedStatement ps = conn.prepareStatement("SELECT a.type, a.channel_id, a.data, a.resetdata, a.actor_user_id, a.creation, a.expiration, a.reason, a.prev_id, s.suspender_user_id, s.time, s.reason "
						+ "FROM `channelaction` a "
						+ "LEFT JOIN `channelactionsuspension` s ON s.channelaction_id = a.id "
						+ "WHERE a.id = ?")) {
			ps.setInt(1, rawActionId);

			try (ResultSet res = ps.executeQuery()) {
				if (!res.next()) return null;

				int rawSuspenderUserId = res.getInt(10);
				if (res.wasNull()) rawSuspenderUserId = -1;

				long suspendTime = res.getLong(11);
				if (res.wasNull()) suspendTime = -1;

				return new ChannelActionEntry(actionId, // id
						ChannelActionType.get(res.getString(1)), // type
						res.getLong(2), // channelId
						res.getInt(3), // data
						res.getInt(4), // resetData
						IdArmor.encode(res.getInt(5)), // actorUserId
						res.getLong(6), // creationTime
						res.getLong(7), // expirationTime
						res.getString(8), // reason
						IdArmor.encodeOptional(res.getInt(9)), // prevId
						IdArmor.encodeOptional(rawSuspenderUserId), // suspenderUserId
						suspendTime, // suspensionTime
						res.getString(12)); // suspendReason
			}
		}
	}

	public static Collection<ChannelActionEntry> getActions(Database db, long channelId) throws SQLException {
		if (db == null) throw new NullPointerException("null db");

		try (Connection conn = db.getConnection();
				PreparedStatement ps = conn.prepareStatement("SELECT a.id, a.type, a.data, a.resetdata, a.actor_user_id, a.creation, a.expiration, a.reason, a.prev_id, s.suspender_user_id, s.time, s.reason "
						+ "FROM `channelaction` a "
						+ "LEFT JOIN `channelactionsuspension` s ON s.channelaction_id = a.id "
						+ "WHERE a.channel_id = ?")) {
			ps.setLong(1, channelId);

			try (ResultSet res = ps.executeQuery()) {
				List<ChannelActionEntry> ret = new ArrayList<>();

				while (res.next()) {
					int rawSuspenderUserId = res.getInt(10);
					if (res.wasNull()) rawSuspenderUserId = -1;

					long suspendTime = res.getLong(11);
					if (res.wasNull()) suspendTime = -1;

					ret.add(new ChannelActionEntry(IdArmor.encode(res.getInt(1)), // id
							ChannelActionType.get(res.getString(2)), // type
							channelId, // channelId
							res.getInt(3), // data
							res.getInt(4), // resetData
							IdArmor.encode(res.getInt(5)), // actorUserId
							res.getLong(6), // creationTime
							res.getLong(7), // expirationTime
							res.getString(8), // reason
							IdArmor.encodeOptional(res.getInt(9)), // prevId
							IdArmor.encodeOptional(rawSuspenderUserId), // suspenderUserId
							suspendTime, // suspensionTime
							res.getString(12))); // suspendReason
				}

				return ret;
			}
		}
	}

	public static ChannelActionEntry createAction(Database db, ChannelActionType type, long channelId, int data, int resetData, int actorUserId, long durationMs, String reason, int prevId) throws SQLException {
		if (db == null) throw new NullPointerException("null db");
		if (type == null) throw new NullPointerException("null type");
		if (actorUserId < 0) throw new IllegalArgumentException("invalid actor userid");
		if (durationMs == 0 && type.hasDuration) throw new IllegalArgumentException("invalid zero duration");

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
				PreparedStatement ps = conn.prepareStatement("INSERT INTO `channelaction` (type, channel_id, data, resetdata, actor_user_id, creation, expiration, reason, prev_id) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)", Statement.RETURN_GENERATED_KEYS);
				PreparedStatement psExpire = conn.prepareStatement("INSERT INTO `channelactionexpiration` (channelaction_id, time) VALUES (?, ?)");
				PreparedStatement psActive = conn.prepareStatement("INSERT INTO `activechannelaction` (channelaction_id, channel_id) VALUES (?, ?)")) {
			conn.setAutoCommit(false);

			ps.setString(1, type.id);
			ps.setLong(2, channelId);
			ps.setInt(3, data);
			ps.setInt(4, resetData);
			ps.setInt(5, rawActorUserId);
			ps.setLong(6, creationTime);
			ps.setLong(7, expirationTime);
			ps.setString(8, reason);
			ps.setInt(9, rawPrevId);
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
				psActive.setLong(2, channelId);
				psActive.executeUpdate();
			}

			conn.commit();

			return new ChannelActionEntry(IdArmor.encode(rawActionId), type, channelId, data, resetData, actorUserId,
					creationTime, expirationTime,
					reason, prevId,
					-1, -1, null);
		}
	}

	public record ChannelActionEntry(int id, ChannelActionType type, long channelId, int data, int resetData, int actorUserId,
			long creationTime, long expirationTime,
			String reason, int prevId,
			int suspenderUserId, long suspensionTime, String suspendReason) { }

	public static Collection<ExpiringChannelActionEntry> getExpiringActions(Database db, long maxTime) throws SQLException {
		if (db == null) throw new NullPointerException("null db");

		try (Connection conn = db.getConnection();
				PreparedStatement ps = conn.prepareStatement("SELECT a.id, a.type, a.channel_id, a.resetdata, a.expiration "
						+ "FROM `channelactionexpiration` e, `channelaction` a "
						+ "WHERE e.time < ? AND a.id = e.channelaction_id")) {
			ps.setLong(1, maxTime);

			try (ResultSet res = ps.executeQuery()) {
				List<ExpiringChannelActionEntry> ret = new ArrayList<>();

				while (res.next()) {
					ret.add(new ExpiringChannelActionEntry(IdArmor.encode(res.getInt(1)), ChannelActionType.get(res.getString(2)), res.getLong(3), res.getInt(4), res.getLong(5)));
				}

				return ret;
			}
		}
	}

	public record ExpiringChannelActionEntry(int id, ChannelActionType type, long channelId, int resetData, long expirationTime) { }

	public static boolean isExpiringAction(Database db, int actionId) throws SQLException {
		if (db == null) throw new NullPointerException("null db");

		int rawActionId = IdArmor.decodeOrThrow(actionId, "action id");

		try (Connection conn = db.getConnection();
				PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*) FROM `channelactionexpiration` WHERE channelaction_id = ?")) {
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
				PreparedStatement psExpire = conn.prepareStatement("DELETE FROM `channelactionexpiration` WHERE channelaction_id = ?");
				PreparedStatement psActive = conn.prepareStatement("DELETE FROM `activechannelaction` WHERE channelaction_id = ?")) {
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
				PreparedStatement psSuspend = conn.prepareStatement("INSERT OR IGNORE INTO `channelactionsuspension` (`channelaction_id`, `suspender_user_id`, `time`, `reason`) VALUES (?, ?, ?, ?)");
				PreparedStatement psExpire = conn.prepareStatement("DELETE FROM `channelactionexpiration` WHERE channelaction_id = ?");
				PreparedStatement psActive = conn.prepareStatement("DELETE FROM `activechannelaction` WHERE channelaction_id = ?")) {
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

	public static ActiveChannelActionEntry getActiveAction(Database db, long channelId, ChannelActionType type) throws SQLException {
		if (db == null) throw new NullPointerException("null db");

		try (Connection conn = db.getConnection();
				PreparedStatement ps = conn.prepareStatement("SELECT a.id, a.type, a.channel_id, a.data, a.resetdata, a.expiration, a.reason "
						+ "FROM `activechannelaction` aa, `channelaction` a "
						+ "WHERE aa.channel_id = ? AND a.id = aa.channelaction_id AND a.type = ?")) {
			ps.setLong(1, channelId);
			ps.setString(2, type.id);

			try (ResultSet res = ps.executeQuery()) {
				if (!res.next()) return null;

				return new ActiveChannelActionEntry(IdArmor.encode(res.getInt(1)), // id
						ChannelActionType.get(res.getString(2)), // type
						res.getLong(3), // channelId
						res.getInt(4), // data
						res.getInt(5), // resetData
						res.getLong(6), // expirationTime
						res.getString(7)); // reason
			}
		}
	}

	public static Collection<ActiveChannelActionEntry> getActiveActions(Database db) throws SQLException {
		if (db == null) throw new NullPointerException("null db");

		try (Connection conn = db.getConnection();
				PreparedStatement ps = conn.prepareStatement("SELECT a.id, a.type, a.channel_id, a.data, a.resetdata, a.expiration, a.reason "
						+ "FROM `activechannelaction` aa "
						+ "JOIN `channelaction` a ON a.id = aa.channelaction_id")) {
			try (ResultSet res = ps.executeQuery()) {
				List<ActiveChannelActionEntry> ret = new ArrayList<>();

				while (res.next()) {
					ret.add(new ActiveChannelActionEntry(IdArmor.encode(res.getInt(1)), // id
							ChannelActionType.get(res.getString(2)), // type
							res.getLong(3), // channelId
							res.getInt(4), // data
							res.getInt(5), // resetData
							res.getLong(6), // expirationTime
							res.getString(7))); // reason
				}

				return ret;
			}
		}
	}


	public record ActiveChannelActionEntry(int id, ChannelActionType type, long channelId, int data, int resetData, long expirationTime, String reason) { }
}
