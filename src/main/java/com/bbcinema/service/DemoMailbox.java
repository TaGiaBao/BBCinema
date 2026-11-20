package com.bbcinema.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.Base64;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/**
 * For demos on a laptop with no mail account: keeps the last {@value #CAPACITY} e-mails the app sends, so they
 * can be shown at /demo/mailbox. Off unless app.demo-mailbox is true (profile h2); production refuses to start
 * with it on, because it shows password reset links to anyone.
 */
@Component
public class DemoMailbox {

    static final int CAPACITY = 50;

    public record DemoMail(long id, LocalDateTime sentAt, String to, String subject, String html) {
    }

    private final boolean enabled;
    private final Clock clock;
    private final Deque<DemoMail> mails = new ArrayDeque<>();
    private final AtomicLong ids = new AtomicLong();

    public DemoMailbox(@Value("${app.demo-mailbox:false}") boolean enabled, Clock clock) {
        this.enabled = enabled;
        this.clock = clock;
    }

    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Keeps a copy. Inline pictures (cid:name) are turned into data URLs, since a browser page cannot read
     * the attachments of a mail.
     */
    public void keep(String to, String subject, String html, Map<String, byte[]> inlinePngs) {
        if (!enabled) {
            return;
        }
        String page = html;
        for (Map.Entry<String, byte[]> png : inlinePngs.entrySet()) {
            page = page.replace("cid:" + png.getKey(),
                    "data:image/png;base64," + Base64.getEncoder().encodeToString(png.getValue()));
        }
        synchronized (mails) {
            mails.addFirst(new DemoMail(ids.incrementAndGet(), LocalDateTime.now(clock), to, subject, page));
            while (mails.size() > CAPACITY) {
                mails.removeLast();
            }
        }
    }

    /** Newest first. */
    public List<DemoMail> all() {
        synchronized (mails) {
            return List.copyOf(mails);
        }
    }

    public Optional<DemoMail> find(long id) {
        return all().stream().filter(m -> m.id() == id).findFirst();
    }
}
