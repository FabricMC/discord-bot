package net.fabricmc.discord.bot.database;

import java.sql.Connection;
import java.sql.SQLException;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

public final class Database {
	static final int currentVersion = 1;

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
