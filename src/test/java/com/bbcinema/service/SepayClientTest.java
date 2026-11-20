package com.bbcinema.service;

import com.bbcinema.config.BankQrProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class SepayClientTest {

    private final SepayClient client = new SepayClient(
            new BankQrProperties("MB", "", "0123", "A", "token", "https://example.invalid", 5000, false), new ObjectMapper());

    @Test
    void readsMoneyReceivedAndSkipsMoneySent() {
        String json = """
                {"status":"success","data":[
                  {"id":"a1","transaction_date":"2026-11-20 10:00:00","account_number":"0123","transfer_type":"in",
                   "amount_in":120000,"amount_out":0,"transaction_content":"MBVCB.123.BBC JF5XSY24.CT tu NGUYEN VAN A",
                   "reference_number":"FT26324ABC","code":null},
                  {"id":"a2","transfer_type":"out","amount_in":0,"amount_out":50000,"transaction_content":"Rut tien",
                   "reference_number":"FT2"}],
                 "meta":{"pagination":{"total":2,"per_page":100,"current_page":1,"last_page":1,"has_more":false}}}
                """;
        SepayClient.Page page = client.parse(json);
        assertThat(page.hasMore()).isFalse();
        assertThat(page.transactions()).singleElement().satisfies(t -> {
            assertThat(t.id()).isEqualTo("a1");
            assertThat(t.amountIn()).isEqualByComparingTo(new BigDecimal("120000"));
            assertThat(t.referenceNumber()).isEqualTo("FT26324ABC");
            assertThat(BankTransferService.bookingCodeIn(t.content())).contains("JF5XSY24");
        });
    }

    @Test
    void findsTheBookingCodeHoweverTheBankWritesTheMessage() {
        assertThat(BankTransferService.bookingCodeIn("BBC JF5XSY24")).contains("JF5XSY24");
        assertThat(BankTransferService.bookingCodeIn("bbcjf5xsy24 chuyen tien")).contains("JF5XSY24");
        assertThat(BankTransferService.bookingCodeIn("IBFT BBC  JF5XSY24-GD 123")).contains("JF5XSY24");
        assertThat(BankTransferService.bookingCodeIn("BBC JF5XSY245")).isEmpty(); // 9 characters: not a code
        assertThat(BankTransferService.bookingCodeIn("tien nha thang 11")).isEmpty();
        assertThat(BankTransferService.bookingCodeIn(null)).isEmpty();
    }
}
