package com.bbcinema.service;

import com.bbcinema.dto.request.PasswordForm;
import com.bbcinema.dto.request.ProfileForm;
import com.bbcinema.dto.request.RegisterForm;
import com.bbcinema.entity.User;
import com.bbcinema.entity.enums.Role;
import com.bbcinema.exception.BusinessException;
import com.bbcinema.exception.NotFoundException;
import com.bbcinema.repository.UserRepository;
import com.bbcinema.util.TextUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Locale;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class UserService {

    private final UserRepository userRepository;
    private final com.bbcinema.repository.CinemaRepository cinemaRepository;
    private final PasswordEncoder passwordEncoder;
    private final MemberCodeService memberCodeService;
    private final Clock clock;

    public User getById(Long id) {
        return userRepository.findById(id).orElseThrow(() -> new NotFoundException("Không tìm thấy tài khoản"));
    }

    public User getByEmail(String email) {
        return userRepository.findByEmailIgnoreCase(email).orElseThrow(() -> new NotFoundException("Không tìm thấy tài khoản"));
    }

    @Transactional
    public User register(RegisterForm form) {
        if (!form.getPassword().equals(form.getConfirmPassword())) {
            throw new BusinessException("Mật khẩu nhập lại không khớp");
        }
        String email = form.getEmail().trim().toLowerCase(Locale.ROOT);
        if (userRepository.existsByEmailIgnoreCase(email)) {
            throw new BusinessException("Email " + email + " đã được đăng ký");
        }
        User user = new User();
        user.setEmail(email);
        user.setFullName(form.getFullName().trim());
        user.setPhone(TextUtil.blankToNull(form.getPhone()));
        user.setBirthDate(form.getBirthDate());
        user.setPasswordHash(passwordEncoder.encode(form.getPassword()));
        user.setRole(Role.USER);
        // Not confirmed yet: EmailVerificationService mails the link, and login waits for it.
        user.setEmailVerifiedAt(null);
        memberCodeService.assignIfMissing(user);
        return userRepository.save(user);
    }

    @Transactional
    public User createAccount(String email, String fullName, String rawPassword, Role role) {
        User user = new User();
        user.setEmail(email.toLowerCase(Locale.ROOT));
        user.setFullName(fullName);
        user.setPasswordHash(passwordEncoder.encode(rawPassword));
        user.setRole(role);
        // Accounts made by the cinema (staff, demo data) or vouched for by Google need no confirmation mail.
        user.setEmailVerifiedAt(LocalDateTime.now(clock));
        memberCodeService.assignIfMissing(user); // members only; internal accounts get no code
        return userRepository.save(user);
    }

    /**
     * The general manager gives out an internal account (staff, counter manager or general manager).
     * A counter manager must belong to a cinema; members sign up themselves.
     */
    @Transactional
    public User createInternalAccount(String email, String fullName, String rawPassword, Role role, Long cinemaId) {
        if (role == null || !role.isInternal()) {
            throw new BusinessException("Chỉ cấp tài khoản nhân viên, quản lý quầy hoặc quản lý tổng");
        }
        if (email == null || !email.trim().matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")) {
            throw new BusinessException("Email không hợp lệ");
        }
        if (fullName == null || fullName.isBlank()) {
            throw new BusinessException("Nhập họ tên");
        }
        if (rawPassword == null || rawPassword.length() < 8) {
            throw new BusinessException("Mật khẩu tối thiểu 8 ký tự");
        }
        if (userRepository.existsByEmailIgnoreCase(email.trim())) {
            throw new BusinessException("Email " + email.trim() + " đã có tài khoản");
        }
        User user = createAccount(email.trim(), fullName.trim(), rawPassword, role);
        user.setCinema(cinemaFor(role, cinemaId));
        return user;
    }

    private com.bbcinema.entity.Cinema cinemaFor(Role role, Long cinemaId) {
        if (role == Role.MANAGER && cinemaId == null) {
            throw new BusinessException("Quản lý quầy phải thuộc một rạp");
        }
        if (cinemaId == null || role == Role.ADMIN || role == Role.USER) {
            return null;
        }
        return cinemaRepository.findById(cinemaId).orElseThrow(() -> new NotFoundException("Không tìm thấy rạp"));
    }

    @Transactional
    public void updateProfile(Long userId, ProfileForm form) {
        User user = getById(userId);
        user.setFullName(form.getFullName().trim());
        user.setPhone(TextUtil.blankToNull(form.getPhone()));
        // Members may fill in a missing birth date once; after that only the cinema can change it, otherwise a member
        // could move it to get the birthday gift on demand or pass the age check for T13/T16/T18 films.
        if (user.getBirthDate() == null) {
            user.setBirthDate(form.getBirthDate());
        }
    }

    @Transactional
    public void changePassword(Long userId, PasswordForm form) {
        User user = getById(userId);
        if (!passwordEncoder.matches(form.getCurrentPassword(), user.getPasswordHash())) {
            throw new BusinessException("Mật khẩu hiện tại không đúng");
        }
        if (!form.getNewPassword().equals(form.getConfirmPassword())) {
            throw new BusinessException("Mật khẩu nhập lại không khớp");
        }
        user.setPasswordHash(passwordEncoder.encode(form.getNewPassword()));
    }

    public ProfileForm toProfileForm(User user) {
        ProfileForm form = new ProfileForm();
        form.setFullName(user.getFullName());
        form.setPhone(user.getPhone());
        form.setBirthDate(user.getBirthDate());
        return form;
    }

    public Page<User> search(String keyword, Role role, Pageable pageable) {
        return userRepository.search(TextUtil.blankToNull(keyword), role, pageable);
    }

    /** Admin action: change role, cinema, or lock/unlock. An admin cannot lock or demote their own account. */
    @Transactional
    public void updateByAdmin(Long adminId, Long userId, Role role, boolean enabled) {
        updateByAdmin(adminId, userId, role, enabled, getById(userId).getCinema() == null ? null : getById(userId).getCinema().getId());
    }

    @Transactional
    public void updateByAdmin(Long adminId, Long userId, Role role, boolean enabled, Long cinemaId) {
        User user = getById(userId);
        if (user.getId().equals(adminId) && (role != Role.ADMIN || !enabled)) {
            throw new BusinessException("Bạn không thể tự khoá hoặc tự hạ quyền tài khoản của mình");
        }
        user.setCinema(cinemaFor(role, cinemaId));
        user.setRole(role);
        user.setEnabled(enabled);
        memberCodeService.assignIfMissing(user); // an account turned into a member gets its code now
    }

    public long countMembers() {
        return userRepository.countByRole(Role.USER);
    }
}
