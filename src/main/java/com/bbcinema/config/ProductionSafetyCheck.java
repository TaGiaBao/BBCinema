package com.bbcinema.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Profiles;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Set;

/**
 * Stops the application when the prod profile is active but the configuration still has missing or
 * development values: the demo JWT key, a short key, the MySQL root account, a well-known password,
 * a localhost address.
 * <p>
 * It runs as soon as the configuration files are loaded, before any bean is created, so nothing
 * (Flyway, the connection pool) touches the database with an unsafe setup. Registered in
 * META-INF/spring.factories.
 */
public class ProductionSafetyCheck implements EnvironmentPostProcessor, Ordered {

    /** The development key from application.yml; anyone reading the repository knows it. */
    static final String DEV_JWT_SECRET = "Ymljb2luZW1hLWRldmVsb3BtZW50LXNlY3JldC1rZXktY2hhbmdlLW1lLTIwMjY=";
    static final int MIN_JWT_KEY_BYTES = 32;
    static final Set<String> WEAK_PASSWORDS = Set.of("", "root", "password", "123456", "admin", "bbcinema");

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        if (!environment.acceptsProfiles(Profiles.of("prod"))) {
            return;
        }
        List<String> problems = problems(
                read(environment, "app.jwt.secret"),
                read(environment, "spring.datasource.username"),
                read(environment, "spring.datasource.password"),
                read(environment, "app.base-url"));
        if ("true".equalsIgnoreCase(read(environment, "app.bank-qr.demo-confirm"))) {
            problems.add("DEMO_BANK_CONFIRM must be off: it lets anyone mark a bank transfer as received");
        }
        if ("true".equalsIgnoreCase(read(environment, "app.demo-mailbox"))) {
            problems.add("DEMO_MAILBOX must be off: the demo mailbox shows every e-mail, password reset links included");
        }
        if (!problems.isEmpty()) {
            throw new IllegalStateException("Unsafe production configuration (profile prod):\n - "
                    + String.join("\n - ", problems));
        }
    }

    /** After the config files are loaded (they are added by a post-processor with a higher priority). */
    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }

    /** The value, or null when it is missing (a ${NAME} placeholder with no value set). */
    private static String read(ConfigurableEnvironment environment, String key) {
        try {
            return environment.getProperty(key);
        } catch (IllegalArgumentException unresolvedPlaceholder) {
            return null;
        }
    }

    static List<String> problems(String jwtSecret, String dbUser, String dbPassword, String baseUrl) {
        List<String> problems = new ArrayList<>();
        if (jwtSecret == null || jwtSecret.isBlank() || DEV_JWT_SECRET.equals(jwtSecret)) {
            problems.add("JWT_SECRET must be set to your own key (openssl rand -base64 32)");
        } else if (keyBytes(jwtSecret) < MIN_JWT_KEY_BYTES) {
            problems.add("JWT_SECRET must be a Base64 key of at least " + MIN_JWT_KEY_BYTES + " bytes");
        }
        if (dbUser == null || dbUser.isBlank() || "root".equalsIgnoreCase(dbUser)) {
            problems.add("DB_USERNAME must be a dedicated account, not root (see database/create-app-user.sql)");
        }
        if (dbPassword == null || WEAK_PASSWORDS.contains(dbPassword.toLowerCase())) {
            problems.add("DB_PASSWORD must be a strong password, not a demo value");
        }
        if (baseUrl == null || baseUrl.isBlank() || baseUrl.contains("localhost")) {
            problems.add("APP_BASE_URL must be the public address of the site (used in e-mails and VNPay)");
        }
        return problems;
    }

    private static int keyBytes(String base64) {
        try {
            return Base64.getDecoder().decode(base64).length;
        } catch (IllegalArgumentException e) {
            return 0;
        }
    }
}
