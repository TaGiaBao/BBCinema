package com.bbcinema.service;

import com.bbcinema.TestFixtures;
import com.bbcinema.entity.PointTransaction;
import com.bbcinema.entity.User;
import com.bbcinema.entity.enums.PointType;
import com.bbcinema.repository.PointTransactionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PointServiceTest {

    @Mock
    PointTransactionRepository repository;

    PointService service() {
        return new PointService(repository, TestFixtures.properties(), TestFixtures.clock());
    }

    @Test
    void earnsFivePercentInThousands() {
        assertThat(service().pointsEarnedFor(BigDecimal.valueOf(200_000))).isEqualTo(10);
        assertThat(service().pointsEarnedFor(BigDecimal.valueOf(219_000))).isEqualTo(10);
        assertThat(service().pointsEarnedFor(BigDecimal.valueOf(19_000))).isZero();
    }

    @Test
    void canRedeemUpToHalfTheOrderAndTheBalance() {
        User user = TestFixtures.user(1, null);
        user.setPoints(500);
        assertThat(service().maxRedeemable(user, BigDecimal.valueOf(100_000))).isEqualTo(50);
        user.setPoints(20);
        assertThat(service().maxRedeemable(user, BigDecimal.valueOf(100_000))).isEqualTo(20);
        assertThat(service().maxRedeemable(user, BigDecimal.ZERO)).isZero();
    }

    @Test
    void recordChangesBalanceAndKeepsHistory() {
        User user = TestFixtures.user(1, null);
        user.setPoints(10);

        service().record(user, null, PointType.EARN, 7, "test");

        assertThat(user.getPoints()).isEqualTo(17);
        ArgumentCaptor<PointTransaction> captor = ArgumentCaptor.forClass(PointTransaction.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getPoints()).isEqualTo(7);
        assertThat(captor.getValue().getCreatedAt()).isEqualTo(TestFixtures.NOW);
    }

    @Test
    void balanceNeverGoesNegative() {
        User user = TestFixtures.user(1, null);
        user.setPoints(3);
        service().record(user, null, PointType.REVERT, -10, "revert");
        assertThat(user.getPoints()).isZero();
    }

    @Test
    void zeroPointsAreNotRecorded() {
        service().record(TestFixtures.user(1, null), null, PointType.EARN, 0, "none");
        verify(repository, never()).save(any());
    }
}
