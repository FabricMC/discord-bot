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

import net.fabricmc.discord.bot.database.Database;
import net.fabricmc.discord.bot.database.IdArmor;

public final class NoteQueries {
	public static NoteEntry create(Database db, int targetUserId, int actorUserId, String content) throws SQLException {
		if (db == null) throw new NullPointerException("null db");
		if (content == null) throw new NullPointerException("null content");
		if (targetUserId < 0) throw new IllegalArgumentException("invalid target userid");
		if (actorUserId < 0) throw new IllegalArgumentException("invalid actor userid");

		int rawTargetUserId = IdArmor.decodeOrThrow(targetUserId, "target user id");
		int rawActorUserId = IdArmor.decodeOrThrow(actorUserId, "actor user id");
		long creationTime = System.currentTimeMillis();

		try (Connection conn = db.getConnection();
				PreparedStatement ps = conn.prepareStatement("INSERT INTO `note` (target_user_id, actor_user_id, creation, content) VALUES (?, ?, ?, ?)", Statement.RETURN_GENERATED_KEYS)) {
			ps.setInt(1, rawTargetUserId);
			ps.setInt(2, rawActorUserId);
			ps.setLong(3, creationTime);
			ps.setString(4, content);
			ps.executeUpdate();

			try (ResultSet res = ps.getGeneratedKeys()) {
				if (!res.next()) throw new IllegalStateException();

				return new NoteEntry(res.getInt(1), targetUserId, actorUserId, creationTime, content);
			}
		}
	}

	public static Collection<NoteEntry> getAll(Database db, int targetUserId) throws SQLException {
		if (db == null) throw new NullPointerException("null db");
		if (targetUserId < 0) throw new IllegalArgumentException("invalid target userid");

		int rawTargetUserId = IdArmor.decodeOrThrow(targetUserId, "user id");

		try (Connection conn = db.getConnection();
				PreparedStatement ps = conn.prepareStatement("SELECT id, actor_user_id, creation, content FROM `note` WHERE target_user_id = ?")) {
			ps.setInt(1, rawTargetUserId);

			try (ResultSet res = ps.executeQuery()) {
				List<NoteEntry> ret = new ArrayList<>();

				while (res.next()) {
					ret.add(new NoteEntry(IdArmor.encode(res.getInt(1)), // id
							targetUserId, // targetUserId
							IdArmor.encode(res.getInt(2)), // actorUserId
							res.getLong(3), // creationTime
							res.getString(4))); // content
				}

				return ret;
			}
		}
	}

	public static NoteEntry get(Database db, int noteId) throws SQLException {
		if (db == null) throw new NullPointerException("null db");
		if (noteId < 0) throw new IllegalArgumentException("invalid note id");

		int rawNoteId = IdArmor.decodeOrThrow(noteId, "note id");

		try (Connection conn = db.getConnection();
				PreparedStatement ps = conn.prepareStatement("SELECT target_user_id, actor_user_id, creation, content FROM `note` WHERE id = ?")) {
			ps.setInt(1, rawNoteId);

			try (ResultSet res = ps.executeQuery()) {
				if (!res.next()) return null;

				return new NoteEntry(noteId, // id
						IdArmor.encode(res.getInt(1)), // targetUserId
						IdArmor.encode(res.getInt(2)), // actorUserId
						res.getLong(3), // creationTime
						res.getString(4)); // content
			}
		}
	}

	public static boolean setContent(Database db, int noteId, String content) throws SQLException {
		if (db == null) throw new NullPointerException("null db");
		if (content == null) throw new NullPointerException("null content");
		if (noteId < 0) throw new IllegalArgumentException("invalid note id");

		int rawNoteId = IdArmor.decodeOrThrow(noteId, "note id");

		try (Connection conn = db.getConnection();
				PreparedStatement ps = conn.prepareStatement("UPDATE `note` SET content = ? WHERE id = ?")) {
			ps.setString(1, content);
			ps.setInt(2, rawNoteId);

			return ps.executeUpdate() > 0;
		}
	}

	public static boolean remove(Database db, int noteId) throws SQLException {
		if (db == null) throw new NullPointerException("null db");
		if (noteId < 0) throw new IllegalArgumentException("invalid note id");

		int rawNoteId = IdArmor.decodeOrThrow(noteId, "note id");

		try (Connection conn = db.getConnection();
				PreparedStatement ps = conn.prepareStatement("DELETE FROM `note` WHERE id = ?")) {
			ps.setInt(1, rawNoteId);

			return ps.executeUpdate() > 0;
		}
	}

	public record NoteEntry(int id, int targetUserId, int actorUserId, long creationTime, String content) { }
}
