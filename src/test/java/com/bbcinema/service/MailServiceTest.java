package com.bbcinema.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MailServiceTest {

    @Test
    void reservedExampleDomainsAreNeverMailed() {
        assertThat(MailService.isReservedAddress("lan.pham@example.com")).isTrue();
        assertThat(MailService.isReservedAddress("a@mail.EXAMPLE.org")).isTrue();
        assertThat(MailService.isReservedAddress("a@cinema.test")).isTrue();
        assertThat(MailService.isReservedAddress("user@bbcinema.vn")).isFalse();
        assertThat(MailService.isReservedAddress("member1@test.vn")).isFalse();
        assertThat(MailService.isReservedAddress("someone@notexample.com")).isFalse();
    }
}
