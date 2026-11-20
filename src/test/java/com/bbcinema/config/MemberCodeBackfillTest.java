package com.bbcinema.config;

import com.bbcinema.service.MemberCodeService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.*;

class MemberCodeBackfillTest {

    @Test
    void aFailingBackfillDoesNotStopTheApplication() {
        MemberCodeService service = mock(MemberCodeService.class);
        when(service.backfillMissing()).thenThrow(new IllegalStateException("database is read-only"));

        assertThatCode(() -> new MemberCodeBackfill(service).run(new DefaultApplicationArguments()))
                .doesNotThrowAnyException();
        verify(service).backfillMissing();
    }
}
