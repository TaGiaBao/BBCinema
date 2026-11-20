package com.bbcinema.repository;

import com.bbcinema.entity.InventoryItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface InventoryItemRepository extends JpaRepository<InventoryItem, Long> {

    List<InventoryItem> findAllByOrderByNameAsc();

    List<InventoryItem> findByActiveTrueOrderByNameAsc();

    boolean existsByNameIgnoreCase(String name);
}
