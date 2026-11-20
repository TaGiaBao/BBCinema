package com.bbcinema.repository;

import com.bbcinema.entity.User;
import com.bbcinema.entity.enums.Role;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByEmailIgnoreCase(String email);

    boolean existsByEmailIgnoreCase(String email);

    Optional<User> findByResetTokenHash(String resetTokenHash);

    Optional<User> findByVerifyTokenHash(String verifyTokenHash);

    long countByRole(Role role);

    boolean existsByMemberCode(String memberCode);

    Optional<User> findByMemberCode(String memberCode);

    java.util.List<User> findByPhoneAndRole(String phone, Role role);

    /** Members created before member codes existed (or by a role change) that still need one. */
    java.util.List<User> findByRoleAndMemberCodeIsNull(Role role);

    @Query("""
            select u from User u
            where (:keyword is null or lower(u.email) like lower(concat('%', :keyword, '%'))
                   or lower(u.fullName) like lower(concat('%', :keyword, '%')))
              and (:role is null or u.role = :role)
            order by u.createdAt desc
            """)
    Page<User> search(String keyword, Role role, Pageable pageable);
}
