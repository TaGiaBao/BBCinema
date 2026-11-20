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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;

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

    /** Refusal when the member attached to a sale is not (or no longer) a valid member. */
    public static final String INVALID_MEMBER = "Tài khoản thành viên không hợp lệ. Vui lòng tra cứu lại khách thành viên.";

    /** How staff found the member: phone numbers are not verified, so a phone match needs the name confirmed. */
    public enum MatchedBy { CODE, PHONE }

    /** A member found at the counter, and how. */
    public record Match(User user, MatchedBy matchedBy) {
    }

    private final UserRepository userRepository;
    private final MembershipService membershipService;
    private final Clock clock;
    private final Supplier<String> generator;
    /** Runs each attempt to store a code in its own transaction, so a unique-key clash only undoes that attempt. */
    private final TransactionOperations newTransaction;

    @Autowired
    public MemberCodeService(UserRepository userRepository, MembershipService membershipService, Clock clock,
                             PlatformTransactionManager transactionManager) {
        this(userRepository, membershipService, clock, CodeGenerator::memberCode, requiresNew(transactionManager));
    }

    MemberCodeService(UserRepository userRepository, MembershipService membershipService, Clock clock,
                      Supplier<String> generator, TransactionOperations newTransaction) {
        this.userRepository = userRepository;
        this.membershipService = membershipService;
        this.clock = clock;
        this.generator = generator;
        this.newTransaction = newTransaction;
    }

    private static TransactionOperations requiresNew(PlatformTransactionManager transactionManager) {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return template;
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
     * Gives a new (or just loaded) member account its code if it has none; the caller saves the user. Returns
     * the code, or null for staff, managers and admins, including a former member who kept an old code: only
     * members may use one.
     */
    public String assignIfMissing(User user) {
        if (user.getRole() != Role.USER) {
            return null;
        }
        if (user.getMemberCode() == null) {
            user.setMemberCode(newUniqueCode());
        }
        return user.getMemberCode();
    }

    /**
     * The member's code, creating it the first time it is needed (members who joined before codes existed).
     * Null for internal accounts. A missing code is written with a targeted update, never a whole-entity save.
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public String ensureCode(Long userId) {
        User user = userRepository.findById(userId).orElseThrow(() -> new NotFoundException("Không tìm thấy tài khoản"));
        if (user.getRole() != Role.USER) {
            return null;
        }
        if (user.getMemberCode() != null) {
            return user.getMemberCode();
        }
        String written = storeNewCode(userId);
        String code = written != null ? written : userRepository.findMemberCodeById(userId);
        user.setMemberCode(code); // keep a loaded copy in line, should the caller save it later
        return code;
    }

    /**
     * Writes a fresh code for an account that has none, each attempt in its own transaction. A clash on the
     * unique key (another account took the same code meanwhile) draws again; zero rows updated means the account
     * already got a code elsewhere, which is fine. Returns the code this call wrote, or null when it wrote none.
     */
    String storeNewCode(Long userId) {
        for (int i = 0; i < MAX_ATTEMPTS; i++) {
            String code = newUniqueCode();
            try {
                Integer rows = newTransaction.execute(status -> userRepository.setMemberCodeIfMissing(userId, code));
                return rows != null && rows > 0 ? code : null;
            } catch (DataIntegrityViolationException e) {
                log.debug("Member code {} was taken meanwhile, drawing another", code);
            }
        }
        throw new IllegalStateException("Could not store a member code after " + MAX_ATTEMPTS + " attempts");
    }

    /** Gives a code to every member that has none. Run at start-up; returns how many codes were written. */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public int backfillMissing() {
        List<Long> ids = userRepository.findIdsByRoleWithoutMemberCode(Role.USER);
        int made = 0;
        for (Long id : ids) {
            try {
                if (storeNewCode(id) != null) {
                    made++;
                }
            } catch (RuntimeException e) {
                log.warn("Could not give member {} a member code: {}", id, e.getMessage());
            }
        }
        if (made > 0) {
            log.info("Gave member codes to {} existing members", made);
        }
        return made;
    }

    // ------------------------------------------------------------------ counter lookup

    /**
     * Finds the member a customer at the counter claims to be, by member code (typed or scanned) or by phone
     * number. Only enabled members with a confirmed e-mail are found, never staff. A phone number shared by
     * several members is refused: staff then ask for the member code.
     */
    public Match findForCounter(String query) {
        String text = query == null ? "" : query.trim();
        String code = normalizeCode(text);
        if (CodeGenerator.isMemberCode(code)) {
            User user = userRepository.findByMemberCode(code)
                    .filter(MemberCodeService::isActiveMember)
                    .orElseThrow(() -> new NotFoundException("Không tìm thấy thành viên có mã " + code));
            return new Match(user, MatchedBy.CODE);
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
            return new Match(found.get(0), MatchedBy.PHONE);
        }
        throw new BusinessException("Nhập mã thành viên (BB + 8 ký tự) hoặc số điện thoại (10-11 số)");
    }

    /**
     * Looks a member up for the counter and returns only what staff need to see. The age is worked out on
     * {@code onDate} (the showtime's date), or today when none is given.
     */
    public MemberCard lookupForCounter(String query, LocalDate onDate) {
        Match match = findForCounter(query);
        User user = match.user();
        if (user.getMemberCode() == null) {
            // Found by phone before the start-up backfill reached them; the sale form carries the code.
            ensureCode(user.getId());
        }
        return cardOf(user, match.matchedBy(), onDate);
    }

    /**
     * The member a counter sale is attached to, from the member code the sale form carries. Re-checked on every
     * sale with the lookup's rules: the code comes from the browser and must belong to an enabled member with a
     * confirmed e-mail, never a staff or admin account.
     */
    public User memberForSale(String memberCode) {
        String code = normalizeCode(memberCode == null ? "" : memberCode.trim());
        if (!CodeGenerator.isMemberCode(code)) {
            throw new BusinessException(INVALID_MEMBER);
        }
        return userRepository.findByMemberCode(code)
                .filter(MemberCodeService::isActiveMember)
                .orElseThrow(() -> new BusinessException(INVALID_MEMBER));
    }

    public MemberCard cardOf(User user, MatchedBy matchedBy, LocalDate onDate) {
        MemberTier tier = membershipService.tierOf(user);
        int age = user.ageOn(onDate == null ? LocalDate.now(clock) : onDate);
        return new MemberCard(user.getFullName(), user.getMemberCode(), maskPhone(user.getPhone()),
                tier.name(), tier.getLabel(), membershipService.discountPercent(tier), age < 0 ? null : age,
                matchedBy.name());
    }

    /** Enabled members who confirmed their e-mail; accounts made by the cinema or through Google count as confirmed. */
    static boolean isActiveMember(User user) {
        return user.getRole() == Role.USER && user.isEnabled() && user.isEmailVerified();
    }

    private static String normalizeCode(String text) {
        return text.toUpperCase(Locale.ROOT).replaceAll("[\\s-]", "");
    }

    /** "0901234567" -> "09xx xxx 567": enough for the customer to recognise their number, not to copy it. */
    static String maskPhone(String phone) {
        if (phone == null || phone.length() < 6) {
            return null;
        }
        int hidden = phone.length() - 5;
        return phone.substring(0, 2) + "xx " + "x".repeat(hidden - 2) + " " + phone.substring(phone.length() - 3);
    }

    /**
     * "BB7KQ2MXPA" -> "BB•••••XPA" for printed tickets: paper gets left behind, and the full code would let anyone
     * collect the member's discount and points at the counter.
     */
    public String maskCode(String code) {
        if (code == null || code.length() <= 5) {
            return code;
        }
        return code.substring(0, 2) + "•".repeat(code.length() - 5) + code.substring(code.length() - 3);
    }
}
