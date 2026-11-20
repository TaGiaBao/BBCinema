package com.bbcinema.service;

import com.bbcinema.entity.Booking;
import com.bbcinema.entity.BookingCombo;
import com.bbcinema.entity.Cinema;
import com.bbcinema.entity.Combo;
import com.bbcinema.entity.ComboIngredient;
import com.bbcinema.entity.InventoryItem;
import com.bbcinema.entity.InventoryMovement;
import com.bbcinema.entity.StockRequest;
import com.bbcinema.entity.StockRequestLine;
import com.bbcinema.entity.User;
import com.bbcinema.entity.enums.Role;
import com.bbcinema.entity.enums.StockRequestStatus;
import com.bbcinema.exception.BusinessException;
import com.bbcinema.exception.NotFoundException;
import com.bbcinema.repository.ComboIngredientRepository;
import com.bbcinema.repository.ComboRepository;
import com.bbcinema.repository.InventoryItemRepository;
import com.bbcinema.repository.InventoryMovementRepository;
import com.bbcinema.repository.StockRequestRepository;
import com.bbcinema.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Snack-bar stock per cinema. The counter manager enters what was delivered (a goods-received note) and the
 * general manager approves it; paid orders take their combos' ingredients out of the stock of the cinema where the
 * film is shown, and a refund puts them back. Stock on hand = approved notes + those movements.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class InventoryService {

    public static final BigDecimal MAX_QUANTITY = BigDecimal.valueOf(100_000);

    private final InventoryItemRepository itemRepository;
    private final StockRequestRepository requestRepository;
    private final InventoryMovementRepository movementRepository;
    private final ComboIngredientRepository ingredientRepository;
    private final ComboRepository comboRepository;
    private final UserRepository userRepository;
    private final Clock clock;

    /** One row of the stock table. */
    public record StockLine(InventoryItem item, BigDecimal received, BigDecimal used, BigDecimal onHand) {
        public boolean isOut() {
            return onHand.signum() <= 0;
        }
    }

    public List<InventoryItem> items() {
        return itemRepository.findAllByOrderByNameAsc();
    }

    public List<InventoryItem> activeItems() {
        return itemRepository.findByActiveTrueOrderByNameAsc();
    }

    /** Received (approved notes), used (net of refunds) and on hand, for every item at a cinema. */
    public List<StockLine> stockLines(Long cinemaId) {
        Map<Long, BigDecimal> received = sums(requestRepository.stockOf(cinemaId));
        Map<Long, BigDecimal> moved = sums(movementRepository.totalsOf(cinemaId));
        return items().stream().map(item -> {
            BigDecimal in = received.getOrDefault(item.getId(), BigDecimal.ZERO);
            BigDecimal movement = moved.getOrDefault(item.getId(), BigDecimal.ZERO);
            return new StockLine(item, in, movement.negate(), in.add(movement));
        }).toList();
    }

    /** Quantity on hand per item at a cinema. */
    public Map<InventoryItem, BigDecimal> stockOf(Long cinemaId) {
        Map<InventoryItem, BigDecimal> stock = new LinkedHashMap<>();
        stockLines(cinemaId).forEach(l -> stock.put(l.item(), l.onHand()));
        return stock;
    }

    private static Map<Long, BigDecimal> sums(List<Object[]> rows) {
        Map<Long, BigDecimal> map = new HashMap<>();
        for (Object[] row : rows) {
            map.put((Long) row[0], row[1] instanceof BigDecimal d ? d : new BigDecimal(row[1].toString()));
        }
        return map;
    }

    // ------------------------------------------------------------------ combos

    /** Recipe of every combo, by combo id. */
    public Map<Long, List<ComboIngredient>> recipes() {
        return ingredientRepository.findAllByOrderByIdAsc().stream()
                .collect(Collectors.groupingBy(i -> i.getCombo().getId(), LinkedHashMap::new, Collectors.toList()));
    }

    /** The general manager sets what one portion of a combo uses; blank or 0 removes the item. */
    @Transactional
    public void saveRecipe(Long comboId, Map<Long, BigDecimal> quantities) {
        Combo combo = comboRepository.findById(comboId).orElseThrow(() -> new NotFoundException("Không tìm thấy combo"));
        ingredientRepository.deleteByComboId(comboId);
        ingredientRepository.flush();
        for (InventoryItem item : items()) {
            BigDecimal quantity = quantities.get(item.getId());
            if (quantity == null || quantity.signum() == 0) {
                continue;
            }
            if (quantity.signum() < 0 || quantity.compareTo(BigDecimal.valueOf(1000)) > 0) {
                throw new BusinessException("Định lượng " + item.getName() + " phải lớn hơn 0 và không quá 1000");
            }
            ComboIngredient ingredient = new ComboIngredient();
            ingredient.setCombo(combo);
            ingredient.setItem(item);
            ingredient.setQuantity(quantity);
            ingredientRepository.save(ingredient);
        }
    }

    /** What an order's combos take from the stock, per item. Combos without a recipe take nothing. */
    private Map<InventoryItem, BigDecimal> needs(List<BookingCombo> lines) {
        Map<InventoryItem, BigDecimal> needs = new LinkedHashMap<>();
        if (lines.isEmpty()) {
            return needs;
        }
        List<Long> comboIds = lines.stream().map(l -> l.getCombo().getId()).distinct().toList();
        Map<Long, List<ComboIngredient>> recipe = ingredientRepository.findByComboIdIn(comboIds).stream()
                .collect(Collectors.groupingBy(i -> i.getCombo().getId()));
        for (BookingCombo line : lines) {
            for (ComboIngredient ingredient : recipe.getOrDefault(line.getCombo().getId(), List.of())) {
                needs.merge(ingredient.getItem(), ingredient.getQuantity().multiply(BigDecimal.valueOf(line.getQuantity())), BigDecimal::add);
            }
        }
        return needs;
    }

    /** Refuses an order whose combos need more than the cinema has on hand. */
    public void checkAvailable(Cinema cinema, List<BookingCombo> lines) {
        Map<InventoryItem, BigDecimal> needs = needs(lines);
        if (needs.isEmpty()) {
            return;
        }
        Map<Long, BigDecimal> onHand = new HashMap<>();
        stockLines(cinema.getId()).forEach(l -> onHand.put(l.item().getId(), l.onHand()));
        for (Map.Entry<InventoryItem, BigDecimal> need : needs.entrySet()) {
            if (onHand.getOrDefault(need.getKey().getId(), BigDecimal.ZERO).compareTo(need.getValue()) < 0) {
                String combos = lines.stream().map(l -> l.getCombo().getName()).distinct().collect(Collectors.joining(", "));
                throw new BusinessException("Rạp " + cinema.getName() + " tạm hết " + need.getKey().getName()
                        + ", chưa bán được " + combos + ". Vui lòng chọn món khác.");
            }
        }
    }

    /** A paid order takes its combos' ingredients out of the stock of the cinema where it is shown. */
    @Transactional
    public void takeForOrder(Booking booking) {
        Cinema cinema = booking.getShowtime().getRoom().getCinema();
        LocalDateTime now = LocalDateTime.now(clock);
        needs(booking.getCombos()).forEach((item, quantity) ->
                movementRepository.save(movement(cinema, item, quantity.negate(), booking, "Bán combo, đơn " + booking.getCode(), now)));
    }

    /** A refunded order puts back exactly what it took. */
    @Transactional
    public void returnForOrder(Booking booking) {
        LocalDateTime now = LocalDateTime.now(clock);
        for (InventoryMovement taken : movementRepository.findByBookingId(booking.getId())) {
            if (taken.getQuantity().signum() < 0) {
                movementRepository.save(movement(taken.getCinema(), taken.getItem(), taken.getQuantity().negate(), booking,
                        "Hoàn kho, huỷ đơn " + booking.getCode(), now));
            }
        }
    }

    private static InventoryMovement movement(Cinema cinema, InventoryItem item, BigDecimal quantity, Booking booking,
                                              String reason, LocalDateTime now) {
        InventoryMovement m = new InventoryMovement();
        m.setCinema(cinema);
        m.setItem(item);
        m.setQuantity(quantity);
        m.setBooking(booking);
        m.setReason(reason);
        m.setCreatedAt(now);
        return m;
    }

    // ------------------------------------------------------------------ goods-received notes

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
    public StockRequest request(Long managerId, Map<Long, BigDecimal> quantities, String note) {
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
            BigDecimal quantity = quantities.get(item.getId());
            if (quantity == null || quantity.signum() == 0) {
                continue;
            }
            if (quantity.signum() < 0 || quantity.compareTo(MAX_QUANTITY) > 0) {
                throw new BusinessException("Số lượng " + item.getName() + " phải lớn hơn 0 và không quá " + MAX_QUANTITY.toPlainString());
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

    /** The general manager adds an item to the stock list, counted in one of the standard units. */
    @Transactional
    public InventoryItem addItem(String name, String unit) {
        if (name == null || name.isBlank()) {
            throw new BusinessException("Nhập tên mặt hàng");
        }
        if (unit == null || !InventoryItem.UNITS.contains(unit.trim())) {
            throw new BusinessException("Chọn đơn vị tính: " + String.join(", ", InventoryItem.UNITS));
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
