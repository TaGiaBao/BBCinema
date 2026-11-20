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

/** V19 (birthday reward) applied with Flyway to a database already at V18 (H2 in MySQL mode). */
class BirthdayMigrationTest {

    private static final String URL = "jdbc:h2:mem:flyway-check-v19;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";

    @Test
    void v19AddsPersonalPromotionsTheSettingsRowAndOneGiftPerMemberAndYear() throws Exception {
        try (Connection connection = DriverManager.getConnection(URL, "sa", "");
             Statement statement = connection.createStatement()) {
            statement.execute("create table users (id bigint primary key, email varchar(120) not null)");
            statement.execute("""
                    create table promotions (id bigint auto_increment primary key, code varchar(30) not null,
                        discount_percent integer not null)""");
            statement.execute("insert into users (id, email) values (1, 'a@test.vn'), (2, 'b@test.vn')");
            statement.execute("insert into promotions (id, code, discount_percent) values (1, 'BBCINEMA10', 10)");
        }

        Flyway flyway = Flyway.configure().dataSource(URL, "sa", "")
                .locations("classpath:db/migration")
                .baselineOnMigrate(true).baselineVersion("18").target("19")
                .load();
        flyway.migrate();
        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("19");

        try (Connection connection = DriverManager.getConnection(URL, "sa", "");
             Statement statement = connection.createStatement()) {
            // Existing codes stay general (no owner) and percent-based.
            try (ResultSet rs = statement.executeQuery("select owner_id, discount_amount from promotions where id = 1")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getObject(1)).isNull();
                assertThat(rs.getObject(2)).isNull();
            }
            // The gift starts turned off.
            try (ResultSet rs = statement.executeQuery(
                    "select enabled, discount_type, discount_value from birthday_reward_settings where id = 1")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getBoolean(1)).isFalse();
                assertThat(rs.getString(2)).isEqualTo("PERCENT");
                assertThat(rs.getInt(3)).isEqualTo(20);
            }
            statement.execute("insert into promotions (id, code, discount_percent, owner_id) values (2, 'SNAAAAAAAA', 20, 1)");
            statement.execute("insert into promotions (id, code, discount_percent, owner_id) values (3, 'SNBBBBBBBB', 20, 1)");
            assertThatThrownBy(() -> statement.execute(
                    "insert into promotions (id, code, discount_percent, owner_id) values (4, 'SNCCCCCCCC', 20, 99)"))
                    .as("owner must be an existing account").isInstanceOf(SQLException.class);

            statement.execute("insert into birthday_rewards (user_id, reward_year, promotion_id, issued_at) values (1, 2026, 2, now())");
            assertThatThrownBy(() -> statement.execute(
                    "insert into birthday_rewards (user_id, reward_year, promotion_id, issued_at) values (1, 2026, 3, now())"))
                    .as("one gift per member and year").isInstanceOf(SQLException.class);
            // Next year is a new gift.
            statement.execute("insert into birthday_rewards (user_id, reward_year, promotion_id, issued_at) values (1, 2027, 3, now())");
            assertThatThrownBy(() -> statement.execute(
                    "insert into birthday_rewards (user_id, reward_year, promotion_id, issued_at) values (2, 2027, 3, now())"))
                    .as("a code belongs to one gift").isInstanceOf(SQLException.class);
        }
    }
}
