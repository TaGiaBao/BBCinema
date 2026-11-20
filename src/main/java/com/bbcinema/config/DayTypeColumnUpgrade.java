package com.bbcinema.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Locale;

/**
 * Dev and demo databases only (Hibernate {@code ddl-auto: update}, Flyway off). Hibernate stores
 * {@code ticket_prices.day_type} as a native ENUM on MySQL and H2, and {@code update} never widens
 * an existing column, so a database created before holiday prices refuses 'HOLIDAY'. This adds the
 * value at start-up. Production gets the same change from the V17 migration, so this never runs there.
 * <p>
 * Safe to run on every start: it does nothing when the column is not an ENUM (or the table does
 * not exist) or already accepts HOLIDAY, and a failure is only logged.
 */
@Slf4j
@Component
@Order(0)
@Profile("!prod")
@ConditionalOnProperty(name = "spring.flyway.enabled", havingValue = "false", matchIfMissing = true)
public class DayTypeColumnUpgrade implements ApplicationRunner {

    static final String VALUES = "'HOLIDAY','WEEKDAY','WEEKEND'";
    /** MySQL, and H2 in MySQL mode (the dev H2 URL uses MODE=MySQL). */
    static final String MYSQL_SYNTAX = "ALTER TABLE ticket_prices MODIFY COLUMN day_type ENUM(" + VALUES + ") NOT NULL";
    /** H2 without MySQL mode. */
    static final String H2_SYNTAX = "ALTER TABLE ticket_prices ALTER COLUMN day_type ENUM(" + VALUES + ") NOT NULL";

    private final DataSource dataSource;

    public DayTypeColumnUpgrade(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public void run(ApplicationArguments args) {
        upgrade();
    }

    /** @return true when the column was widened by this call */
    boolean upgrade() {
        try (Connection connection = dataSource.getConnection()) {
            if (!needsHoliday(connection)) {
                return false;
            }
            try (Statement statement = connection.createStatement()) {
                try {
                    statement.execute(MYSQL_SYNTAX);
                } catch (SQLException mysqlSyntaxRefused) {
                    statement.execute(H2_SYNTAX);
                }
            }
            if (!connection.getAutoCommit()) {
                connection.commit();
            }
            log.info("ticket_prices.day_type now accepts HOLIDAY (database created before holiday prices)");
            return true;
        } catch (SQLException | RuntimeException e) {
            log.warn("Could not add HOLIDAY to ticket_prices.day_type, saving holiday prices may fail: {}", e.getMessage());
            return false;
        }
    }

    /** True when day_type is an ENUM column whose values do not include HOLIDAY. */
    private static boolean needsHoliday(Connection connection) throws SQLException {
        DatabaseMetaData meta = connection.getMetaData();
        String typeName = null;
        for (String[] names : new String[][]{{"ticket_prices", "day_type"}, {"TICKET_PRICES", "DAY_TYPE"}}) {
            try (ResultSet rs = meta.getColumns(connection.getCatalog(), null, names[0], names[1])) {
                if (rs.next()) {
                    typeName = rs.getString("TYPE_NAME");
                    break;
                }
            }
        }
        if (typeName == null || !typeName.toUpperCase(Locale.ROOT).startsWith("ENUM")) {
            return false; // no table yet, or a VARCHAR column that takes any value
        }
        return !acceptsHoliday(connection);
    }

    /**
     * Reads the allowed values where the database lists them (MySQL COLUMN_TYPE, H2 ENUM_VALUES).
     * When neither works it answers false: the ALTER that follows is harmless on a column that
     * already has the three values.
     */
    private static boolean acceptsHoliday(Connection connection) {
        String[] queries = {
                // MySQL
                "SELECT column_type FROM information_schema.columns WHERE table_schema = DATABASE()"
                        + " AND table_name = 'ticket_prices' AND column_name = 'day_type'",
                // H2 2.x
                "SELECT value_name FROM information_schema.enum_values WHERE LOWER(object_name) = 'ticket_prices'"
                        + " AND LOWER(enum_identifier) IN (SELECT LOWER(dtd_identifier) FROM information_schema.columns"
                        + " WHERE LOWER(table_name) = 'ticket_prices' AND LOWER(column_name) = 'day_type')"
        };
        for (String query : queries) {
            try (Statement statement = connection.createStatement(); ResultSet rs = statement.executeQuery(query)) {
                boolean any = false;
                while (rs.next()) {
                    any = true;
                    String value = rs.getString(1);
                    if (value != null && value.toUpperCase(Locale.ROOT).contains("HOLIDAY")) {
                        return true;
                    }
                }
                if (any) {
                    return false;
                }
            } catch (SQLException notThisDatabase) {
                // try the next dialect
            }
        }
        return false;
    }
}
