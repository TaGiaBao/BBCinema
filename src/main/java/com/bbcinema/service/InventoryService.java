package com.bbcinema.service;

import com.bbcinema.entity.Cinema;
import com.bbcinema.entity.InventoryItem;
import com.bbcinema.entity.StockRequest;
import com.bbcinema.entity.StockRequestLine;
import com.bbcinema.entity.User;
import com.bbcinema.entity.enums.Role;
import com.bbcinema.entity.enums.StockRequestStatus;
import com.bbcinema.exception.BusinessException;
import com.bbcinema.exception.NotFoundException;
import com.bbcinema.repository.InventoryItemRepository;
import com.bbcinema.repository.StockRequestRepository;
import com.bbcinema.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Snack-bar stock per cinema. The counter manager enters what was delivered (a goods-received note); the general
 * manager approves or rejects it, and only approved notes count towards the stock on hand.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class InventoryService {

    public static final int MAX_QUANTITY = 100_000;

    private final InventoryItemRepository itemRepository;
    private final StockRequestRepository requestRepository;
    private final UserRepository userRepository;
    private final Clock clock;

    public List<InventoryItem> items() {
        return itemRepository.findAllByOrderByNameAsc();
    }

    public List<InventoryItem> activeItems() {
        return itemRepository.findByActiveTrueOrderByNameAsc();
    }

    /** Quantity on hand of every item at a cinema (items never received show 0). */
    public Map<InventoryItem, Integer> stockOf(Long cinemaId) {
        Map<Long, Integer> received = new HashMap<>();
        for (Object[] row : requestRepository.stockOf(cinemaId)) {
            received.put((Long) row[0], ((Number) row[1]).intValue());
        }
        Map<InventoryItem, Integer> stock = new LinkedHashMap<>();
        items().forEach(item -> stock.put(item, received.getOrDefault(item.getId(), 0)));
        return stock;
    }

    /** Notes the viewer may see: every cinema for the general manager, their own cinema for a counter manager. */
    public List<StockRequest> requestsFor(User viewer) {
        if (viewer.getRole() == Role.ADMIN) {
            return requestRepository.findTop100ByOrderByRequestedAtDesc();
        }
        return viewer.getCinema() == null ? List.of() : requestRepository.findTop100ByCinemaIdOrderByRequestedAtDesc(viewer.getCinema().getId());
    }

    public long pendingCount() {
        return requestRepository.countByStatus(StockRequestStatus.PENDING);
    }

    /** The counter manager records a delivery for their cinema; it waits for the general manager's approval. */
    @Transactional
    public StockRequest request(Long managerId, Map<Long, Integer> quantities, String note) {
        User manager = user(managerId);
        if (manager.getRole() != Role.MANAGER) {
            throw new BusinessException("Chỉ quản lý quầy tạo phiếu nhập kho");
        }
        Cinema cinema = manager.getCinema();
        if (cinema == null) {
            throw new BusinessException("Tài khoản quản lý quầy chưa được gắn rạp");
        }
        StockRequest request = new StockRequest();
        request.setCinema(cinema);
        request.setRequestedBy(manager);
        request.setRequestedAt(LocalDateTime.now(clock));
        request.setNote(note == null || note.isBlank() ? null : note.trim());
        for (InventoryItem item : activeItems()) {
            Integer quantity = quantities.get(item.getId());
            if (quantity == null || quantity == 0) {
                continue;
            }
            if (quantity < 0 || quantity > MAX_QUANTITY) {
                throw new BusinessException("Số lượng " + item.getName() + " phải từ 1 đến " + MAX_QUANTITY);
            }
            StockRequestLine line = new StockRequestLine();
            line.setRequest(request);
            line.setItem(item);
            line.setQuantity(quantity);
            request.getLines().add(line);
        }
        if (request.getLines().isEmpty()) {
            throw new BusinessException("Nhập số lượng cho ít nhất một mặt hàng");
        }
        request = requestRepository.save(request);
        log.info("Stock request {} for {} by {} ({} lines)", request.getId(), cinema.getName(), manager.getEmail(), request.getLines().size());
        return request;
    }

    /** The general manager approves (stock goes up) or rejects (with a reason) a pending note. */
    @Transactional
    public StockRequest review(Long requestId, Long adminId, boolean approve, String reviewNote) {
        User admin = user(adminId);
        if (admin.getRole() != Role.ADMIN) {
            throw new BusinessException("Chỉ quản lý tổng duyệt phiếu nhập kho");
        }
        StockRequest request = requestRepository.findById(requestId)
                .orElseThrow(() -> new NotFoundException("Không tìm thấy phiếu nhập kho"));
        if (!request.isPending()) {
            throw new BusinessException("Phiếu #" + requestId + " đã được xử lý (" + request.getStatus().getLabel().toLowerCase() + ")");
        }
        if (!approve && (reviewNote == null || reviewNote.isBlank())) {
            throw new BusinessException("Ghi lý do từ chối để quản lý quầy biết");
        }
        request.setStatus(approve ? StockRequestStatus.APPROVED : StockRequestStatus.REJECTED);
        request.setReviewedBy(admin);
        request.setReviewedAt(LocalDateTime.now(clock));
        request.setReviewNote(reviewNote == null || reviewNote.isBlank() ? null : reviewNote.trim());
        return request;
    }

    /** The general manager adds an item to the stock list. */
    @Transactional
    public InventoryItem addItem(String name, String unit) {
        if (name == null || name.isBlank() || unit == null || unit.isBlank()) {
            throw new BusinessException("Nhập tên mặt hàng và đơn vị tính");
        }
        if (itemRepository.existsByNameIgnoreCase(name.trim())) {
            throw new BusinessException("Mặt hàng " + name.trim() + " đã có");
        }
        return itemRepository.save(new InventoryItem(name.trim(), unit.trim()));
    }

    private User user(Long id) {
        return userRepository.findById(id).orElseThrow(() -> new NotFoundException("Không tìm thấy tài khoản"));
    }
}
