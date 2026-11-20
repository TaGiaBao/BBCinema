package com.bbcinema.config;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Widening ticket_prices.day_type on a dev database created by Hibernate before holiday prices existed. */
class DayTypeColumnUpgradeTest {

    /** The table as Hibernate 6 created it before HOLIDAY was added to DayType. */
    private static final String OLD_TABLE = """
            create table ticket_prices (
                price decimal(12,0) not null, id bigint not null auto_increment,
                day_type enum ('WEEKDAY','WEEKEND') not null, format enum ('THREE_D','TWO_D') not null,
                seat_type enum ('COUPLE','STANDARD','VIP') not null, time_slot enum ('BEFORE_17','FROM_17') not null,
                primary key (id))""";

    private static final String INSERT_HOLIDAY = "insert into ticket_prices (price, day_type, format, seat_type, time_slot) "
            + "values (99000, 'HOLIDAY', 'TWO_D', 'STANDARD', 'FROM_17')";

    private static DriverManagerDataSource dataSource(String url) {
        return new DriverManagerDataSource(url, "sa", "");
    }

    private static void execute(DriverManagerDataSource ds, String sql) throws SQLException {
        try (Connection connection = ds.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private void widensTheOldEnum(String url) throws Exception {
        DriverManagerDataSource ds = dataSource(url);
        execute(ds, OLD_TABLE);
        execute(ds, "insert into ticket_prices (price, day_type, format, seat_type, time_slot) "
                + "values (90000, 'WEEKEND', 'TWO_D', 'STANDARD', 'FROM_17')");
        assertThatThrownBy(() -> execute(ds, INSERT_HOLIDAY)).isInstanceOf(SQLException.class);

        DayTypeColumnUpgrade upgrade = new DayTypeColumnUpgrade(ds);
        assertThat(upgrade.upgrade()).isTrue();

        execute(ds, INSERT_HOLIDAY);
        try (Connection connection = ds.getConnection(); Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("select day_type from ticket_prices order by id")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString(1)).isEqualTo("WEEKEND"); // existing rows kept
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString(1)).isEqualTo("HOLIDAY");
        }
        assertThatThrownBy(() -> execute(ds, "insert into ticket_prices (price, day_type, format, seat_type, time_slot) "
                + "values (1, 'OTHER', 'TWO_D', 'STANDARD', 'FROM_17')")).isInstanceOf(SQLException.class);

        // Second start: nothing to do, nothing breaks.
        upgrade.upgrade();
        execute(ds, INSERT_HOLIDAY.replace("99000", "98000"));
    }

    @Test
    void widensTheEnumOnTheDevH2DatabaseInMySqlMode() throws Exception {
        widensTheOldEnum("jdbc:h2:mem:daytype-mysql;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
    }

    @Test
    void widensTheEnumOnPlainH2() throws Exception {
        widensTheOldEnum("jdbc:h2:mem:daytype-plain;DB_CLOSE_DELAY=-1");
    }

    @Test
    void leavesAWidenedEnumAlone() throws Exception {
        DriverManagerDataSource ds = dataSource("jdbc:h2:mem:daytype-done;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        execute(ds, OLD_TABLE.replace("('WEEKDAY','WEEKEND')", "('HOLIDAY','WEEKDAY','WEEKEND')"));

        assertThat(new DayTypeColumnUpgrade(ds).upgrade()).isFalse();
        execute(ds, INSERT_HOLIDAY);
    }

    @Test
    void leavesAVarcharColumnAndAMissingTableAlone() throws Exception {
        DriverManagerDataSource ds = dataSource("jdbc:h2:mem:daytype-varchar;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        assertThat(new DayTypeColumnUpgrade(ds).upgrade()).isFalse(); // no table yet

        execute(ds, "create table ticket_prices (id bigint primary key, day_type varchar(20) not null)");
        assertThat(new DayTypeColumnUpgrade(ds).upgrade()).isFalse();
        try (Connection connection = ds.getConnection(); ResultSet rs = connection.getMetaData()
                .getColumns(connection.getCatalog(), null, "ticket_prices", "day_type")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString("TYPE_NAME")).isIn("CHARACTER VARYING", "VARCHAR");
        }
    }
}
