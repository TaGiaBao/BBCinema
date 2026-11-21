package com.bbcinema.config;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
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
                    .isInstanceOf(SQLException.class);
        }
    }

    /**
     * V17 on a database at V16: ticket_prices.day_type (a MySQL enum) accepts HOLIDAY and keeps the
     * existing rows, and the holidays table refuses a second row for the same date.
     */
    @Test
    void v17AddsHolidayPricesAndTheHolidaysTable() throws Exception {
        String url = "jdbc:h2:mem:flyway-v17;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
        try (Connection connection = DriverManager.getConnection(url, "sa", "");
             Statement statement = connection.createStatement()) {
            statement.execute("""
                    create table ticket_prices (
                        price decimal(12,0) not null, id bigint not null auto_increment,
                        day_type enum ('WEEKDAY','WEEKEND') not null, format enum ('THREE_D','TWO_D') not null,
                        seat_type enum ('COUPLE','STANDARD','VIP') not null, time_slot enum ('BEFORE_17','FROM_17') not null,
                        primary key (id))""");
            statement.execute("insert into ticket_prices (price, day_type, format, seat_type, time_slot) "
                    + "values (90000, 'WEEKEND', 'TWO_D', 'STANDARD', 'FROM_17')");
        }

        Flyway flyway = Flyway.configure().dataSource(url, "sa", "")
                .locations("classpath:db/migration")
                .baselineOnMigrate(true).baselineVersion("16")
                .target("17")
                .load();
        flyway.migrate();
        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("17");

        try (Connection connection = DriverManager.getConnection(url, "sa", "");
             Statement statement = connection.createStatement()) {
            statement.execute("insert into ticket_prices (price, day_type, format, seat_type, time_slot) "
                    + "values (99000, 'HOLIDAY', 'TWO_D', 'STANDARD', 'FROM_17')");
            try (ResultSet rs = statement.executeQuery("select day_type, price from ticket_prices order by id")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString(1)).isEqualTo("WEEKEND");
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString(1)).isEqualTo("HOLIDAY");
                assertThat(rs.getInt(2)).isEqualTo(99000);
            }
            statement.execute("insert into holidays (holiday_date, name) values ('2027-09-02', 'Quốc khánh 2/9')");
            assertThatThrownBy(() -> statement.execute("insert into holidays (holiday_date, name) values ('2027-09-02', 'Trùng')"))
                    .isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> statement.execute("insert into ticket_prices (price, day_type, format, seat_type, time_slot) "
                    + "values (1, 'OTHER', 'TWO_D', 'STANDARD', 'FROM_17')"))
                    .isInstanceOf(SQLException.class);
        }
    }

    @Test
    void v21CreatesTheAuditLog() throws Exception {
        String url = "jdbc:h2:mem:flyway-check-v21;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
        // A database already at V20 (Flyway only baselines a schema that has tables).
        try (Connection connection = DriverManager.getConnection(url, "sa", "");
             Statement statement = connection.createStatement()) {
            statement.execute("create table bookings (id bigint primary key)");
        }
        Flyway flyway = Flyway.configure().dataSource(url, "sa", "")
                .locations("classpath:db/migration")
                .baselineOnMigrate(true).baselineVersion("20").target("21")
                .load();
        flyway.migrate();
        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("21");

        try (Connection connection = DriverManager.getConnection(url, "sa", "");
             Statement statement = connection.createStatement()) {
            statement.execute("insert into audit_logs (created_at, actor_email, actor_role, action, success) "
                    + "values ('2026-11-21 10:00:00', 'admin@bbcinema.vn', 'ADMIN', 'Xoá phim', true)");
            try (ResultSet rs = statement.executeQuery("select action, success from audit_logs")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString(1)).isEqualTo("Xoá phim");
                assertThat(rs.getBoolean(2)).isTrue();
            }
            assertThatThrownBy(() -> statement.execute("insert into audit_logs (created_at, action, success) "
                    + "values ('2026-11-21 10:00:00', 'Thiếu người', true)"))
                    .isInstanceOf(SQLException.class);
        }
    }
}
