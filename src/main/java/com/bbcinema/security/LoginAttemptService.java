package com.bbcinema.security;

import com.bbcinema.config.AppSecurityProperties;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Slows down password guessing. Counts failed logins per email (lower-cased) and per client address, and blocks
 * further attempts for a while once either count reaches its limit, even with the right password. Kept in memory
 * only: the counts are lost when the app restarts, which is acceptable for a single-instance deployment.
 *
 * <p>The email is counted whether or not an account exists, so the answer never tells which emails are registered.
 */
@Service
public class LoginAttemptService {

    public static final String BLOCKED_MESSAGE = "Bạn đã nhập sai quá nhiều lần. Vui lòng thử lại sau 15 phút.";

    /** Failures counted since {@code windowStart}; {@code lockedUntil} is set while the key is blocked. */
    private record Attempts(int failures, Instant windowStart, Instant lockedUntil) {

        boolean lockedAt(Instant now) {
            return lockedUntil != null && lockedUntil.isAfter(now);
        }
    }

    private final AppSecurityProperties.Login limits;
    private final Clock clock;
    private final Map<String, Attempts> accounts = new ConcurrentHashMap<>();
    private final Map<String, Attempts> addresses = new ConcurrentHashMap<>();

    public LoginAttemptService(AppSecurityProperties properties, Clock clock) {
        this.limits = properties.login();
        this.clock = clock;
    }

    /** True while the email or the client address is blocked. */
    public boolean isBlocked(String email, String clientIp) {
        Instant now = clock.instant();
        return locked(accounts, accountKey(email), now) || locked(addresses, clientIp, now);
    }

    /** Records a wrong password (or any refused credential check) for this email and address. */
    public void recordFailure(String email, String clientIp) {
        Instant now = clock.instant();
        fail(accounts, accountKey(email), limits.maxFailuresPerAccount(), now);
        if (clientIp != null) {
            fail(addresses, clientIp, limits.maxFailuresPerIp(), now);
        }
    }

    /** The password was right: the email starts again from zero. The address keeps its count. */
    public void recordSuccess(String email) {
        String key = accountKey(email);
        accounts.computeIfPresent(key, (k, v) -> v.lockedAt(clock.instant()) ? v : null);
    }

    /** Forgets everything (tests). */
    public void clear() {
        accounts.clear();
        addresses.clear();
    }

    /** Number of emails and addresses currently remembered (tests, monitoring). */
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

    static String accountKey(String email) {
        return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
    }

    private static boolean locked(Map<String, Attempts> map, String key, Instant now) {
        if (key == null) {
            return false;
        }
        Attempts attempts = map.get(key);
        return attempts != null && attempts.lockedAt(now);
    }

    private void fail(Map<String, Attempts> map, String key, int max, Instant now) {
        if (!map.containsKey(key) && map.size() >= limits.maxTrackedKeys()) {
            makeRoom(map, now);
        }
        map.compute(key, (k, v) -> {
            if (v != null && v.lockedAt(now)) {
                return v;
            }
            if (v == null || expired(v, now)) {
                v = new Attempts(0, now, null);
            }
            int failures = v.failures() + 1;
            if (failures >= max) {
                return new Attempts(0, now, now.plus(limits.lockDuration()));
            }
            return new Attempts(failures, v.windowStart(), null);
        });
    }

    /** Failures outside the window are forgotten (a lock that has ended counts as nothing). */
    private boolean expired(Attempts v, Instant now) {
        return !v.lockedAt(now) && !v.windowStart().plus(limits.window()).isAfter(now);
    }

    private void purge(Map<String, Attempts> map, Instant now) {
        map.entrySet().removeIf(e -> expired(e.getValue(), now));
    }

    /**
     * Keeps the map under its size limit: first drops stale counts, then counts that are not blocking anything,
     * and only as a last resort some blocks. Memory stays bounded even under a flood of different emails.
     */
    private void makeRoom(Map<String, Attempts> map, Instant now) {
        purge(map, now);
        int target = Math.max(0, limits.maxTrackedKeys() - Math.max(1, limits.maxTrackedKeys() / 10));
        if (map.size() <= target) {
            return;
        }
        Iterator<Map.Entry<String, Attempts>> entries = map.entrySet().iterator();
        while (map.size() > target && entries.hasNext()) {
            if (!entries.next().getValue().lockedAt(now)) {
                entries.remove();
            }
        }
        entries = map.entrySet().iterator();
        while (map.size() > target && entries.hasNext()) {
            entries.next();
            entries.remove();
        }
    }
}
