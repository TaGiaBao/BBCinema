package com.bbcinema.service;

import com.bbcinema.TestFixtures;
import com.bbcinema.dto.response.MemberCard;
import com.bbcinema.entity.User;
import com.bbcinema.entity.enums.Role;
import com.bbcinema.exception.BusinessException;
import com.bbcinema.exception.NotFoundException;
import com.bbcinema.repository.BookingRepository;
import com.bbcinema.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionOperations;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MemberCodeServiceTest {

    UserRepository userRepository;
    BookingRepository bookingRepository;
    Deque<String> codes;
    MemberCodeService service;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        bookingRepository = mock(BookingRepository.class);
        codes = new ArrayDeque<>();
        MembershipService membership = new MembershipService(bookingRepository, TestFixtures.properties(), TestFixtures.clock());
        service = new MemberCodeService(userRepository, membership, TestFixtures.clock(),
                () -> codes.isEmpty() ? "BBZZZZZZZZ" : codes.pop(), TransactionOperations.withoutTransaction());
    }

    private User member(long id) {
        User user = TestFixtures.user(id, LocalDate.of(2000, 1, 1));
        user.setPhone("0901234567");
        user.setEmailVerifiedAt(java.time.LocalDateTime.of(2026, 1, 1, 0, 0));
        return user;
    }

    @Test
    void drawsAgainWhenTheCodeIsTaken() {
        codes.addAll(List.of("BBAAAAAAAA", "BBBBBBBBBB", "BBCCCCCCCC"));
        when(userRepository.existsByMemberCode("BBAAAAAAAA")).thenReturn(true);
        when(userRepository.existsByMemberCode("BBBBBBBBBB")).thenReturn(true);
        when(userRepository.existsByMemberCode("BBCCCCCCCC")).thenReturn(false);

        User user = member(1);
        assertThat(service.assignIfMissing(user)).isEqualTo("BBCCCCCCCC");
        assertThat(user.getMemberCode()).isEqualTo("BBCCCCCCCC");
        verify(userRepository, times(3)).existsByMemberCode(anyString());
    }

    @Test
    void givesUpAfterTooManyCollisions() {
        when(userRepository.existsByMemberCode(anyString())).thenReturn(true);
        assertThatThrownBy(() -> service.assignIfMissing(member(1))).isInstanceOf(IllegalStateException.class);
        verify(userRepository, times(MemberCodeService.MAX_ATTEMPTS)).existsByMemberCode(anyString());
    }

    @Test
    void realGeneratorGivesBbCodes() {
        MemberCodeService real = new MemberCodeService(userRepository,
                new MembershipService(bookingRepository, TestFixtures.properties(), TestFixtures.clock()), TestFixtures.clock(),
                mock(org.springframework.transaction.PlatformTransactionManager.class));
        User user = member(1);
        assertThat(real.assignIfMissing(user)).matches("BB[A-HJKMNP-Z2-9]{8}");
    }

    @Test
    void keepsAnExistingCodeAndSkipsInternalAccounts() {
        User user = member(1);
        user.setMemberCode("BBKEEPME22");
        assertThat(service.assignIfMissing(user)).isEqualTo("BBKEEPME22");

        for (Role role : List.of(Role.STAFF, Role.MANAGER, Role.ADMIN)) {
            User staff = member(2);
            staff.setRole(role);
            assertThat(service.assignIfMissing(staff)).isNull();
            assertThat(staff.getMemberCode()).isNull();
        }
        verify(userRepository, never()).existsByMemberCode(anyString());
    }

    @Test
    void backfillWritesCodesWithATargetedUpdateOnly() {
        codes.addAll(List.of("BBAAAAAAAA", "BBBBBBBBBB"));
        when(userRepository.findIdsByRoleWithoutMemberCode(Role.USER)).thenReturn(List.of(1L, 2L));
        when(userRepository.setMemberCodeIfMissing(anyLong(), anyString())).thenReturn(1);

        assertThat(service.backfillMissing()).isEqualTo(2);
        verify(userRepository).setMemberCodeIfMissing(1L, "BBAAAAAAAA");
        verify(userRepository).setMemberCodeIfMissing(2L, "BBBBBBBBBB");
        verify(userRepository, never()).save(any());
        verify(userRepository, never()).saveAndFlush(any());
    }

    @Test
    void backfillRetriesOnAUniqueClashAndSkipsMembersAlreadyFilled() {
        codes.addAll(List.of("BBAAAAAAAA", "BBBBBBBBBB", "BBCCCCCCCC"));
        when(userRepository.findIdsByRoleWithoutMemberCode(Role.USER)).thenReturn(List.of(1L, 2L));
        // Member 1: someone took BBAAAAAAAA between the check and the update; the next code works.
        when(userRepository.setMemberCodeIfMissing(1L, "BBAAAAAAAA"))
                .thenThrow(new org.springframework.dao.DataIntegrityViolationException("uk_users_member_code"));
        when(userRepository.setMemberCodeIfMissing(1L, "BBBBBBBBBB")).thenReturn(1);
        // Member 2 got a code elsewhere meanwhile: 0 rows, nothing to count.
        when(userRepository.setMemberCodeIfMissing(2L, "BBCCCCCCCC")).thenReturn(0);

        assertThat(service.backfillMissing()).isEqualTo(1);
        verify(userRepository, times(3)).setMemberCodeIfMissing(anyLong(), anyString());
    }

    @Test
    void backfillKeepsGoingWhenOneMemberFails() {
        when(userRepository.findIdsByRoleWithoutMemberCode(Role.USER)).thenReturn(List.of(1L, 2L));
        when(userRepository.setMemberCodeIfMissing(eq(1L), anyString()))
                .thenThrow(new org.springframework.dao.DataIntegrityViolationException("clash"));
        when(userRepository.setMemberCodeIfMissing(eq(2L), anyString())).thenReturn(1);
        assertThat(service.backfillMissing()).isEqualTo(1);
        verify(userRepository, times(MemberCodeService.MAX_ATTEMPTS)).setMemberCodeIfMissing(eq(1L), anyString());
    }

    @Test
    void ensureCodeUsesTheTargetedUpdateAndIgnoresInternalAccounts() {
        User user = member(1);
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(userRepository.setMemberCodeIfMissing(1L, "BBZZZZZZZZ")).thenReturn(1);
        assertThat(service.ensureCode(1L)).isEqualTo("BBZZZZZZZZ");
        assertThat(user.getMemberCode()).isEqualTo("BBZZZZZZZZ");
        verify(userRepository, never()).save(any());

        // Filled by another request meanwhile: read back what is stored.
        User other = member(2);
        when(userRepository.findById(2L)).thenReturn(Optional.of(other));
        when(userRepository.setMemberCodeIfMissing(eq(2L), anyString())).thenReturn(0);
        when(userRepository.findMemberCodeById(2L)).thenReturn("BBOTHER222");
        assertThat(service.ensureCode(2L)).isEqualTo("BBOTHER222");

        // A former member now on staff keeps no usable code (no QR, no profile card).
        User staff = member(3);
        staff.setRole(Role.STAFF);
        staff.setMemberCode("BBOLDCODE2");
        when(userRepository.findById(3L)).thenReturn(Optional.of(staff));
        assertThat(service.ensureCode(3L)).isNull();
        assertThat(service.assignIfMissing(staff)).isNull();
    }

    @Test
    void lookupByCodeAcceptsLowerCaseAndSpaces() {
        User user = member(1);
        user.setMemberCode("BB7KQ2MXPA");
        when(userRepository.findByMemberCode("BB7KQ2MXPA")).thenReturn(Optional.of(user));

        MemberCard card = service.lookupForCounter(" bb7k q2mx-pa ", null);
        assertThat(card.matchedBy()).isEqualTo("CODE");
        assertThat(card.memberCode()).isEqualTo("BB7KQ2MXPA");
        assertThat(card.maskedPhone()).isEqualTo("09xx xxx 567");
        assertThat(card.tier()).isEqualTo("STANDARD");
        assertThat(card.discountPercent()).isZero();
        assertThat(card.age()).isEqualTo(26); // born 2000-01-01, today 2026-11-19
    }

    @Test
    void ageIsWorkedOutOnTheShowtimeDate() {
        User user = member(1);
        user.setBirthDate(LocalDate.of(2010, 11, 25)); // 15 today (2026-11-19), 16 on the 25th
        user.setMemberCode("BB7KQ2MXPA");
        when(userRepository.findByMemberCode("BB7KQ2MXPA")).thenReturn(Optional.of(user));
        assertThat(service.lookupForCounter("BB7KQ2MXPA", null).age()).isEqualTo(15);
        assertThat(service.lookupForCounter("BB7KQ2MXPA", LocalDate.of(2026, 11, 26)).age()).isEqualTo(16);
    }

    @Test
    void phoneMatchIsMarkedForNameConfirmation() {
        User user = member(1);
        user.setMemberCode("BB7KQ2MXPA");
        when(userRepository.findByPhoneAndRole("0901234567", Role.USER)).thenReturn(List.of(user));
        MemberCard card = service.lookupForCounter("0901234567", null);
        assertThat(card.matchedBy()).isEqualTo("PHONE");
        assertThat(card.fullName()).isEqualTo("User 1");
        assertThat(card.memberCode()).isEqualTo("BB7KQ2MXPA");
    }

    @Test
    void unverifiedMembersAreNeitherFoundNorSold() {
        User user = member(1);
        user.setEmailVerifiedAt(null);
        user.setMemberCode("BB7KQ2MXPA");
        when(userRepository.findByMemberCode("BB7KQ2MXPA")).thenReturn(Optional.of(user));
        when(userRepository.findByPhoneAndRole("0901234567", Role.USER)).thenReturn(List.of(user));
        assertThatThrownBy(() -> service.findForCounter("BB7KQ2MXPA")).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.findForCounter("0901234567")).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.memberForSale("BB7KQ2MXPA")).isInstanceOf(BusinessException.class);
    }

    @Test
    void masksMemberCodesForPaper() {
        assertThat(service.maskCode("BB7KQ2MXPA")).isEqualTo("BB\u2022\u2022\u2022\u2022\u2022XPA");
        assertThat(service.maskCode(null)).isNull();
    }

    @Test
    void lookupFindsOnlyEnabledMembers() {
        User staff = member(1);
        staff.setRole(Role.STAFF);
        staff.setMemberCode("BBSTAFF222");
        when(userRepository.findByMemberCode("BBSTAFF222")).thenReturn(Optional.of(staff));
        assertThatThrownBy(() -> service.findForCounter("BBSTAFF222")).isInstanceOf(NotFoundException.class);

        User locked = member(2);
        locked.setEnabled(false);
        locked.setMemberCode("BBKKD22222");
        when(userRepository.findByMemberCode("BBKKD22222")).thenReturn(Optional.of(locked));
        assertThatThrownBy(() -> service.findForCounter("BBKKD22222")).isInstanceOf(NotFoundException.class);
    }

    @Test
    void lookupByPhoneRefusesASharedNumber() {
        User a = member(1);
        User b = member(2);
        when(userRepository.findByPhoneAndRole("0901234567", Role.USER)).thenReturn(List.of(a, b));
        assertThatThrownBy(() -> service.findForCounter("090 123 4567"))
                .isInstanceOf(BusinessException.class).hasMessageContaining("mã thành viên");

        b.setEnabled(false); // a locked account does not count
        assertThat(service.findForCounter("0901234567").user()).isSameAs(a);
    }

    @Test
    void lookupRejectsTextThatIsNeitherCodeNorPhone() {
        assertThatThrownBy(() -> service.findForCounter("")).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.findForCounter("K7M2QX9P")).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.findForCounter(null)).isInstanceOf(BusinessException.class);
    }

    @Test
    void memberForSaleRejectsStaffLockedUnknownAndMalformedCodes() {
        User staff = member(1);
        staff.setRole(Role.ADMIN);
        when(userRepository.findByMemberCode("BBADMN2222")).thenReturn(Optional.of(staff));
        User locked = member(2);
        locked.setEnabled(false);
        when(userRepository.findByMemberCode("BBKKD22222")).thenReturn(Optional.of(locked));
        when(userRepository.findByMemberCode("BBNNNNNNNN")).thenReturn(Optional.empty());
        User ok = member(4);
        when(userRepository.findByMemberCode("BB7KQ2MXPA")).thenReturn(Optional.of(ok));

        for (String bad : new String[] {"BBADMN2222", "BBKKD22222", "BBNNNNNNNN", "4", "", null}) {
            assertThatThrownBy(() -> service.memberForSale(bad)).isInstanceOf(BusinessException.class)
                    .hasMessage(MemberCodeService.INVALID_MEMBER);
        }
        assertThat(service.memberForSale(" bb7kq2mxpa ")).isSameAs(ok);
    }

    @Test
    void cardShowsTierDiscountAndUnknownAge() {
        User user = member(1);
        user.setBirthDate(null);
        user.setPhone(null);
        when(bookingRepository.sumPaidByUserSince(eq(1L), any())).thenReturn(BigDecimal.valueOf(3_500_000));
        MemberCard card = service.cardOf(user, MemberCodeService.MatchedBy.CODE, null);
        assertThat(card.tier()).isEqualTo("GOLD");
        assertThat(card.discountPercent()).isEqualTo(10);
        assertThat(card.age()).isNull();
        assertThat(card.maskedPhone()).isNull();
    }

    @Test
    void masksPhoneNumbers() {
        assertThat(MemberCodeService.maskPhone("0901234567")).isEqualTo("09xx xxx 567");
        assertThat(MemberCodeService.maskPhone("09012345678")).isEqualTo("09xx xxxx 678");
        assertThat(MemberCodeService.maskPhone(null)).isNull();
    }
}
