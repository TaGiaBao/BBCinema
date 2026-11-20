package com.bbcinema.repository;

import com.bbcinema.entity.ComboIngredient;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface ComboIngredientRepository extends JpaRepository<ComboIngredient, Long> {

    @EntityGraph(attributePaths = {"item", "combo"})
    List<ComboIngredient> findByComboIdIn(Collection<Long> comboIds);

    @EntityGraph(attributePaths = {"item", "combo"})
    List<ComboIngredient> findAllByOrderByIdAsc();

    void deleteByComboId(Long comboId);
}
