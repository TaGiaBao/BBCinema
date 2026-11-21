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

/** V20 (audience tickets) applied with Flyway to a database already at V19 (H2 in MySQL mode). */
class AudienceMigrationTest {

    private static final String URL = "jdbc:h2:mem:flyway-check-v20;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";

    @Test
    void v20AddsTheThreeTypesAndPerSeatColumnsWithoutTouchingOldSales() throws Exception {
        try (Connection connection = DriverManager.getConnection(URL, "sa", "");
             Statement statement = connection.createStatement()) {
            statement.execute("create table showtime_seats (id bigint primary key, price decimal(12,0) not null)");
            statement.execute("create table bookings (id bigint primary key, total_amount decimal(12,0) not null)");
            statement.execute("insert into showtime_seats (id, price) values (1, 60000)");
            statement.execute("insert into bookings (id, total_amount) values (1, 60000)");
        }

        Flyway flyway = Flyway.configure().dataSource(URL, "sa", "")
                .locations("classpath:db/migration")
                .baselineOnMigrate(true).baselineVersion("19").target("20")
                .load();
        flyway.migrate();
        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("20");

        try (Connection connection = DriverManager.getConnection(URL, "sa", "");
             Statement statement = connection.createStatement()) {
            try (ResultSet rs = statement.executeQuery("select audience_type, audience_discount from showtime_seats where id = 1")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getObject(1)).isNull();
                assertThat(rs.getObject(2)).isNull();
            }
            try (ResultSet rs = statement.executeQuery("select audience_discount from bookings where id = 1")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getInt(1)).isZero();
            }
            try (ResultSet rs = statement.executeQuery(
                    "select audience_type, enabled, discount_value, on_holidays from audience_discounts order by audience_type")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString(1)).isEqualTo("CHILD");
                assertThat(rs.getInt(3)).isEqualTo(30);
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString(1)).isEqualTo("SENIOR");
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString(1)).isEqualTo("STUDENT");
                assertThat(rs.getBoolean(2)).isTrue();
                assertThat(rs.getInt(3)).isEqualTo(20);
                assertThat(rs.getBoolean(4)).isFalse();
                assertThat(rs.next()).isFalse();
            }
            assertThatThrownBy(() -> statement.execute(
                    "insert into audience_discounts (audience_type, enabled, discount_type, discount_value, on_holidays) "
                            + "values ('STUDENT', true, 'PERCENT', 10, false)"))
                    .as("one row per type").isInstanceOf(SQLException.class);
        }
    }
}
