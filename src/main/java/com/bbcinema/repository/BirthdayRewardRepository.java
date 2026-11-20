package com.bbcinema.repository;

import com.bbcinema.entity.BirthdayReward;
import com.bbcinema.entity.enums.Role;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface BirthdayRewardRepository extends JpaRepository<BirthdayReward, Long> {

    boolean existsByUserIdAndRewardYear(Long userId, int rewardYear);

    @Query("select r from BirthdayReward r join fetch r.promotion join fetch r.user where r.id = :id")
    Optional<BirthdayReward> findDetailed(@Param("id") Long id);

    @Query("""
            select r from BirthdayReward r join fetch r.promotion join fetch r.user
            where r.user.id = :userId and r.rewardYear = :year""")
    Optional<BirthdayReward> findForMember(@Param("userId") Long userId, @Param("year") int year);

    /** Gifts of one year, newest first (the admin page shows the latest ones). */
    @Query("""
            select r from BirthdayReward r join fetch r.promotion join fetch r.user
            where r.rewardYear = :year order by r.issuedAt desc, r.id desc""")
    List<BirthdayReward> findLatestOfYear(@Param("year") int year, Pageable pageable);

    long countByRewardYear(int rewardYear);

    long countByUserId(Long userId);

    /**
     * Members who should have this year's gift now: enabled members with a confirmed e-mail whose birthday is in
     * {@code month}, and who have no gift for {@code year} yet.
     */
    @Query("""
            select u.id from User u
            where u.role = :role and u.enabled = true and u.emailVerifiedAt is not null
              and u.birthDate is not null and extract(month from u.birthDate) = :month
              and not exists (select 1 from BirthdayReward r where r.user = u and r.rewardYear = :year)
            order by u.id""")
    List<Long> findMembersDue(@Param("role") Role role, @Param("month") int month, @Param("year") int year);

    default List<Long> findMembersDue(int month, int year) {
        return findMembersDue(Role.USER, month, year);
    }
}
