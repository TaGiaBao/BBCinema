package com.bbcinema.scheduler;

import com.bbcinema.service.BirthdayRewardService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Gives members their birthday codes every morning (08:00 in app.timezone by default), and once when the application
 * starts, since a demo laptop is rarely running at 08:00. Tests turn both off (cron "-") and call the service directly.
 */
@Slf4j
@Component
public class BirthdayRewardScheduler {

    private final BirthdayRewardService birthdayRewardService;
    private final boolean runAtStartup;

    public BirthdayRewardScheduler(BirthdayRewardService birthdayRewardService,
                                   @Value("${app.birthday-reward.run-at-startup:true}") boolean runAtStartup) {
        this.birthdayRewardService = birthdayRewardService;
        this.runAtStartup = runAtStartup;
    }

    @Scheduled(cron = "${app.birthday-reward.cron:0 0 8 * * *}", zone = "${app.timezone:Asia/Ho_Chi_Minh}")
    public void issueDaily() {
        issue();
    }

    @EventListener(ApplicationReadyEvent.class)
    public void issueAtStartup() {
        if (runAtStartup) {
            issue();
        }
    }

    private void issue() {
        try {
            birthdayRewardService.issueDue();
        } catch (RuntimeException e) {
            log.warn("Birthday codes were not given: {}", e.getMessage());
        }
    }
}
