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

import java.sql.Connection;
import java.sql.SQLException;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

public final class Database {
	static final int currentVersion = 6;

	private static final int preparedStatementCacheSize = 250;
	private static final int preparedStatementCacheSqlLimit = 2048;

	private final HikariDataSource dataSource;

	public Database(String url) {
		HikariConfig config = new HikariConfig();
		config.setJdbcUrl(url);
		config.addDataSourceProperty("cachePrepStmts", "true");
		config.addDataSourceProperty("prepStmtCacheSize", Integer.toString(preparedStatementCacheSize));
		config.addDataSourceProperty("prepStmtCacheSqlLimit", Integer.toString(preparedStatementCacheSqlLimit));
		config.addDataSourceProperty("noAccessToProcedureBodies", "true");

		dataSource = new HikariDataSource(config);

		DbMigration.run(this);
	}

	public Connection getConnection() throws SQLException {
		return dataSource.getConnection();
	}

	public void close() {
		dataSource.close();
	}
}
