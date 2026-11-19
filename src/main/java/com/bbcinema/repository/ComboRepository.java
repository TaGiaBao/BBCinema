package com.bbcinema.repository;

import com.bbcinema.entity.Combo;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ComboRepository extends JpaRepository<Combo, Long> {

    List<Combo> findByActiveTrueOrderByPriceAsc();

    List<Combo> findAllByOrderByIdAsc();
}
