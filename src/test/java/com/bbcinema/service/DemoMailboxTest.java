package com.bbcinema.service;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DemoMailboxTest {

    private final Clock clock = Clock.fixed(Instant.parse("2026-11-20T03:00:00Z"), ZoneId.of("Asia/Ho_Chi_Minh"));

    @Test
    void keepsNothingWhenOff() {
        DemoMailbox mailbox = new DemoMailbox(false, clock);
        mailbox.keep("a@test.vn", "Vé", "<p>hi</p>", Map.of());
        assertThat(mailbox.all()).isEmpty();
    }

    @Test
    void inlinePicturesBecomeDataUrlsSoTheBrowserCanShowThem() {
        DemoMailbox mailbox = new DemoMailbox(true, clock);
        mailbox.keep("a@test.vn", "Vé", "<img src=\"cid:qr\">", Map.of("qr", new byte[]{1, 2, 3}));
        DemoMailbox.DemoMail mail = mailbox.all().get(0);
        assertThat(mail.html()).isEqualTo("<img src=\"data:image/png;base64,AQID\">");
        assertThat(mail.sentAt()).isEqualTo("2026-11-20T10:00");
        assertThat(mailbox.find(mail.id())).contains(mail);
    }

    @Test
    void keepsTheNewestFiftyNewestFirst() {
        DemoMailbox mailbox = new DemoMailbox(true, clock);
        for (int i = 1; i <= 60; i++) {
            mailbox.keep("a@test.vn", "Mail " + i, "", Map.of());
        }
        assertThat(mailbox.all()).hasSize(DemoMailbox.CAPACITY);
        assertThat(mailbox.all().get(0).subject()).isEqualTo("Mail 60");
        assertThat(mailbox.all().get(49).subject()).isEqualTo("Mail 11");
    }
}
