package com.bbcinema.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProductionSafetyCheckTest {

    private static final String GOOD_KEY = Base64.getEncoder().encodeToString(new byte[32]);

    @Test
    void acceptsDedicatedAccountAndOwnKey() {
        assertThat(ProductionSafetyCheck.problems(GOOD_KEY, "bbcinema_app", "S3cure-Long-Pass!", "https://bbcinema.example"))
                .isEmpty();
    }

    @Test
    void refusesDevelopmentValues() {
        assertThat(ProductionSafetyCheck.problems(ProductionSafetyCheck.DEV_JWT_SECRET, "root", "root", "http://localhost:8080"))
                .hasSize(4)
                .anyMatch(p -> p.startsWith("JWT_SECRET"))
                .anyMatch(p -> p.startsWith("DB_USERNAME"))
                .anyMatch(p -> p.startsWith("DB_PASSWORD"))
                .anyMatch(p -> p.startsWith("APP_BASE_URL"));
    }

    @Test
    void refusesShortOrMalformedKey() {
        String shortKey = Base64.getEncoder().encodeToString(new byte[16]);
        assertThat(ProductionSafetyCheck.problems(shortKey, "app", "S3cure-Long-Pass!", "https://x.vn"))
                .singleElement().asString().contains("at least 32 bytes");
        assertThat(ProductionSafetyCheck.problems("not base64 !!", "app", "S3cure-Long-Pass!", "https://x.vn"))
                .singleElement().asString().contains("at least 32 bytes");
    }

    @Test
    void stopsStartupOnlyWhenProdProfileIsActive() {
        ProductionSafetyCheck check = new ProductionSafetyCheck();
        MockEnvironment dev = new MockEnvironment().withProperty("spring.datasource.username", "root");
        check.postProcessEnvironment(dev, null); // no prod profile: nothing to check

        MockEnvironment prod = new MockEnvironment()
                .withProperty("app.jwt.secret", "${JWT_SECRET}") // placeholder with no value
                .withProperty("spring.datasource.username", "root")
                .withProperty("spring.datasource.password", "root")
                .withProperty("app.base-url", "http://localhost:8080");
        prod.setActiveProfiles("mysql", "prod");
        assertThatThrownBy(() -> check.postProcessEnvironment(prod, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JWT_SECRET")
                .hasMessageContaining("DB_USERNAME");
    }

    @Test
    void refusesDemoToolsInProduction() {
        MockEnvironment prod = new MockEnvironment()
                .withProperty("app.jwt.secret", GOOD_KEY)
                .withProperty("spring.datasource.username", "bbcinema_app")
                .withProperty("spring.datasource.password", "S3cure-Long-Pass!")
                .withProperty("app.base-url", "https://bbcinema.example")
                .withProperty("app.demo-mailbox", "true")
                .withProperty("app.bank-qr.demo-confirm", "true");
        prod.setActiveProfiles("mysql", "prod");
        assertThatThrownBy(() -> new ProductionSafetyCheck().postProcessEnvironment(prod, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DEMO_MAILBOX")
                .hasMessageContaining("DEMO_BANK_CONFIRM");
    }
}
