package com.bbcinema.security;

import com.bbcinema.config.AppSecurityProperties;
import com.bbcinema.exception.LoginBlockedException;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.BadCredentialsException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LoginAttemptServiceTest {

    /** A clock the test moves by hand, so lock expiry needs no sleeping. */
    static final class MovableClock extends Clock {
        private volatile Instant now = Instant.parse("2026-11-20T03:00:00Z");

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    private final MovableClock clock = new MovableClock();

    /** Accounts as the database would find them: "admin@test.vn" also under its accented variant (MySQL collation). */
    private static final Function<String, Optional<Long>> ACCOUNTS = email -> switch (email.toLowerCase()) {
        case "admin@test.vn", "ádmin@test.vn" -> Optional.of(1L);
        default -> Optional.empty();
    };

    private LoginAttemptService service(int perAccount, int perIp, int maxKeys) {
        var login = new AppSecurityProperties.Login(perAccount, perIp, Duration.ofMinutes(15), Duration.ofMinutes(15), maxKeys);
        return new LoginAttemptService(new AppSecurityProperties(false, false, login), clock, ACCOUNTS);
    }

    private LoginAttemptService defaults() {
        return service(5, 20, 10_000);
    }

    /** One wrong password; returns true if it was even allowed to be checked. */
    private static boolean fail(LoginAttemptService s, String email, String ip) {
        try (LoginAttemptService.Attempt attempt = s.begin(email, ip)) {
            attempt.failed();
            return true;
        } catch (LoginBlockedException e) {
            return false;
        }
    }

    private static boolean succeed(LoginAttemptService s, String email, String ip) {
        try (LoginAttemptService.Attempt attempt = s.begin(email, ip)) {
            return attempt.succeeded();
        } catch (LoginBlockedException e) {
            return false;
        }
    }

    @Test
    void blocksAnAccountAfterFiveFailuresWhateverTheCase() {
        LoginAttemptService s = defaults();
        for (int i = 0; i < 4; i++) {
            fail(s, "Victim@Test.vn", "10.0.0." + i);
        }
        assertThat(s.isBlocked("victim@test.vn", "10.0.0.99")).isFalse();
        fail(s, " VICTIM@test.vn ", "10.0.0.50");
        assertThat(s.isBlocked("victim@test.vn", "10.0.0.99")).isTrue();
        assertThat(s.isBlocked("other@test.vn", "10.0.0.99")).isFalse();
        assertThatThrownBy(() -> s.begin("victim@test.vn", "10.0.0.99")).isInstanceOf(LoginBlockedException.class);
    }

    @Test
    void variantsOfAnExistingAccountShareOneCounter() {
        LoginAttemptService s = defaults();
        // The database finds the same account for both spellings, so they count together.
        fail(s, "admin@test.vn", "1.1.1.1");
        fail(s, "ádmin@test.vn", "1.1.1.1");
        fail(s, "ADMIN@test.vn", "1.1.1.1");
        fail(s, "Ádmin@test.vn", "1.1.1.1");
        fail(s, "admin@test.vn", "1.1.1.1");
        assertThat(s.isBlocked("admin@test.vn", "2.2.2.2")).isTrue();
        assertThat(s.isBlocked("ádmin@test.vn", "2.2.2.2")).isTrue();
        // A right password through either spelling resets the same counter.
        LoginAttemptService t = defaults();
        for (int i = 0; i < 4; i++) {
            fail(t, "ádmin@test.vn", "1.1.1.1");
        }
        assertThat(succeed(t, "admin@test.vn", "1.1.1.1")).isTrue();
        for (int i = 0; i < 4; i++) {
            fail(t, "ádmin@test.vn", "1.1.1.1");
        }
        assertThat(t.isBlocked("admin@test.vn", "2.2.2.2")).isFalse();
    }

    @Test
    void unknownEmailsAreNormalizedBeforeCounting() {
        assertThat(LoginAttemptService.normalize(" Ｇhost​@Tést.vn﻿ ")).isEqualTo("ghost@test.vn");
        LoginAttemptService s = defaults();
        fail(s, "ghost@test.vn", "1.1.1.1");
        fail(s, "GHOST@test.vn", "1.1.1.1");
        fail(s, "gh​ost@test.vn", "1.1.1.1");
        fail(s, "ｇhost@test.vn", "1.1.1.1");
        fail(s, "ghóst@test.vn", "1.1.1.1");
        assertThat(s.isBlocked("ghost@test.vn", "2.2.2.2")).isTrue();
    }

    @Test
    void successResetsTheAccountCount() {
        LoginAttemptService s = defaults();
        for (int i = 0; i < 4; i++) {
            fail(s, "a@test.vn", "1.1.1.1");
        }
        assertThat(succeed(s, "A@test.vn", "1.1.1.1")).isTrue();
        for (int i = 0; i < 4; i++) {
            fail(s, "a@test.vn", "1.1.1.1");
        }
        assertThat(s.isBlocked("a@test.vn", "2.2.2.2")).isFalse();
    }

    @Test
    void aBlockedAccountCannotEvenTryTheRightPassword() {
        LoginAttemptService s = defaults();
        for (int i = 0; i < 5; i++) {
            fail(s, "a@test.vn", "1.1.1.1");
        }
        assertThat(succeed(s, "a@test.vn", "2.2.2.2")).isFalse();
        assertThat(s.isBlocked("a@test.vn", "2.2.2.2")).isTrue();
    }

    @Test
    void blockEndsAfterTheLockDuration() {
        LoginAttemptService s = defaults();
        for (int i = 0; i < 5; i++) {
            fail(s, "a@test.vn", "1.1.1.1");
        }
        clock.advance(Duration.ofMinutes(14));
        assertThat(s.isBlocked("a@test.vn", "2.2.2.2")).isTrue();
        clock.advance(Duration.ofMinutes(1));
        assertThat(s.isBlocked("a@test.vn", "2.2.2.2")).isFalse();
        // Counting starts again from zero after the block.
        fail(s, "a@test.vn", "1.1.1.1");
        assertThat(s.isBlocked("a@test.vn", "2.2.2.2")).isFalse();
    }

    @Test
    void oldFailuresAreForgottenAfterTheWindow() {
        LoginAttemptService s = defaults();
        for (int i = 0; i < 4; i++) {
            fail(s, "a@test.vn", "1.1.1.1");
        }
        clock.advance(Duration.ofMinutes(16));
        fail(s, "a@test.vn", "1.1.1.1");
        assertThat(s.isBlocked("a@test.vn", "2.2.2.2")).isFalse();
    }

    @Test
    void blocksAnAddressAfterTwentyFailuresOnDifferentEmails() {
        LoginAttemptService s = defaults();
        for (int i = 0; i < 19; i++) {
            fail(s, "user" + i + "@test.vn", "9.9.9.9");
        }
        assertThat(s.isBlocked("fresh@test.vn", "9.9.9.9")).isFalse();
        fail(s, "user19@test.vn", "9.9.9.9");
        assertThat(s.isBlocked("fresh@test.vn", "9.9.9.9")).isTrue();
        assertThat(s.isBlocked("fresh@test.vn", "8.8.8.8")).isFalse();
        // A correct login does not clear the address (it is not even allowed).
        assertThat(succeed(s, "fresh@test.vn", "9.9.9.9")).isFalse();
        assertThat(s.isBlocked("fresh@test.vn", "9.9.9.9")).isTrue();
        clock.advance(Duration.ofMinutes(15));
        assertThat(s.isBlocked("fresh@test.vn", "9.9.9.9")).isFalse();
    }

    @Test
    void anAddressBlockLeavesTheAccountCounterUntouched() {
        LoginAttemptService s = service(5, 1, 100);
        fail(s, "a@test.vn", "9.9.9.9");
        assertThatThrownBy(() -> s.begin("b@test.vn", "9.9.9.9")).isInstanceOf(LoginBlockedException.class);
        // The refused attempt did not keep a reservation on b@test.vn.
        for (int i = 0; i < 4; i++) {
            fail(s, "b@test.vn", "7.7.7." + i);
        }
        assertThat(s.isBlocked("b@test.vn", "6.6.6.6")).isFalse();
    }

    @Test
    void closingWithoutAnOutcomeReleasesTheReservation() {
        LoginAttemptService s = service(1, 100, 100);
        s.begin("a@test.vn", "1.1.1.1").close(); // e.g. the database failed during the check
        assertThat(s.isBlocked("a@test.vn", "1.1.1.1")).isFalse();
        assertThat(s.trackedKeys()).isZero();
        assertThat(fail(s, "a@test.vn", "1.1.1.1")).isTrue();
        assertThat(s.isBlocked("a@test.vn", "1.1.1.1")).isTrue();
    }

    @Test
    void overlongEmailsAreRefusedAndCountedOnlyAgainstTheAddress() {
        LoginAttemptService s = service(5, 3, 100);
        String huge = "a".repeat(10_000) + "@test.vn";
        for (int i = 0; i < 3; i++) {
            assertThatThrownBy(() -> s.begin(huge, "5.5.5.5")).isInstanceOf(BadCredentialsException.class);
        }
        assertThat(s.trackedKeys()).isEqualTo(1); // only the address
        assertThat(s.isBlocked("someone@test.vn", "5.5.5.5")).isTrue();
        assertThatThrownBy(() -> s.begin(huge, "5.5.5.5")).isInstanceOf(LoginBlockedException.class);
        // Exactly 254 characters is still a normal email.
        String longest = "b".repeat(254 - "@test.vn".length()) + "@test.vn";
        assertThat(fail(s, longest, "6.6.6.6")).isTrue();
        // Something that only becomes too long after normalization (one character expands to 18) is refused too.
        String expands = "ﷺ".repeat(20) + "@test.vn";
        assertThatThrownBy(() -> s.begin(expands, "6.6.6.7")).isInstanceOf(BadCredentialsException.class);
    }

    @Test
    void memoryStaysBoundedAndKeepsActiveBlocks() {
        LoginAttemptService s = service(2, 1_000_000, 100);
        fail(s, "locked@test.vn", null);
        fail(s, "locked@test.vn", null);
        for (int i = 0; i < 1_000; i++) {
            fail(s, "spray" + i + "@test.vn", null);
        }
        assertThat(s.trackedKeys()).isLessThanOrEqualTo(100);
        assertThat(s.isBlocked("locked@test.vn", null)).isTrue();
    }

    @Test
    void evictionDropsTheOldestUnlockedEntriesFirst() {
        LoginAttemptService s = service(5, 1_000_000, 10);
        fail(s, "old@test.vn", null);
        for (int i = 0; i < 9; i++) {
            clock.advance(Duration.ofSeconds(1));
            fail(s, "newer" + i + "@test.vn", null);
        }
        clock.advance(Duration.ofSeconds(1));
        fail(s, "newest@test.vn", null); // full: the oldest goes first
        // old@test.vn was forgotten; the others keep their failure.
        for (int i = 0; i < 4; i++) {
            fail(s, "old@test.vn", null);
        }
        assertThat(s.isBlocked("old@test.vn", null)).isFalse();
        for (int i = 0; i < 4; i++) {
            fail(s, "newer8@test.vn", null);
        }
        assertThat(s.isBlocked("newer8@test.vn", null)).isTrue();
    }

    @Test
    void whenEverythingIsBlockedNewKeysAreNotTrackedButStillCountOnTheAddress() {
        LoginAttemptService s = service(1, 3, 3);
        for (int i = 0; i < 3; i++) {
            fail(s, "locked" + i + "@test.vn", "10.0.0." + i); // three blocked accounts, map full
        }
        // A new email cannot be tracked (no lock is ever dropped), but its address is.
        assertThat(fail(s, "fresh@test.vn", "10.0.9.9")).isTrue();
        for (int i = 0; i < 3; i++) {
            assertThat(s.isBlocked("locked" + i + "@test.vn", "1.2.3.4")).isTrue();
        }
        assertThat(s.isBlocked("fresh@test.vn", "1.2.3.4")).isFalse();
        fail(s, "fresh2@test.vn", "10.0.9.9");
        fail(s, "fresh3@test.vn", "10.0.9.9");
        assertThat(s.isBlocked("anyone@test.vn", "10.0.9.9")).isTrue();
    }

    @Test
    void purgeDropsStaleEntries() {
        LoginAttemptService s = defaults();
        fail(s, "a@test.vn", "1.1.1.1");
        for (int i = 0; i < 5; i++) {
            fail(s, "b@test.vn", "1.1.1.2");
        }
        clock.advance(Duration.ofMinutes(10));
        s.purgeExpired();
        assertThat(s.trackedKeys()).isEqualTo(4);
        clock.advance(Duration.ofMinutes(6));
        s.purgeExpired();
        assertThat(s.trackedKeys()).isZero();
    }

    // ---------- concurrency

    @Test
    void parallelWrongPasswordsGetNoMoreGuessesThanTheLimit() throws Exception {
        LoginAttemptService s = defaults();
        int threads = 30;
        AtomicInteger passwordChecks = new AtomicInteger();
        AtomicInteger refused = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch allAsked = new CountDownLatch(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                String ip = "10.9.0." + i;
                futures.add(pool.submit(() -> {
                    start.await();
                    LoginAttemptService.Attempt attempt;
                    try {
                        attempt = s.begin("victim@test.vn", ip);
                    } catch (LoginBlockedException e) {
                        refused.incrementAndGet();
                        allAsked.countDown();
                        return null;
                    }
                    allAsked.countDown();
                    try {
                        passwordChecks.incrementAndGet();
                        // The slow BCrypt check: every request is in flight at the same time.
                        allAsked.await(10, TimeUnit.SECONDS);
                        attempt.failed();
                    } finally {
                        attempt.close();
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> f : futures) {
                f.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(passwordChecks.get()).isEqualTo(5);
        assertThat(refused.get()).isEqualTo(threads - 5);
        assertThat(s.isBlocked("victim@test.vn", "1.2.3.4")).isTrue();
    }

    @Test
    void aRightPasswordRacingWithWrongOnesCannotExceedTheLimit() throws Exception {
        for (int round = 0; round < 20; round++) {
            LoginAttemptService s = defaults();
            for (int i = 0; i < 3; i++) {
                fail(s, "victim@test.vn", "10.8.0.1");
            }
            int threads = 12;
            AtomicInteger passwordChecks = new AtomicInteger();
            Map<String, Boolean> logins = new java.util.concurrent.ConcurrentHashMap<>();
            CountDownLatch start = new CountDownLatch(1);
            CountDownLatch allAsked = new CountDownLatch(threads);
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            try {
                List<Future<?>> futures = new ArrayList<>();
                for (int i = 0; i < threads; i++) {
                    boolean right = i == 0;
                    String ip = "10.8.1." + i;
                    futures.add(pool.submit(() -> {
                        start.await();
                        LoginAttemptService.Attempt attempt;
                        try {
                            attempt = s.begin("victim@test.vn", ip);
                        } catch (LoginBlockedException e) {
                            allAsked.countDown(); // refused before the check
                            return null;
                        }
                        allAsked.countDown();
                        try (attempt) {
                            passwordChecks.incrementAndGet();
                            allAsked.await(10, TimeUnit.SECONDS);
                            if (right) {
                                logins.put("right", attempt.succeeded());
                            } else {
                                attempt.failed();
                            }
                        }
                        return null;
                    }));
                }
                start.countDown();
                for (Future<?> f : futures) {
                    f.get(30, TimeUnit.SECONDS);
                }
            } finally {
                pool.shutdownNow();
            }
            // 3 earlier failures leave 2 checks, whichever requests win them.
            assertThat(passwordChecks.get()).isLessThanOrEqualTo(2);
            if (Boolean.TRUE.equals(logins.get("right"))) {
                // The right password got one of the two checks; at most one wrong guess ran beside it: no block.
                assertThat(s.isBlocked("victim@test.vn", "1.2.3.4")).isFalse();
            } else {
                // Two wrong guesses took both checks and reached the limit; the right one was refused.
                assertThat(logins).doesNotContainKey("right");
                assertThat(s.isBlocked("victim@test.vn", "1.2.3.4")).isTrue();
            }
        }
    }
}
