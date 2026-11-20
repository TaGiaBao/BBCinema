package com.bbcinema.config;

import com.bbcinema.service.MemberCodeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Members who signed up before member codes existed get one at start-up, so staff can find them at the counter
 * straight away. Members created later get theirs at sign-up; the member page also fills a missing one.
 */
@Slf4j
@Component
@Order(2)
@RequiredArgsConstructor
public class MemberCodeBackfill implements ApplicationRunner {

    private final MemberCodeService memberCodeService;

    @Override
    public void run(ApplicationArguments args) {
        try {
            memberCodeService.backfillMissing();
        } catch (RuntimeException e) {
            // Not worth stopping the cinema for: a member without a code gets one when opening the member page.
            log.warn("Member code backfill failed, will retry on the next start: {}", e.getMessage());
        }
    }
}
