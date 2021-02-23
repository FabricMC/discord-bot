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

package net.fabricmc.discord.bot.command.core;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import net.fabricmc.discord.bot.UserHandler;
import net.fabricmc.discord.bot.command.Command;
import net.fabricmc.discord.bot.command.CommandContext;

public final class DbCommand extends Command {
	@Override
	public String name() {
		return "db";
	}

	@Override
	public String usage() {
		return "<table>";
	}

	@Override
	public String getPermission() {
		return UserHandler.ADMIN_PERMISSION;
	}

	@Override
	public boolean run(CommandContext context, Map<String, String> arguments) {
		List<String> values = new ArrayList<>();
		int cols;

		try (Connection conn = context.bot().getDatabase().getConnection();
				Statement st = conn.createStatement()) {
			try (ResultSet res = st.executeQuery("SELECT * FROM `%s` LIMIT 30".formatted(arguments.get("table").replace('`', ' ')))) {
				ResultSetMetaData meta = res.getMetaData();
				cols = meta.getColumnCount();

				for (int i = 1; i <= cols; i++) {
					values.add(meta.getColumnName(i));
				}

				while (res.next()) {
					for (int i = 1; i <= cols; i++) {
						String val = res.getString(i);
						values.add(val != null ? val : "NULL");
					}
				}
			}
		} catch (SQLException e) {
			context.channel().sendMessage("Query failed:\n`%s`".formatted(e));
			return false;
		}

		int rows = values.size() / cols;
		int[] pad = new int[values.size()];

		for (int col = 0; col < cols; col++) {
			int len = 0;

			for (int row = 0; row < rows; row++) {
				int idx = row * cols + col;
				String val = values.get(idx);
				if (val.length() > len) len = val.length();
			}

			for (int row = 0; row < rows; row++) {
				int idx = row * cols + col;
				pad[idx] = len - values.get(idx).length();
			}
		}

		StringBuilder sb = new StringBuilder();
		sb.append("```\n");

		for (int row = 0; row < rows; row++) {
			for (int col = 0; col < cols; col++) {
				if (col > 0) sb.append(" | ");
				int idx = row * cols + col;

				String val = values.get(idx);
				sb.append(val);

				if (col + 1 < cols) {
					for (int i = 0; i < pad[idx]; i++) {
						sb.append(' ');
					}
				}
			}

			sb.append('\n');
		}

		sb.append("```");
		context.channel().sendMessage(sb.toString());

		return true;
	}
}
