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
        service = new MemberCodeService(userRepository, membership, TestFixtures.clock(), () -> codes.isEmpty() ? "BBZZZZZZZZ" : codes.pop());
    }

    private User member(long id) {
        User user = TestFixtures.user(id, LocalDate.of(2000, 1, 1));
        user.setPhone("0901234567");
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
                new MembershipService(bookingRepository, TestFixtures.properties(), TestFixtures.clock()), TestFixtures.clock());
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
    void backfillGivesEveryCodelessMemberADifferentCode() {
        User a = member(1);
        User b = member(2);
        codes.addAll(List.of("BBAAAAAAAA", "BBBBBBBBBB"));
        when(userRepository.findByRoleAndMemberCodeIsNull(Role.USER)).thenReturn(List.of(a, b));

        assertThat(service.backfillMissing()).isEqualTo(2);
        assertThat(a.getMemberCode()).isEqualTo("BBAAAAAAAA");
        assertThat(b.getMemberCode()).isEqualTo("BBBBBBBBBB");
        verify(userRepository).saveAndFlush(a);
        verify(userRepository).saveAndFlush(b);
    }

    @Test
    void lookupByCodeAcceptsLowerCaseAndSpaces() {
        User user = member(1);
        user.setMemberCode("BB7KQ2MXPA");
        when(userRepository.findByMemberCode("BB7KQ2MXPA")).thenReturn(Optional.of(user));

        MemberCard card = service.lookupForCounter(" bb7k q2mx-pa ");
        assertThat(card.id()).isEqualTo(1L);
        assertThat(card.memberCode()).isEqualTo("BB7KQ2MXPA");
        assertThat(card.maskedPhone()).isEqualTo("09xx xxx 567");
        assertThat(card.tier()).isEqualTo("STANDARD");
        assertThat(card.discountPercent()).isZero();
        assertThat(card.age()).isEqualTo(26); // born 2000-01-01, today 2026-11-19
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
        assertThat(service.findForCounter("0901234567")).isSameAs(a);
    }

    @Test
    void lookupRejectsTextThatIsNeitherCodeNorPhone() {
        assertThatThrownBy(() -> service.findForCounter("")).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.findForCounter("K7M2QX9P")).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.findForCounter(null)).isInstanceOf(BusinessException.class);
    }

    @Test
    void memberForSaleRejectsStaffLockedAndUnknownAccounts() {
        User staff = member(1);
        staff.setRole(Role.ADMIN);
        when(userRepository.findById(1L)).thenReturn(Optional.of(staff));
        User locked = member(2);
        locked.setEnabled(false);
        when(userRepository.findById(2L)).thenReturn(Optional.of(locked));
        when(userRepository.findById(3L)).thenReturn(Optional.empty());
        User ok = member(4);
        when(userRepository.findById(4L)).thenReturn(Optional.of(ok));

        assertThatThrownBy(() -> service.memberForSale(1L)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.memberForSale(2L)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.memberForSale(3L)).isInstanceOf(BusinessException.class);
        assertThat(service.memberForSale(4L)).isSameAs(ok);
    }

    @Test
    void cardShowsTierDiscountAndUnknownAge() {
        User user = member(1);
        user.setBirthDate(null);
        user.setPhone(null);
        when(bookingRepository.sumPaidByUserSince(eq(1L), any())).thenReturn(BigDecimal.valueOf(3_500_000));
        MemberCard card = service.cardOf(user);
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
