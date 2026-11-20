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

    /** Members created before member codes existed that still need one. */
    @Query("select u.id from User u where u.role = :role and u.memberCode is null order by u.id")
    java.util.List<Long> findIdsByRoleWithoutMemberCode(@org.springframework.data.repository.query.Param("role") Role role);

    @Query("select u.memberCode from User u where u.id = :id")
    String findMemberCodeById(@org.springframework.data.repository.query.Param("id") Long id);

    /**
     * Gives an account its member code only if it still has none: a targeted update, so it never overwrites other
     * columns of a user someone else is changing. Returns the number of rows changed (0 = already had a code).
     */
    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.transaction.annotation.Transactional
    @Query("update User u set u.memberCode = :code where u.id = :id and u.memberCode is null")
    int setMemberCodeIfMissing(@org.springframework.data.repository.query.Param("id") Long id,
                               @org.springframework.data.repository.query.Param("code") String code);

    @Query("""
            select u from User u
            where (:keyword is null or lower(u.email) like lower(concat('%', :keyword, '%'))
                   or lower(u.fullName) like lower(concat('%', :keyword, '%')))
              and (:role is null or u.role = :role)
            order by u.createdAt desc
            """)
    Page<User> search(String keyword, Role role, Pageable pageable);
}
