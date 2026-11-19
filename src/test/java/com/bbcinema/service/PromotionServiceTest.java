package com.bbcinema.service;

import com.bbcinema.TestFixtures;
import com.bbcinema.dto.request.PromotionForm;
import com.bbcinema.entity.Promotion;
import com.bbcinema.exception.BusinessException;
import com.bbcinema.repository.PromotionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PromotionServiceTest {

    static final LocalDate TODAY = TestFixtures.NOW.toLocalDate();

    @Mock
    PromotionRepository repository;

    PromotionService service() {
        return new PromotionService(repository, TestFixtures.clock());
    }

    @Test
    void discountIsPercentRoundedDownToThousand() {
        Promotion p = TestFixtures.promotion("A", 10, null, null, TODAY, TODAY, null);
        assertThat(PromotionService.discountFor(p, BigDecimal.valueOf(199_000))).isEqualByComparingTo("19000");
    }

    @Test
    void discountIsCappedByMaximum() {
        Promotion p = TestFixtures.promotion("A", 50, 30_000L, null, TODAY, TODAY, null);
        assertThat(PromotionService.discountFor(p, BigDecimal.valueOf(500_000))).isEqualByComparingTo("30000");
    }

    @Test
    void discountNeverExceedsOrder() {
        Promotion p = TestFixtures.promotion("A", 100, null, null, TODAY, TODAY, null);
        assertThat(PromotionService.discountFor(p, BigDecimal.valueOf(60_000))).isEqualByComparingTo("60000");
    }

    @Test
    void rejectsExpiredCode() {
        Promotion p = TestFixtures.promotion("OLD", 10, null, null, TODAY.minusDays(10), TODAY.minusDays(1), null);
        assertThatThrownBy(() -> PromotionService.checkUsable(p, BigDecimal.valueOf(100_000), TODAY))
                .isInstanceOf(BusinessException.class).hasMessageContaining("thời gian");
    }

    @Test
    void rejectsCodeNotStartedYet() {
        Promotion p = TestFixtures.promotion("NEW", 10, null, null, TODAY.plusDays(1), TODAY.plusDays(5), null);
        assertThatThrownBy(() -> PromotionService.checkUsable(p, BigDecimal.valueOf(100_000), TODAY))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void rejectsUsedUpCode() {
        Promotion p = TestFixtures.promotion("LIMIT", 10, null, null, TODAY, TODAY, 2);
        p.setUsedCount(2);
        assertThatThrownBy(() -> PromotionService.checkUsable(p, BigDecimal.valueOf(100_000), TODAY))
                .isInstanceOf(BusinessException.class).hasMessageContaining("hết lượt");
    }

    @Test
    void rejectsSmallOrders() {
        Promotion p = TestFixtures.promotion("MIN", 10, null, 200_000L, TODAY, TODAY, null);
        assertThatThrownBy(() -> PromotionService.checkUsable(p, BigDecimal.valueOf(150_000), TODAY))
                .isInstanceOf(BusinessException.class).hasMessageContaining("200.000đ");
    }

    @Test
    void rejectsDisabledCode() {
        Promotion p = TestFixtures.promotion("OFF", 10, null, null, TODAY, TODAY, null);
        p.setActive(false);
        assertThatThrownBy(() -> PromotionService.checkUsable(p, BigDecimal.TEN, TODAY)).isInstanceOf(BusinessException.class);
    }

    @Test
    void findUsableIgnoresCase() {
        Promotion p = TestFixtures.promotion("BBCINEMA10", 10, null, null, TODAY, TODAY, null);
        when(repository.findByCodeIgnoreCase("bbcinema10")).thenReturn(Optional.of(p));
        assertThat(service().findUsable(" bbcinema10 ", BigDecimal.valueOf(100_000))).isSameAs(p);
    }

    @Test
    void unknownCode() {
        when(repository.findByCodeIgnoreCase(any())).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service().findUsable("NOPE", BigDecimal.TEN)).hasMessageContaining("không tồn tại");
    }

    @Test
    void saveRejectsDuplicateCodeAndBadDates() {
        PromotionForm form = new PromotionForm();
        form.setCode("dup");
        form.setDiscountPercent(10);
        form.setValidFrom(TODAY);
        form.setValidTo(TODAY.minusDays(1));
        assertThatThrownBy(() -> service().save(form)).hasMessageContaining("Ngày kết thúc");

        form.setValidTo(TODAY.plusDays(1));
        Promotion existing = TestFixtures.promotion("DUP", 5, null, null, TODAY, TODAY, null);
        existing.setId(99L);
        when(repository.findByCodeIgnoreCase("DUP")).thenReturn(Optional.of(existing));
        assertThatThrownBy(() -> service().save(form)).hasMessageContaining("đã tồn tại");
    }
}
