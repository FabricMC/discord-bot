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
	public boolean run(CommandContext context, Map<String, String> arguments) throws Exception {
		List<String> values = new ArrayList<>();
		int cols;

		try (Connection conn = context.bot().getDatabase().getConnection();
				Statement st = conn.createStatement()) {
			try (ResultSet res = st.executeQuery("SELECT * FROM `%s`".formatted(arguments.get("table").replace('`', ' ')))) {
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

		StringBuilder pageSb = new StringBuilder();
		StringBuilder lineSb = new StringBuilder();
		int row = 1;

		while (row < rows) {
			pageSb.append("```\n");

			for (int col = 0; col < cols; col++) {
				if (col > 0) pageSb.append(" | ");
				int idx = col;

				String val = values.get(idx);
				pageSb.append(val);

				if (col + 1 < cols) {
					for (int i = 0; i < pad[idx]; i++) {
						pageSb.append(' ');
					}
				}
			}

			pageSb.append('\n');


			if (lineSb.length() > 0) {
				pageSb.append(lineSb);
				row++;
			}

			while (row < rows) {
				lineSb.setLength(0);

				for (int col = 0; col < cols; col++) {
					if (col > 0) lineSb.append(" | ");
					int idx = row * cols + col;

					String val = values.get(idx);
					lineSb.append(val);

					if (col + 1 < cols) {
						for (int i = 0; i < pad[idx]; i++) {
							lineSb.append(' ');
						}
					}
				}

				lineSb.append('\n');

				if (pageSb.length() + lineSb.length() + 3 <= 2000) {
					pageSb.append(lineSb);
					row++;
				} else {
					break;
				}
			}

			pageSb.append("```");
			context.channel().sendMessage(pageSb.toString());
			pageSb.setLength(0);
		}

		return true;
	}
}
