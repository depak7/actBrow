package com.actbrow.actbrow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;
import java.sql.SQLException;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

class SafetyFlagsMigrationTests {

	private static final String SCRIPT = "db/migration/V20260923__assistant_safety_flags.sql";

	@Test
	void createsTheTableIsRerunnableAndEnforcesOneRowPerFlag() throws Exception {
		String sql = new ClassPathResource(SCRIPT).getContentAsString(StandardCharsets.UTF_8);
		String url = "jdbc:h2:mem:actbrow-safety-flags;MODE=PostgreSQL;DB_CLOSE_DELAY=-1";
		try (var connection = DriverManager.getConnection(url, "sa", "");
			 var statement = connection.createStatement()) {
			statement.execute(sql);
			// The migration header promises it is safe to re-run.
			statement.execute(sql);

			statement.execute("INSERT INTO assistant_safety_flags (id, assistant_id, flag, enabled, updated_at) "
				+ "VALUES ('1', 'a1', 'tools_enabled', FALSE, CURRENT_TIMESTAMP)");
			try (var rs = statement.executeQuery(
				"SELECT enabled FROM assistant_safety_flags WHERE assistant_id = 'a1' AND flag = 'tools_enabled'")) {
				assertThat(rs.next()).isTrue();
				assertThat(rs.getBoolean(1)).isFalse();
			}

			assertThatThrownBy(() -> statement.execute(
				"INSERT INTO assistant_safety_flags (id, assistant_id, flag, enabled, updated_at) "
					+ "VALUES ('2', 'a1', 'tools_enabled', TRUE, CURRENT_TIMESTAMP)"))
				.isInstanceOf(SQLException.class);
		}
	}
}
