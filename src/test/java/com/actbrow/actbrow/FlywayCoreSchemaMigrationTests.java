package com.actbrow.actbrow;

import java.sql.DriverManager;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FlywayCoreSchemaMigrationTests {

	@Test
	void createsTheHistoricalTablesBeforeAddingConversationMessageSequence() throws Exception {
		String jdbcUrl = "jdbc:h2:mem:actbrow-flyway-core-schema;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE";
		Flyway flyway = Flyway.configure()
			.dataSource(jdbcUrl, "sa", "")
			.locations("classpath:db/migration")
			.target("20260701.1")
			.load();

		assertEquals(3, flyway.migrate().migrationsExecuted);
		try (var connection = DriverManager.getConnection(jdbcUrl, "sa", "");
			 var statement = connection.createStatement();
			 var result = statement.executeQuery("SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS "
				 + "WHERE TABLE_NAME = 'CONVERSATION_MESSAGES' AND COLUMN_NAME = 'SEQ'")) {
			result.next();
			assertEquals(1, result.getInt(1));
		}
	}
}
