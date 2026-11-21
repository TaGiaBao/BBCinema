package com.bbcinema.service;

import com.bbcinema.dto.request.BirthdayRewardForm;
import com.bbcinema.entity.BirthdayReward;
import com.bbcinema.entity.BirthdayRewardSettings;
import com.bbcinema.entity.Promotion;
import com.bbcinema.entity.User;
import com.bbcinema.entity.enums.DiscountType;
import com.bbcinema.entity.enums.Role;
import com.bbcinema.exception.BusinessException;
import com.bbcinema.repository.BirthdayRewardRepository;
import com.bbcinema.repository.BirthdayRewardSettingsRepository;
import com.bbcinema.repository.PromotionRepository;
import com.bbcinema.repository.UserRepository;
import com.bbcinema.service.event.BirthdayRewardIssuedEvent;
import com.bbcinema.util.CodeGenerator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Birthday gift: once a year, in the member's birthday month, each enabled member with a confirmed e-mail and a
 * birth date gets a personal promotion code (SN + 8 characters) that only their account can use, once, until the
 * last day of that month. The gift itself (percent or fixed amount) is set by the general manager.
 * <p>
 * Validity window: the whole birthday month (1st to last day). A 29 February birthday therefore simply falls in
 * February every year. The job runs daily and also catches up members whose window is open but who have no code
 * yet (app was down, feature just turned on, member just confirmed their e-mail).
 */
@Slf4j
@Service
@Transactional(readOnly = true)
public class BirthdayRewardService {

    public static final String CODE_PREFIX = "SN";
    static final int MAX_CODE_ATTEMPTS = 10;
    /** How many of this year's gifts the admin page lists. */
    public static final int ADMIN_LIST_SIZE = 50;

    private final BirthdayRewardSettingsRepository settingsRepository;
    private final BirthdayRewardRepository rewardRepository;
    private final PromotionRepository promotionRepository;
    private final UserRepository userRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;
    /** Each member's gift is written in its own transaction, so one clash (unique key) only undoes that member. */
    private final TransactionOperations newTransaction;

    @Autowired
    public BirthdayRewardService(BirthdayRewardSettingsRepository settingsRepository, BirthdayRewardRepository rewardRepository,
                                 PromotionRepository promotionRepository, UserRepository userRepository,
                                 ApplicationEventPublisher eventPublisher, Clock clock,
                                 PlatformTransactionManager transactionManager) {
        this.settingsRepository = settingsRepository;
        this.rewardRepository = rewardRepository;
        this.promotionRepository = promotionRepository;
        this.userRepository = userRepository;
        this.eventPublisher = eventPublisher;
        this.clock = clock;
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.newTransaction = template;
    }

    // ------------------------------------------------------------------ settings

    /** The current settings; the defaults (turned off) when the row does not exist yet. */
    public BirthdayRewardSettings settings() {
        return settingsRepository.findById(BirthdayRewardSettings.SINGLETON_ID).orElseGet(BirthdayRewardSettings::new);
    }

    @Transactional
    public BirthdayRewardSettings saveSettings(BirthdayRewardForm form) {
        validate(form);
        BirthdayRewardSettings settings = settingsRepository.findById(BirthdayRewardSettings.SINGLETON_ID)
                .orElseGet(BirthdayRewardSettings::new);
        settings.setEnabled(form.isEnabled());
        settings.setDiscountType(form.getDiscountType());
        settings.setDiscountValue(form.getDiscountValue());
        // A cap only means something for a percent; a zero cap is read as "no cap".
        boolean percent = form.getDiscountType() == DiscountType.PERCENT;
        settings.setMaxDiscount(percent && positive(form.getMaxDiscount()) ? form.getMaxDiscount() : null);
        settings.setMinOrder(positive(form.getMinOrder()) ? form.getMinOrder() : null);
        settings.setUpdatedAt(LocalDateTime.now(clock));
        return settingsRepository.save(settings);
    }

    static void validate(BirthdayRewardForm form) {
        if (form.getDiscountType() == null || form.getDiscountValue() == null) {
            throw new BusinessException("Vui lòng chọn cách giảm giá và nhập mức giảm");
        }
        if (form.getDiscountValue().signum() <= 0) {
            throw new BusinessException("Mức giảm phải lớn hơn 0");
        }
        if (form.getDiscountType() == DiscountType.PERCENT) {
            if (form.getDiscountValue().compareTo(BigDecimal.valueOf(100)) > 0) {
                throw new BusinessException("Giảm theo phần trăm chỉ từ 1% đến 100%");
            }
        } else if (form.getDiscountValue().compareTo(BigDecimal.valueOf(1000)) < 0) {
            throw new BusinessException("Số tiền giảm tối thiểu là 1.000đ");
        }
    }

    public BirthdayRewardForm toForm(BirthdayRewardSettings settings) {
        BirthdayRewardForm form = new BirthdayRewardForm();
        form.setEnabled(settings.isEnabled());
        form.setDiscountType(settings.getDiscountType());
        form.setDiscountValue(settings.getDiscountValue());
        form.setMaxDiscount(settings.getMaxDiscount());
        form.setMinOrder(settings.getMinOrder());
        return form;
    }

    private static boolean positive(BigDecimal value) {
        return value != null && value.signum() > 0;
    }

    // ------------------------------------------------------------------ window

    /** First day the gift can be used in {@code year}: the 1st of the birthday month. */
    public static LocalDate windowStart(LocalDate birthDate, int year) {
        return YearMonth.of(year, birthDate.getMonth()).atDay(1);
    }

    /** Last day the gift can be used in {@code year}: the last day of the birthday month. */
    public static LocalDate windowEnd(LocalDate birthDate, int year) {
        return YearMonth.of(year, birthDate.getMonth()).atEndOfMonth();
    }

    /** Whether {@code today} is inside the member's birthday window of that year. */
    public static boolean inWindow(LocalDate birthDate, LocalDate today) {
        return birthDate != null && !today.isBefore(windowStart(birthDate, today.getYear()))
                && !today.isAfter(windowEnd(birthDate, today.getYear()));
    }

    /** Members the gift is meant for: enabled members with a confirmed e-mail and a known birth date. */
    static boolean isEligible(User user) {
        return user.getRole() == Role.USER && user.isEnabled() && user.isEmailVerified() && user.getBirthDate() != null;
    }

    // ------------------------------------------------------------------ issuing

    /**
     * Gives this year's code to every eligible member whose birthday window is open today and who has none yet.
     * Does nothing when the feature is off (codes already given stay valid until they expire). Safe to run any
     * number of times, also from two app instances at once. Returns how many codes were given.
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public int issueDue() {
        BirthdayRewardSettings settings = settings();
        if (!settings.isEnabled()) {
            return 0;
        }
        LocalDate today = LocalDate.now(clock);
        int issued = 0;
        for (Long userId : rewardRepository.findMembersDue(today.getMonthValue(), today.getYear())) {
            try {
                if (Boolean.TRUE.equals(newTransaction.execute(status -> issueFor(userId, settings, today)))) {
                    issued++;
                }
            } catch (DataIntegrityViolationException e) {
                // Another instance (or run) gave this member their code at the same moment.
                log.debug("Member {} already got a birthday code for {}", userId, today.getYear());
            } catch (RuntimeException e) {
                log.warn("Could not give member {} a birthday code: {}", userId, e.getMessage());
            }
        }
        if (issued > 0) {
            log.info("Gave {} birthday code(s)", issued);
        }
        return issued;
    }

    /** Runs inside its own transaction. Returns false when the member is (no longer) due a code. */
    private boolean issueFor(Long userId, BirthdayRewardSettings settings, LocalDate today) {
        User user = userRepository.findById(userId).orElse(null);
        if (user == null || !isEligible(user) || !inWindow(user.getBirthDate(), today)
                || rewardRepository.existsByUserIdAndRewardYear(userId, today.getYear())) {
            return false;
        }
        Promotion promotion = new Promotion();
        promotion.setCode(newUniqueCode());
        promotion.setDescription("Quà sinh nhật " + today.getYear() + " - " + user.getFullName());
        if (settings.getDiscountType() == DiscountType.AMOUNT) {
            promotion.setDiscountPercent(0);
            promotion.setDiscountAmount(settings.getDiscountValue());
        } else {
            promotion.setDiscountPercent(settings.getDiscountValue().intValueExact());
            promotion.setMaxDiscount(settings.getMaxDiscount());
        }
        promotion.setMinOrder(settings.getMinOrder());
        promotion.setValidFrom(windowStart(user.getBirthDate(), today.getYear()));
        promotion.setValidTo(windowEnd(user.getBirthDate(), today.getYear()));
        promotion.setUsageLimit(1);
        promotion.setActive(true);
        promotion.setOwner(user);
        promotionRepository.saveAndFlush(promotion);

        BirthdayReward reward = new BirthdayReward();
        reward.setUser(user);
        reward.setRewardYear(today.getYear());
        reward.setPromotion(promotion);
        reward.setIssuedAt(LocalDateTime.now(clock));
        rewardRepository.saveAndFlush(reward);
        eventPublisher.publishEvent(new BirthdayRewardIssuedEvent(reward.getId()));
        return true;
    }

    String newUniqueCode() {
        for (int i = 0; i < MAX_CODE_ATTEMPTS; i++) {
            String code = CODE_PREFIX + CodeGenerator.random(8);
            if (!promotionRepository.existsByCodeIgnoreCase(code)) {
                return code;
            }
        }
        throw new IllegalStateException("Could not find a free birthday code after " + MAX_CODE_ATTEMPTS + " attempts");
    }

    // ------------------------------------------------------------------ queries

    /** The member's gift of this year, if they have one. */
    public Optional<BirthdayReward> currentFor(Long userId) {
        return rewardRepository.findForMember(userId, LocalDate.now(clock).getYear());
    }

    /** This year's gifts, newest first, at most {@value #ADMIN_LIST_SIZE}. */
    public List<BirthdayReward> latestThisYear() {
        return rewardRepository.findLatestOfYear(LocalDate.now(clock).getYear(), PageRequest.of(0, ADMIN_LIST_SIZE));
    }

    public long countThisYear() {
        return rewardRepository.countByRewardYear(LocalDate.now(clock).getYear());
    }

    /** {@link #describe} for templates, which call beans rather than static methods. */
    public String discountText(Promotion promotion) {
        return describe(promotion);
    }

    /** "Giảm 20%, tối đa 50.000đ" / "Giảm 50.000đ": the gift in words, for the e-mail and the admin list. */
    public static String describe(Promotion promotion) {
        if (promotion.getDiscountAmount() != null) {
            return "Giảm " + money(promotion.getDiscountAmount());
        }
        String text = "Giảm " + promotion.getDiscountPercent() + "%";
        return promotion.getMaxDiscount() != null ? text + ", tối đa " + money(promotion.getMaxDiscount()) : text;
    }

    static String money(BigDecimal amount) {
        return String.format(Locale.ROOT, "%,d", amount.longValue()).replace(',', '.') + "đ";
    }
}
