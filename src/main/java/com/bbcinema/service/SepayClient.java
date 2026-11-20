package com.bbcinema.service;

import com.bbcinema.config.BankQrProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/** Reads the money received by the cinema's bank account from the SePay API (v2, list transactions). */
@Component
@RequiredArgsConstructor
public class SepayClient {

    private static final DateTimeFormatter SEPAY_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final BankQrProperties properties;
    private final ObjectMapper objectMapper;
    private final RestClient restClient = RestClient.create();

    public record Transaction(String id, BigDecimal amountIn, String content, String referenceNumber) {
    }

    record Page(List<Transaction> transactions, boolean hasMore) {
    }

    /** Money received since the given time, oldest first (up to 5 pages of 100). */
    public List<Transaction> incomingSince(LocalDateTime since) {
        List<Transaction> all = new ArrayList<>();
        for (int page = 1; page <= 5; page++) {
            String body = restClient.get()
                    .uri(properties.sepayApiUrl() + "?transfer_type=in&transaction_date_sort=asc&per_page=100"
                            + "&page={page}&transaction_date_from={from}", page, since.format(SEPAY_TIME))
                    .header("Authorization", "Bearer " + properties.sepayApiToken().trim())
                    .retrieve()
                    .body(String.class);
            Page result = parse(body);
            all.addAll(result.transactions());
            if (!result.hasMore()) {
                break;
            }
        }
        return all;
    }

    /** Parses a v2 answer: {"status":"success","data":[...],"meta":{"pagination":{"has_more":false}}}. */
    Page parse(String json) {
        try {
            JsonNode root = objectMapper.readTree(json);
            List<Transaction> list = new ArrayList<>();
            for (JsonNode t : root.path("data")) {
                BigDecimal amountIn = new BigDecimal(t.path("amount_in").asText("0"));
                if (amountIn.signum() > 0) {
                    list.add(new Transaction(t.path("id").asText(), amountIn,
                            t.path("transaction_content").asText(""), t.path("reference_number").asText("")));
                }
            }
            return new Page(list, root.path("meta").path("pagination").path("has_more").asBoolean(false));
        } catch (Exception e) {
            throw new IllegalStateException("Unexpected SePay answer", e);
        }
    }
}
