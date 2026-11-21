package com.bbcinema.repository;

import com.bbcinema.entity.AuditLog;
import com.bbcinema.entity.enums.Role;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDateTime;
import java.util.Collection;

public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {

    /** Newest first. {@code from}, {@code to} and {@code outcomes} are always given; the others may be null. */
    @Query("""
            select a from AuditLog a
            where a.createdAt >= :from and a.createdAt < :to
              and a.success in :outcomes
              and (:role is null or a.actorRole = :role)
              and (:actor is null or lower(a.actorEmail) like lower(concat('%', :actor, '%')))
              and (:keyword is null or lower(a.action) like lower(concat('%', :keyword, '%'))
                   or lower(a.target) like lower(concat('%', :keyword, '%'))
                   or lower(a.detail) like lower(concat('%', :keyword, '%')))
            order by a.createdAt desc, a.id desc
            """)
    Page<AuditLog> search(LocalDateTime from, LocalDateTime to, Collection<Boolean> outcomes, Role role,
                          String actor, String keyword, Pageable pageable);
}
