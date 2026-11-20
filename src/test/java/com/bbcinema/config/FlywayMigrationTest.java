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
                .target("15")
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

    /**
     * V18 on a database at V17: ticket_prices.day_type (a MySQL enum) accepts HOLIDAY and keeps the
     * existing rows, and the holidays table refuses a second row for the same date.
     */
    @Test
    void v18AddsHolidayPricesAndTheHolidaysTable() throws Exception {
        String url = "jdbc:h2:mem:flyway-v18;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
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
                .baselineOnMigrate(true).baselineVersion("17")
                .target("18")
                .load();
        flyway.migrate();
        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("18");

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
                    .isInstanceOf(java.sql.SQLException.class);
            assertThatThrownBy(() -> statement.execute("insert into ticket_prices (price, day_type, format, seat_type, time_slot) "
                    + "values (1, 'OTHER', 'TWO_D', 'STANDARD', 'FROM_17')"))
                    .isInstanceOf(java.sql.SQLException.class);
        }
    }
}
