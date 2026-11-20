package com.bbcinema.config;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Applies the latest migrations with Flyway to a database already at an older version (H2 in MySQL mode).
 * V1-V14 use MySQL-only syntax, so each test baselines its own database just before the migration it checks.
 */
class FlywayMigrationTest {

    private static final String URL = "jdbc:h2:mem:flyway-check;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
    private static final String URL_V16 = "jdbc:h2:mem:flyway-check-v16;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";

    @Test
    void v15AddsPrintLogColumnsToExistingBookings() throws Exception {
        try (Connection connection = DriverManager.getConnection(URL, "sa", "");
             Statement statement = connection.createStatement()) {
            statement.execute("create table bookings (id bigint primary key, code varchar(12) not null)");
            statement.execute("insert into bookings (id, code) values (1, 'K7M2QX9P')");
        }

        Flyway flyway = Flyway.configure().dataSource(URL, "sa", "")
                .locations("classpath:db/migration")
                .baselineOnMigrate(true).baselineVersion("14").target("15")
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

    @Test
    void v16AddsAUniqueNullableMemberCodeToExistingUsers() throws Exception {
        try (Connection connection = DriverManager.getConnection(URL_V16, "sa", "");
             Statement statement = connection.createStatement()) {
            statement.execute("create table users (id bigint primary key, email varchar(120) not null)");
            statement.execute("insert into users (id, email) values (1, 'a@test.vn'), (2, 'b@test.vn'), (3, 'c@test.vn')");
        }

        Flyway flyway = Flyway.configure().dataSource(URL_V16, "sa", "")
                .locations("classpath:db/migration")
                .baselineOnMigrate(true).baselineVersion("15").target("16")
                .load();
        flyway.migrate();
        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("16");

        try (Connection connection = DriverManager.getConnection(URL_V16, "sa", "");
             Statement statement = connection.createStatement()) {
            try (ResultSet rs = statement.executeQuery("""
                    select is_nullable from information_schema.columns
                    where table_name = 'users' and column_name = 'member_code'""")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString(1)).isEqualTo("YES");
            }
            // Existing accounts start without a code (the app gives members one at start-up), and many NULLs are fine.
            try (ResultSet rs = statement.executeQuery("select count(*) from users where member_code is null")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getInt(1)).isEqualTo(3);
            }
            statement.execute("update users set member_code = 'BBAAAAAAAA' where id = 1");
            assertThatThrownBy(() -> statement.execute("update users set member_code = 'BBAAAAAAAA' where id = 2"))
                    .isInstanceOf(java.sql.SQLException.class);
        }
    }
}
