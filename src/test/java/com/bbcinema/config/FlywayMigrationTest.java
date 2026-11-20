package com.bbcinema.config;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Applies the V15 print log migration with Flyway to a database already at V14 (H2 in MySQL mode).
 * V1-V14 use MySQL-only syntax, so the database is baselined at 14 with an existing bookings row.
 */
class FlywayMigrationTest {

    private static final String URL = "jdbc:h2:mem:flyway-check;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";

    @Test
    void v15AddsPrintLogColumnsToExistingBookings() throws Exception {
        try (Connection connection = DriverManager.getConnection(URL, "sa", "");
             Statement statement = connection.createStatement()) {
            statement.execute("create table bookings (id bigint primary key, code varchar(12) not null)");
            statement.execute("insert into bookings (id, code) values (1, 'K7M2QX9P')");
        }

        Flyway flyway = Flyway.configure().dataSource(URL, "sa", "")
                .locations("classpath:db/migration")
                .baselineOnMigrate(true).baselineVersion("14")
                .load();
        flyway.migrate();
        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("15");

        try (Connection connection = DriverManager.getConnection(URL, "sa", "");
             Statement statement = connection.createStatement()) {
            try (ResultSet rs = statement.executeQuery("""
                    select column_name, is_nullable from information_schema.columns
                    where table_name = 'bookings' and column_name in ('print_count', 'last_printed_at')
                    order by column_name""")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString(1)).isEqualTo("last_printed_at");
                assertThat(rs.getString(2)).isEqualTo("YES");
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString(1)).isEqualTo("print_count");
                assertThat(rs.getString(2)).isEqualTo("NO");
            }
            try (ResultSet rs = statement.executeQuery("select print_count, last_printed_at from bookings where id = 1")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getInt(1)).isZero(); // existing bookings start at "never printed"
                assertThat(rs.getObject(2)).isNull();
            }
        }
    }
}
