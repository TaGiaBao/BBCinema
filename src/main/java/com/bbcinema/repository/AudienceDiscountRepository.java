package com.bbcinema.repository;

import com.bbcinema.entity.AudienceDiscount;
import com.bbcinema.entity.enums.AudienceType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface AudienceDiscountRepository extends JpaRepository<AudienceDiscount, Long> {

    Optional<AudienceDiscount> findByAudienceType(AudienceType audienceType);
}
