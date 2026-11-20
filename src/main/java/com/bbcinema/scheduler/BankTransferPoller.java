package com.bbcinema.scheduler;

import com.bbcinema.config.BankQrProperties;
import com.bbcinema.service.BankTransferService;
import com.bbcinema.service.SepayClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * Every few seconds, while a bank transfer is waiting for its money, asks SePay for the transfers received since
 * the oldest one started and confirms the bookings they name. Polling (rather than a webhook) also works on a
 * laptop the internet cannot reach. Does nothing without a SePay token or when nothing is waiting.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BankTransferPoller {

    private final BankQrProperties properties;
    private final BankTransferService bankTransferService;
    private final SepayClient sepayClient;

    @Scheduled(fixedDelayString = "${app.bank-qr.poll-interval-ms:5000}", initialDelay = 10_000)
    public void poll() {
        if (!properties.isAutomatic()) {
            return;
        }
        LocalDateTime oldest = bankTransferService.oldestWaiting();
        if (oldest == null) {
            return;
        }
        try {
            // Two minutes earlier, in case the bank's clock and ours differ a little.
            for (SepayClient.Transaction t : sepayClient.incomingSince(oldest.minusMinutes(2))) {
                if (bankTransferService.handleIncoming(t.content(), t.amountIn(), "SePay " + t.referenceNumber())) {
                    log.info("Bank transfer {} paid a booking", t.referenceNumber());
                }
            }
        } catch (Exception e) {
            log.warn("Could not read transfers from SePay: {}", e.getMessage());
        }
    }
}
