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

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import net.fabricmc.discord.bot.command.mod.ActionTargetKind;
import net.fabricmc.discord.bot.command.mod.UserActionType;

public final class DbImport {
	// import old bot db (exported via mysql -u fabric_bot -p --default-character-set=utf8mb4 and select * from <table>)
	private static final Pattern userPattern = Pattern.compile("\\| \\s*(\\d+)" // id: discord id
			+ " \\| (.+?)\\s*" // avatarUrl: url string
			+ " \\| (\\d{4})\\s*" // discriminator: 4 digits
			+ " \\| \\s*([01])" // present: 0/1
			+ " \\| (.+?)\\s*" // username: string
			+ " \\|");
	private static final Pattern infractionPattern = Pattern.compile("\\| ([0-9a-f\\-]+)\\s*" // id: uuid
			+ " \\| (.+?)\\s*" // reason: string
			+ " \\| \\s*(\\d+)" // moderator_id: discord id
			+ " \\| \\s*(\\d+)" // target_id: discord id
			+ " \\| (\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2})\\s*" // created: date yyyy-MM-dd hh:mm:ss
			+ " \\| (NULL|\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2})\\s*" // expires: date yyyy-MM-dd hh:mm:ss
			+ " \\| \\s*([01])" // active: 0/1
			+ " \\| ([A-Z_]+)\\s*" // infraction_type: string
			+ " \\|", Pattern.DOTALL);

	private static final DateTimeFormatter dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneOffset.UTC);

	public static void main(String[] args) throws IOException, SQLException {
		if (args.length != 3) {
			System.err.println("usage: <userfile> <infractionFile> <sqliteDbFile>");
		}

		Path userFile = Paths.get(args[0]);
		Path infractionFile = Paths.get(args[1]);

		Map<Long, UserEntry> users = new HashMap<>();

		try (BufferedReader reader = Files.newBufferedReader(userFile)) {
			String line;
			int lineNumber = 0;
			boolean isHeader = true;

			while ((line = reader.readLine()) != null) {
				lineNumber++;
				line = line.trim();
				if (line.isEmpty() || line.startsWith("+")) continue;

				if (isHeader) {
					isHeader = false;
					continue;
				}

				Matcher matcher = userPattern.matcher(line);

				if (!matcher.matches()) {
					throw new IOException(String.format("User line %d is invalid: %s", lineNumber, line));
				}

				long id = Long.parseUnsignedLong(matcher.group(1));
				//System.out.printf("id: %d user: %s#%s%n", id, matcher.group(5), matcher.group(3));

				users.put(id, new UserEntry(id, matcher.group(5), matcher.group(3)));
			}
		}

		Instant now = Instant.now();
		int added = 0;
		int total = 0;

		try (Connection conn = DriverManager.getConnection("jdbc:sqlite:"+args[2]);
				PreparedStatement psGetUserId = conn.prepareStatement("SELECT user_id FROM `discorduser` WHERE id = ?");
				PreparedStatement psAddUser = conn.prepareStatement("INSERT INTO user (stickyname) VALUES (?)", Statement.RETURN_GENERATED_KEYS);
				PreparedStatement psAddDiscordUser = conn.prepareStatement("INSERT INTO `discorduser` "
						+ "(id, user_id, username, discriminator, nickname, firstseen, lastseen, lastnickchange, present) "
						+ "VALUES "
						+ "(?, ?, ?, ?, ?, ?, ?, ?, '0')");
				PreparedStatement psAddNote = conn.prepareStatement("INSERT INTO `note` (target_user_id, actor_user_id, creation, content) VALUES (?, ?, ?, ?)");
				PreparedStatement psAddAction = conn.prepareStatement("INSERT INTO `action` "
						+ "(targetkind, type, target_id, actor_user_id, creation, expiration, reason, prev_id) "
						+ "VALUES "
						+ "('"+ActionTargetKind.USER.id+"', ?, ?, ?, ?, ?, ?, '0')", Statement.RETURN_GENERATED_KEYS);
				PreparedStatement psAddActiveAction = conn.prepareStatement("INSERT INTO `activeaction` (action_id, target_id) VALUES (?, ?)");
				PreparedStatement psAddActiveExpiration = conn.prepareStatement("INSERT INTO `actionexpiration` (action_id, time) VALUES (?, ?)");
				PreparedStatement psSuspendAction = conn.prepareStatement("INSERT OR IGNORE INTO `actionsuspension` (`action_id`, `suspender_user_id`, `time`, `reason`) VALUES (?, ?, ?, 'inactive legacy entry')");
				BufferedReader reader = Files.newBufferedReader(infractionFile)) {
			conn.setAutoCommit(false);
			String line;
			int lineNumber = 0;
			boolean isHeader = true;

			while ((line = reader.readLine()) != null) {
				lineNumber++;
				line = line.trim();
				if (line.isEmpty() || line.startsWith("+")) continue;

				if (isHeader) {
					isHeader = false;
					continue;
				}

				while (!line.endsWith("|")) {
					String nextLine = reader.readLine();
					if (nextLine == null) break;
					line = line+"\n"+nextLine;
				}

				total++;
				Matcher matcher = infractionPattern.matcher(line);

				if (!matcher.matches()) {
					throw new IOException(String.format("Infraction line %d is invalid: %s", lineNumber, line));
				}

				String reason = matcher.group(2);
				long modDiscordId = Long.parseUnsignedLong(matcher.group(3));
				long targetDiscordId = Long.parseUnsignedLong(matcher.group(4));
				Instant created = Instant.from(dateFormatter.parse(matcher.group(5)));
				Instant expires = matcher.group(6).equals("NULL") ? null : Instant.from(dateFormatter.parse(matcher.group(6)));
				boolean active = matcher.group(7).equals("1");

				UserActionType type = switch (matcher.group(8)) {
				case "BAN" -> UserActionType.BAN;
				case "KICK" -> UserActionType.KICK;
				case "META_MUTE" -> UserActionType.META_MUTE;
				case "MUTE" -> UserActionType.MUTE;
				case "NICK_LOCK" -> UserActionType.NICK_LOCK;
				case "NOTE" -> null;
				case "REACTION_MUTE" -> UserActionType.REACTION_MUTE;
				case "REQUESTS_MUTE" -> UserActionType.REQUESTS_MUTE;
				case "SUPPORT_MUTE" -> UserActionType.SUPPORT_MUTE;
				case "WARN" -> UserActionType.WARN;
				default -> throw new RuntimeException("unknown action type: "+matcher.group(8));
				};

				if (type == null || !type.hasDuration() || expires != null && expires.isBefore(now)) {
					active = false;
				}

				int modUserId = getCreateUser(modDiscordId, users, created, psGetUserId, psAddUser, psAddDiscordUser);

				if (modUserId < 0) {
					System.out.printf("skipping %s, mod unavailable%n", matcher.group(1));
					continue;
				}

				int targetUserId = getCreateUser(targetDiscordId, users, created, psGetUserId, psAddUser, psAddDiscordUser);

				if (targetUserId < 0) {
					System.out.printf("skipping %s, target unavailable%n", matcher.group(1));
					continue;
				}

				if (type == null) { // is note
					psAddNote.setInt(1, targetUserId);
					psAddNote.setInt(2, modUserId);
					psAddNote.setLong(3, created.toEpochMilli());
					psAddNote.setString(4, reason);
					psAddNote.executeUpdate();
				} else { // is action
					psAddAction.setString(1, type.id); // type
					psAddAction.setInt(2, targetUserId); // target_id
					psAddAction.setInt(3, modUserId); // actor_user_id
					psAddAction.setLong(4, created.toEpochMilli()); // creation
					psAddAction.setLong(5, !type.hasDuration() ? 0 : (expires == null ? -1 : expires.toEpochMilli())); // expiration
					psAddAction.setString(6, "legacy import: "+reason); // reason
					psAddAction.executeUpdate();

					int actionId;

					try (ResultSet res = psAddAction.getGeneratedKeys()) {
						if (!res.next()) throw new IllegalStateException();
						actionId = res.getInt(1);
					}

					if (active) {
						psAddActiveAction.setInt(1, actionId);
						psAddActiveAction.setInt(2, targetUserId);
						psAddActiveAction.executeUpdate();

						if (expires != null) {
							psAddActiveExpiration.setInt(1, actionId);
							psAddActiveExpiration.setLong(2, expires.toEpochMilli());
							psAddActiveExpiration.executeUpdate();
						}
					} else if (type.hasDuration() && (expires == null || expires.isAfter(now))) { // action was manually suspended
						psSuspendAction.setInt(1, actionId);
						psSuspendAction.setInt(2, modUserId);
						psSuspendAction.setLong(3, created.toEpochMilli() + 1);
					}
				}

				added++;
			}

			conn.commit();
		}

		System.out.printf("done, imported %d/%d infractions%n", added, total);
	}

	private static int getCreateUser(long discordId, Map<Long, UserEntry> userDataMap, Instant lastSeen,
			PreparedStatement psGetUserId, PreparedStatement psAddUser, PreparedStatement psAddDiscordUser) throws SQLException {
		psGetUserId.setLong(1, discordId);

		try (ResultSet res = psGetUserId.executeQuery()) {
			if (res.next()) return res.getInt(1);
		}

		UserEntry entry = userDataMap.get(discordId);
		if (entry == null) return -1;

		psAddUser.setString(1, entry.username);
		psAddUser.executeUpdate();
		int ret;

		try (ResultSet res = psAddUser.getGeneratedKeys()) {
			if (!res.next()) throw new IllegalStateException();
			ret = res.getInt(1);
		}

		psAddDiscordUser.setLong(1, discordId); // id
		psAddDiscordUser.setInt(2, ret); // user_id
		psAddDiscordUser.setString(3, entry.username); // username
		psAddDiscordUser.setString(4, entry.discriminator); // discriminator
		psAddDiscordUser.setNull(5, Types.VARCHAR); // nickname
		long time = lastSeen.toEpochMilli();
		psAddDiscordUser.setLong(6, time); // firstseen
		psAddDiscordUser.setLong(7, time); // lastseen
		psAddDiscordUser.setLong(8, time); // lastnickchange
		psAddDiscordUser.executeUpdate();

		return ret;
	}

	private record UserEntry(long id, String username, String discriminator) { }
}
