package com.bbcinema.service;

import com.bbcinema.dto.response.MemberCard;
import com.bbcinema.entity.User;
import com.bbcinema.entity.enums.MemberTier;
import com.bbcinema.entity.enums.Role;
import com.bbcinema.exception.BusinessException;
import com.bbcinema.exception.NotFoundException;
import com.bbcinema.repository.UserRepository;
import com.bbcinema.util.CodeGenerator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

/**
 * Member codes: the "BB..." code (and its QR) a member shows at the counter so staff can attach the sale to the
 * member's account, giving the tier discount and points as for online bookings.
 */
@Slf4j
@Service
@Transactional(readOnly = true)
public class MemberCodeService {

    /** Give up after this many collisions in a row; with 31^8 codes that only happens if the generator is broken. */
    static final int MAX_ATTEMPTS = 10;

    private final UserRepository userRepository;
    private final MembershipService membershipService;
    private final Clock clock;
    private final Supplier<String> generator;

    @Autowired
    public MemberCodeService(UserRepository userRepository, MembershipService membershipService, Clock clock) {
        this(userRepository, membershipService, clock, CodeGenerator::memberCode);
    }

    MemberCodeService(UserRepository userRepository, MembershipService membershipService, Clock clock,
                      Supplier<String> generator) {
        this.userRepository = userRepository;
        this.membershipService = membershipService;
        this.clock = clock;
        this.generator = generator;
    }

    // ------------------------------------------------------------------ codes

    /** A code no account uses yet; draws again when the random code is already taken. */
    String newUniqueCode() {
        for (int i = 0; i < MAX_ATTEMPTS; i++) {
            String code = generator.get();
            if (!userRepository.existsByMemberCode(code)) {
                return code;
            }
        }
        throw new IllegalStateException("Could not find a free member code after " + MAX_ATTEMPTS + " attempts");
    }

    /**
     * Gives a member account its code if it has none (the caller saves the user). Staff, managers and admins
     * get no code. Returns the user's code, or null for internal accounts.
     */
    public String assignIfMissing(User user) {
        if (user.getRole() != Role.USER) {
            return user.getMemberCode();
        }
        if (user.getMemberCode() == null) {
            user.setMemberCode(newUniqueCode());
        }
        return user.getMemberCode();
    }

    /** The member's code, creating it the first time it is needed (members who signed up before codes existed). */
    @Transactional
    public String ensureCode(Long userId) {
        User user = userRepository.findById(userId).orElseThrow(() -> new NotFoundException("Không tìm thấy tài khoản"));
        boolean missing = user.getMemberCode() == null;
        String code = assignIfMissing(user);
        if (missing && code != null) {
            userRepository.save(user);
        }
        return code;
    }

    /** Gives a code to every member that has none. Run once at start-up; returns how many codes were made. */
    @Transactional
    public int backfillMissing() {
        List<User> members = userRepository.findByRoleAndMemberCodeIsNull(Role.USER);
        for (User user : members) {
            assignIfMissing(user);
            // Flush one at a time, so the next existsByMemberCode check also sees the codes given in this loop.
            userRepository.saveAndFlush(user);
        }
        if (!members.isEmpty()) {
            log.info("Gave member codes to {} existing members", members.size());
        }
        return members.size();
    }

    // ------------------------------------------------------------------ counter lookup

    /**
     * Finds the member a customer at the counter claims to be, by member code (typed or scanned) or by phone
     * number. Only enabled member accounts are found, never staff. A phone number shared by several members
     * is refused: staff then ask for the member code.
     */
    public User findForCounter(String query) {
        String text = query == null ? "" : query.trim();
        String code = text.toUpperCase(Locale.ROOT).replaceAll("[\\s-]", "");
        if (CodeGenerator.isMemberCode(code)) {
            return userRepository.findByMemberCode(code)
                    .filter(MemberCodeService::isActiveMember)
                    .orElseThrow(() -> new NotFoundException("Không tìm thấy thành viên có mã " + code));
        }
        String phone = text.replaceAll("[\\s.\\-]", "");
        if (phone.matches("0\\d{9,10}")) {
            List<User> found = userRepository.findByPhoneAndRole(phone, Role.USER).stream()
                    .filter(MemberCodeService::isActiveMember)
                    .toList();
            if (found.isEmpty()) {
                throw new NotFoundException("Không tìm thấy thành viên có số điện thoại này");
            }
            if (found.size() > 1) {
                throw new BusinessException("Có nhiều tài khoản dùng số điện thoại này. Vui lòng hỏi khách mã thành viên.");
            }
            return found.get(0);
        }
        throw new BusinessException("Nhập mã thành viên (BB + 8 ký tự) hoặc số điện thoại (10-11 số)");
    }

    /** Looks a member up for the counter and returns only what staff need to see. */
    public MemberCard lookupForCounter(String query) {
        return cardOf(findForCounter(query));
    }

    /**
     * The member a counter sale is attached to. Re-checked on every sale: the id comes from the browser and
     * must point at an enabled member account, never a staff or admin account.
     */
    public User memberForSale(Long memberId) {
        User user = userRepository.findById(memberId)
                .filter(MemberCodeService::isActiveMember)
                .orElseThrow(() -> new BusinessException("Tài khoản thành viên không hợp lệ. Vui lòng tra cứu lại khách thành viên."));
        return user;
    }

    public MemberCard cardOf(User user) {
        MemberTier tier = membershipService.tierOf(user);
        int age = user.ageOn(LocalDate.now(clock));
        return new MemberCard(user.getId(), user.getFullName(), user.getMemberCode(), maskPhone(user.getPhone()),
                tier.name(), tier.getLabel(), membershipService.discountPercent(tier), age < 0 ? null : age);
    }

    static boolean isActiveMember(User user) {
        return user.getRole() == Role.USER && user.isEnabled();
    }

    /** "0901234567" -> "09xx xxx 567": enough for the customer to recognise their number, not to copy it. */
    static String maskPhone(String phone) {
        if (phone == null || phone.length() < 6) {
            return null;
        }
        int hidden = phone.length() - 5;
        return phone.substring(0, 2) + "xx " + "x".repeat(hidden - 2) + " " + phone.substring(phone.length() - 3);
    }
}
