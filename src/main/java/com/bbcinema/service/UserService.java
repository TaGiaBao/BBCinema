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
    private final PasswordEncoder passwordEncoder;
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
        return userRepository.save(user);
    }

    @Transactional
    public void updateProfile(Long userId, ProfileForm form) {
        User user = getById(userId);
        user.setFullName(form.getFullName().trim());
        user.setPhone(TextUtil.blankToNull(form.getPhone()));
        user.setBirthDate(form.getBirthDate());
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

    /** Admin action: change role or lock/unlock. An admin cannot lock or demote their own account. */
    @Transactional
    public void updateByAdmin(Long adminId, Long userId, Role role, boolean enabled) {
        User user = getById(userId);
        if (user.getId().equals(adminId) && (role != Role.ADMIN || !enabled)) {
            throw new BusinessException("Bạn không thể tự khoá hoặc tự hạ quyền tài khoản của mình");
        }
        user.setRole(role);
        user.setEnabled(enabled);
    }

    public long countMembers() {
        return userRepository.countByRole(Role.USER);
    }
}
