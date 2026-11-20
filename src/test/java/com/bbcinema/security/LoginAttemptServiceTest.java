package com.bbcinema.security;

import com.bbcinema.config.AppSecurityProperties;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class LoginAttemptServiceTest {

    /** A clock the test moves by hand, so lock expiry needs no sleeping. */
    static final class MovableClock extends Clock {
        private Instant now = Instant.parse("2026-11-20T03:00:00Z");

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    private final MovableClock clock = new MovableClock();

    private LoginAttemptService service(int perAccount, int perIp, int maxKeys) {
        var login = new AppSecurityProperties.Login(perAccount, perIp, Duration.ofMinutes(15), Duration.ofMinutes(15), maxKeys);
        return new LoginAttemptService(new AppSecurityProperties(false, false, login), clock);
    }

    private LoginAttemptService defaults() {
        return service(5, 20, 10_000);
    }

    @Test
    void blocksAnAccountAfterFiveFailuresWhateverTheCase() {
        LoginAttemptService s = defaults();
        for (int i = 0; i < 4; i++) {
            s.recordFailure("Victim@Test.vn", "10.0.0." + i);
        }
        assertThat(s.isBlocked("victim@test.vn", "10.0.0.99")).isFalse();
        s.recordFailure(" VICTIM@test.vn ", "10.0.0.50");
        assertThat(s.isBlocked("victim@test.vn", "10.0.0.99")).isTrue();
        assertThat(s.isBlocked("other@test.vn", "10.0.0.99")).isFalse();
    }

    @Test
    void successResetsTheAccountCount() {
        LoginAttemptService s = defaults();
        for (int i = 0; i < 4; i++) {
            s.recordFailure("a@test.vn", "1.1.1.1");
        }
        s.recordSuccess("A@test.vn");
        for (int i = 0; i < 4; i++) {
            s.recordFailure("a@test.vn", "1.1.1.1");
        }
        assertThat(s.isBlocked("a@test.vn", "2.2.2.2")).isFalse();
    }

    @Test
    void successDoesNotLiftAnActiveBlock() {
        LoginAttemptService s = defaults();
        for (int i = 0; i < 5; i++) {
            s.recordFailure("a@test.vn", "1.1.1.1");
        }
        s.recordSuccess("a@test.vn");
        assertThat(s.isBlocked("a@test.vn", "2.2.2.2")).isTrue();
    }

    @Test
    void blockEndsAfterTheLockDuration() {
        LoginAttemptService s = defaults();
        for (int i = 0; i < 5; i++) {
            s.recordFailure("a@test.vn", "1.1.1.1");
        }
        clock.advance(Duration.ofMinutes(14));
        assertThat(s.isBlocked("a@test.vn", "2.2.2.2")).isTrue();
        clock.advance(Duration.ofMinutes(1));
        assertThat(s.isBlocked("a@test.vn", "2.2.2.2")).isFalse();
        // Counting starts again from zero after the block.
        s.recordFailure("a@test.vn", "1.1.1.1");
        assertThat(s.isBlocked("a@test.vn", "2.2.2.2")).isFalse();
    }

    @Test
    void oldFailuresAreForgottenAfterTheWindow() {
        LoginAttemptService s = defaults();
        for (int i = 0; i < 4; i++) {
            s.recordFailure("a@test.vn", "1.1.1.1");
        }
        clock.advance(Duration.ofMinutes(16));
        s.recordFailure("a@test.vn", "1.1.1.1");
        assertThat(s.isBlocked("a@test.vn", "2.2.2.2")).isFalse();
    }

    @Test
    void blocksAnAddressAfterTwentyFailuresOnDifferentEmails() {
        LoginAttemptService s = defaults();
        for (int i = 0; i < 19; i++) {
            s.recordFailure("user" + i + "@test.vn", "9.9.9.9");
        }
        assertThat(s.isBlocked("fresh@test.vn", "9.9.9.9")).isFalse();
        s.recordFailure("user19@test.vn", "9.9.9.9");
        assertThat(s.isBlocked("fresh@test.vn", "9.9.9.9")).isTrue();
        assertThat(s.isBlocked("fresh@test.vn", "8.8.8.8")).isFalse();
        // A correct login does not clear the address.
        s.recordSuccess("fresh@test.vn");
        assertThat(s.isBlocked("fresh@test.vn", "9.9.9.9")).isTrue();
        clock.advance(Duration.ofMinutes(15));
        assertThat(s.isBlocked("fresh@test.vn", "9.9.9.9")).isFalse();
    }

    @Test
    void memoryStaysBoundedAndKeepsActiveBlocks() {
        LoginAttemptService s = service(2, 1_000_000, 100);
        s.recordFailure("locked@test.vn", null);
        s.recordFailure("locked@test.vn", null);
        for (int i = 0; i < 1_000; i++) {
            s.recordFailure("spray" + i + "@test.vn", null);
        }
        assertThat(s.trackedKeys()).isLessThanOrEqualTo(100);
        assertThat(s.isBlocked("locked@test.vn", null)).isTrue();
    }

    @Test
    void purgeDropsStaleEntries() {
        LoginAttemptService s = defaults();
        s.recordFailure("a@test.vn", "1.1.1.1");
        for (int i = 0; i < 5; i++) {
            s.recordFailure("b@test.vn", "1.1.1.2");
        }
        clock.advance(Duration.ofMinutes(10));
        s.purgeExpired();
        assertThat(s.trackedKeys()).isEqualTo(4);
        clock.advance(Duration.ofMinutes(6));
        s.purgeExpired();
        assertThat(s.trackedKeys()).isZero();
    }
}
