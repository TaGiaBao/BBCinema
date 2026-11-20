package com.bbcinema.security;

import com.bbcinema.config.AppSecurityProperties;
import com.bbcinema.entity.User;
import com.bbcinema.exception.LoginBlockedException;
import com.bbcinema.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.stereotype.Service;

import java.text.Normalizer;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * Slows down password guessing. Counts failed logins per account and per client address, and blocks further
 * attempts for a while once either count reaches its limit, even with the right password. Kept in memory only:
 * the counts are lost when the app restarts, which is acceptable for a single-instance deployment.
 *
 * <p>Usage: {@link #begin} reserves one attempt for the account and the address <em>before</em> the password is
 * checked (refused when blocked, or when the failures plus the checks already running reach the limit), then the
 * caller reports {@link Attempt#failed()} or {@link Attempt#succeeded()} and always {@link Attempt#close()}s it.
 * Because the reservation is taken atomically, parallel requests can never check more passwords than the limit.
 *
 * <p>The account key is the id of the account the email signs in to, looked up exactly as authentication does,
 * so variants the database treats as the same email (case, accents on MySQL) share one counter. An email without
 * an account is counted under its normalized text, so the answer never tells which emails are registered.
 */
@Service
public class LoginAttemptService {

    public static final String BLOCKED_MESSAGE = "Bạn đã nhập sai quá nhiều lần. Vui lòng thử lại sau 15 phút.";

    /** Longest email address allowed (RFC 5321); anything longer is refused without being remembered. */
    public static final int MAX_EMAIL_LENGTH = 254;

    /** Control, format (zero-width...) and combining characters, and the invisible Hangul fillers. */
    private static final String EMAIL_PREFIX = "email:";

    private static final Pattern IGNORABLE = Pattern.compile("[\\p{Cc}\\p{Cf}\\p{Mn}\\p{Me}\\u115F\\u1160\\u3164\\uFFA0]");

    /**
     * Counts of one key. {@code inFlight} is the number of password checks running right now; {@code lockedUntil}
     * is set while the key is blocked.
     */
    private record Entry(int failures, int inFlight, Instant windowStart, Instant lockedUntil) {

        boolean lockedAt(Instant now) {
            return lockedUntil != null && lockedUntil.isAfter(now);
        }

        Entry withInFlight(int n) {
            return new Entry(failures, Math.max(0, n), windowStart, lockedUntil);
        }

        boolean empty(Instant now) {
            return failures == 0 && inFlight == 0 && !lockedAt(now);
        }
    }

    private final AppSecurityProperties.Login limits;
    private final Clock clock;
    private final Function<String, Optional<Long>> accountLookup;
    private final Map<String, Entry> accounts = new ConcurrentHashMap<>();
    private final Map<String, Entry> addresses = new ConcurrentHashMap<>();

    @Autowired
    public LoginAttemptService(AppSecurityProperties properties, Clock clock, UserRepository users) {
        this(properties, clock, email -> users.findByEmailIgnoreCase(email).map(User::getId));
    }

    /** @param accountLookup finds the id of the account an email signs in to (the same lookup as authentication) */
    LoginAttemptService(AppSecurityProperties properties, Clock clock, Function<String, Optional<Long>> accountLookup) {
        this.limits = properties.login();
        this.clock = clock;
        this.accountLookup = accountLookup;
    }

    /**
     * Reserves one attempt for this email and address, before the password is checked.
     *
     * @throws LoginBlockedException   the account or the address is blocked, or already has as many checks running
     *                                 as it has attempts left
     * @throws BadCredentialsException the email is longer than any real address: refused without checking, and
     *                                 counted against the address only
     */
    public Attempt begin(String email, String clientIp) {
        Instant now = clock.instant();
        String key = email == null || email.strip().length() > MAX_EMAIL_LENGTH ? null : accountKey(email);
        if (key == null || key.startsWith(EMAIL_PREFIX) && key.length() - EMAIL_PREFIX.length() > MAX_EMAIL_LENGTH) {
            if (clientIp != null && reserve(addresses, clientIp, limits.maxFailuresPerIp(), now)) {
                fail(addresses, clientIp, limits.maxFailuresPerIp(), now);
            }
            throw new BadCredentialsException("Email too long");
        }
        boolean accountTracked = reserve(accounts, key, limits.maxFailuresPerAccount(), now);
        boolean addressTracked;
        try {
            addressTracked = clientIp != null && reserve(addresses, clientIp, limits.maxFailuresPerIp(), now);
        } catch (LoginBlockedException e) {
            if (accountTracked) {
                release(accounts, key, now);
            }
            throw e;
        }
        return new Attempt(accountTracked ? key : null, addressTracked ? clientIp : null);
    }

    /** True while the account of this email or the client address is blocked. */
    public boolean isBlocked(String email, String clientIp) {
        Instant now = clock.instant();
        return locked(accounts, accountKey(email), now) || locked(addresses, clientIp, now);
    }

    /** Forgets everything (tests). */
    public void clear() {
        accounts.clear();
        addresses.clear();
    }

    /** Number of accounts and addresses currently remembered (tests, monitoring). */
    public int trackedKeys() {
        return accounts.size() + addresses.size();
    }

    /** Drops counts that no longer matter, so the maps only hold recent activity. */
    @Scheduled(fixedDelayString = "${app.security.login.cleanup-interval-ms:300000}", initialDelay = 300_000)
    public void purgeExpired() {
        Instant now = clock.instant();
        purge(accounts, now);
        purge(addresses, now);
    }

    /**
     * The key an email is counted under: "id:" plus the account id when the email signs in to an account, else
     * "email:" plus the email normalized like {@link #normalize}.
     */
    String accountKey(String email) {
        if (email == null) {
            return EMAIL_PREFIX;
        }
        String trimmed = email.trim();
        if (!trimmed.isEmpty()) {
            Optional<Long> id = accountLookup.apply(trimmed);
            if (id.isPresent()) {
                return "id:" + id.get();
            }
        }
        return EMAIL_PREFIX + normalize(email);
    }

    /**
     * Folds the variants of one email together: compatibility forms (full-width letters...), accents, invisible
     * and zero-width characters, surrounding spaces and case.
     */
    static String normalize(String email) {
        String s = Normalizer.normalize(email, Normalizer.Form.NFKC);
        s = Normalizer.normalize(s, Normalizer.Form.NFD);
        s = IGNORABLE.matcher(s).replaceAll("");
        return Normalizer.normalize(s, Normalizer.Form.NFC).strip().toLowerCase(Locale.ROOT);
    }

    // ---------- one login attempt

    /** One reserved attempt. Report the outcome once, then close it (closing without an outcome just releases it). */
    public final class Attempt implements AutoCloseable {

        private final String accountKey;
        private final String address;
        private boolean done;

        private Attempt(String accountKey, String address) {
            this.accountKey = accountKey;
            this.address = address;
        }

        /** The password was wrong. Returns true when the account or the address is blocked now. */
        public boolean failed() {
            Instant now = clock.instant();
            if (!done) {
                done = true;
                if (accountKey != null) {
                    fail(accounts, accountKey, limits.maxFailuresPerAccount(), now);
                }
                if (address != null) {
                    fail(addresses, address, limits.maxFailuresPerIp(), now);
                }
            }
            return blockedNow(now);
        }

        /**
         * The password was right: the account starts again from zero (the address keeps its count). Returns false
         * when a block was set meanwhile, in which case the login must be refused.
         */
        public boolean succeeded() {
            Instant now = clock.instant();
            boolean blocked = blockedNow(now);
            if (!done) {
                done = true;
                if (accountKey != null) {
                    accounts.computeIfPresent(accountKey, (k, v) -> {
                        Entry e = v.withInFlight(v.inFlight() - 1);
                        if (!e.lockedAt(now)) {
                            e = new Entry(0, e.inFlight(), now, null);
                        }
                        return e.empty(now) ? null : e;
                    });
                }
                if (address != null) {
                    release(addresses, address, now);
                }
            }
            return !blocked;
        }

        /** Releases the reservation if no outcome was reported (e.g. an unexpected error). */
        @Override
        public void close() {
            if (!done) {
                done = true;
                Instant now = clock.instant();
                if (accountKey != null) {
                    release(accounts, accountKey, now);
                }
                if (address != null) {
                    release(addresses, address, now);
                }
            }
        }

        private boolean blockedNow(Instant now) {
            return locked(accounts, accountKey, now) || locked(addresses, address, now);
        }
    }

    // ---------- counting

    private static boolean locked(Map<String, Entry> map, String key, Instant now) {
        if (key == null) {
            return false;
        }
        Entry e = map.get(key);
        return e != null && e.lockedAt(now);
    }

    /**
     * Atomically takes one attempt for the key. Returns false when the key cannot be tracked (the map is full of
     * blocked keys): the attempt then goes ahead without being counted under this key.
     */
    private boolean reserve(Map<String, Entry> map, String key, int max, Instant now) {
        if (!map.containsKey(key) && map.size() >= limits.maxTrackedKeys() && !makeRoom(map, now)) {
            return false;
        }
        boolean[] refused = {false};
        map.compute(key, (k, v) -> {
            Entry e = current(v, now);
            if (e.lockedAt(now) || e.failures() + e.inFlight() >= max) {
                refused[0] = true;
                return v == null ? null : e;
            }
            return e.withInFlight(e.inFlight() + 1);
        });
        if (refused[0]) {
            throw new LoginBlockedException();
        }
        return true;
    }

    /** Turns a reservation into a failure, blocking the key when it reaches the limit. */
    private void fail(Map<String, Entry> map, String key, int max, Instant now) {
        map.compute(key, (k, v) -> {
            Entry c = current(v, now);
            Entry e = c.withInFlight(c.inFlight() - 1);
            if (e.lockedAt(now)) {
                return e;
            }
            int failures = e.failures() + 1;
            if (failures >= max) {
                return new Entry(0, e.inFlight(), now, now.plus(limits.lockDuration()));
            }
            return new Entry(failures, e.inFlight(), e.windowStart(), null);
        });
    }

    /** Gives back a reservation without counting anything. */
    private void release(Map<String, Entry> map, String key, Instant now) {
        map.computeIfPresent(key, (k, v) -> {
            Entry e = v.withInFlight(v.inFlight() - 1);
            return e.empty(now) ? null : e;
        });
    }

    /** The entry as it stands now: failures outside the window are forgotten, running checks are kept. */
    private Entry current(Entry v, Instant now) {
        if (v == null) {
            return new Entry(0, 0, now, null);
        }
        if (expired(v, now)) {
            return new Entry(0, v.inFlight(), now, null);
        }
        return v;
    }

    /** Failures outside the window are forgotten (a block that has ended counts as nothing). */
    private boolean expired(Entry v, Instant now) {
        return !v.lockedAt(now) && !v.windowStart().plus(limits.window()).isAfter(now);
    }

    private void purge(Map<String, Entry> map, Instant now) {
        map.entrySet().removeIf(e -> e.getValue().inFlight() == 0 && expired(e.getValue(), now));
    }

    /**
     * Makes room for a new key: drops stale counts, then the oldest counts that block nothing and have no check
     * running. Active blocks are never dropped. Returns false when there is still no room (everything is blocked).
     */
    private boolean makeRoom(Map<String, Entry> map, Instant now) {
        purge(map, now);
        int max = limits.maxTrackedKeys();
        if (map.size() < max) {
            return true;
        }
        int target = Math.max(0, max - Math.max(1, max / 10));
        List<Map.Entry<String, Entry>> evictable = new ArrayList<>();
        for (Map.Entry<String, Entry> e : map.entrySet()) {
            if (!e.getValue().lockedAt(now) && e.getValue().inFlight() == 0) {
                evictable.add(e);
            }
        }
        evictable.sort(Comparator.comparing(e -> e.getValue().windowStart()));
        for (Map.Entry<String, Entry> e : evictable) {
            if (map.size() <= target) {
                break;
            }
            map.remove(e.getKey(), e.getValue());
        }
        return map.size() < max;
    }
}
