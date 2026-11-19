package com.bbcinema.service;

import com.bbcinema.TestFixtures;
import com.bbcinema.config.AppProperties;
import com.bbcinema.entity.User;
import com.bbcinema.entity.enums.MemberTier;
import com.bbcinema.repository.BookingRepository;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MembershipServiceTest {

    private final AppProperties.Membership m = AppProperties.Membership.DEFAULT;

    @Test
    void tierFollowsSpendingThresholds() {
        assertThat(MembershipService.tierFor(BigDecimal.ZERO, m)).isEqualTo(MemberTier.STANDARD);
        assertThat(MembershipService.tierFor(BigDecimal.valueOf(999_999), m)).isEqualTo(MemberTier.STANDARD);
        assertThat(MembershipService.tierFor(BigDecimal.valueOf(1_000_000), m)).isEqualTo(MemberTier.SILVER);
        assertThat(MembershipService.tierFor(BigDecimal.valueOf(3_000_000), m)).isEqualTo(MemberTier.GOLD);
        assertThat(MembershipService.tierFor(BigDecimal.valueOf(9_000_000), m)).isEqualTo(MemberTier.DIAMOND);
    }

    @Test
    void statusTellsHowMuchMoreIsNeededAndDiscountRoundsDown() {
        BookingRepository repo = mock(BookingRepository.class);
        User user = TestFixtures.user(7, LocalDate.of(2000, 1, 1));
        when(repo.sumPaidByUserSince(eq(7L), any())).thenReturn(BigDecimal.valueOf(3_200_000));
        MembershipService service = new MembershipService(repo, TestFixtures.properties(), TestFixtures.clock());

        MembershipService.Status status = service.status(user);

        assertThat(status.tier()).isEqualTo(MemberTier.GOLD);
        assertThat(status.discountPercent()).isEqualTo(10);
        assertThat(status.next()).isEqualTo(MemberTier.DIAMOND);
        assertThat(status.toNext()).isEqualByComparingTo("2800000");
        assertThat(service.discountOn(MemberTier.GOLD, BigDecimal.valueOf(123_456))).isEqualByComparingTo("12345");
        assertThat(service.discountOn(MemberTier.STANDARD, BigDecimal.valueOf(123_456))).isEqualByComparingTo("0");
    }

    @Test
    void noSpendingMeansStandardTier() {
        BookingRepository repo = mock(BookingRepository.class);
        MembershipService service = new MembershipService(repo, TestFixtures.properties(), TestFixtures.clock());
        assertThat(service.tierOf(TestFixtures.user(8, LocalDate.of(2000, 1, 1)))).isEqualTo(MemberTier.STANDARD);
        assertThat(service.tierOf(null)).isEqualTo(MemberTier.STANDARD);
    }
}
