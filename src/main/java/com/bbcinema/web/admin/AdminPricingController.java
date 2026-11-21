package com.bbcinema.web.admin;

import com.bbcinema.dto.request.ComboForm;
import com.bbcinema.dto.request.HolidayForm;
import com.bbcinema.entity.Holiday;
import com.bbcinema.entity.enums.ScreenFormat;
import com.bbcinema.entity.enums.SeatType;
import com.bbcinema.entity.enums.TimeSlot;
import com.bbcinema.exception.BusinessException;
import com.bbcinema.exception.NotFoundException;
import com.bbcinema.service.ComboService;
import com.bbcinema.service.HolidayService;
import com.bbcinema.service.InventoryService;
import com.bbcinema.service.PricingService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Ticket price table, public holidays and snack combos. */
@Slf4j
@Controller
@RequestMapping("/admin")
@RequiredArgsConstructor
public class AdminPricingController {

    private final PricingService pricingService;
    private final ComboService comboService;
    private final InventoryService inventoryService;
    private final HolidayService holidayService;
    private final Clock clock;

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    @GetMapping("/prices")
    public String prices(Model model) {
        model.addAttribute("grid", pricingService.grid());
        model.addAttribute("menu", "prices");
        return "admin/prices";
    }

    @PostMapping("/prices")
    public String savePrices(@RequestParam Map<String, String> params, RedirectAttributes redirect) {
        Map<Long, BigDecimal> prices = new HashMap<>();
        Map<PricingService.HolidayCell, BigDecimal> newHolidayPrices = new HashMap<>();
        try {
            params.forEach((key, value) -> {
                if (key.startsWith("price_")) {
                    // Empty: allowed only on a holiday price, which then goes back to the weekend price.
                    prices.put(Long.parseLong(key.substring(6)), value == null || value.isBlank() ? null : parsePrice(value));
                } else if (key.startsWith(NEW_HOLIDAY_PREFIX) && value != null && !value.isBlank()) {
                    newHolidayPrices.put(holidayCell(key.substring(NEW_HOLIDAY_PREFIX.length())), parsePrice(value));
                }
            });
            int repriced = pricingService.updatePrices(prices, newHolidayPrices);
            String message = "Đã lưu bảng giá. Giá ngày thường và cuối tuần áp dụng cho các suất chiếu tạo sau.";
            if (repriced > 0) {
                message += " Đã cập nhật giá cho " + repriced + " ghế còn trống của các suất chiếu ngày lễ sắp tới.";
            }
            redirect.addFlashAttribute("success", message);
        } catch (NumberFormatException e) {
            redirect.addFlashAttribute("error", "Giá vé phải là số");
        } catch (BusinessException e) {
            redirect.addFlashAttribute("error", e.getMessage());
        } catch (DataIntegrityViolationException e) {
            // e.g. a database created before holiday prices whose day_type column does not accept HOLIDAY yet
            log.warn("Saving the price table failed: {}", e.getMostSpecificCause().getMessage());
            redirect.addFlashAttribute("error", "Không lưu được bảng giá do cơ sở dữ liệu chưa được cập nhật. "
                    + "Vui lòng khởi động lại ứng dụng rồi thử lại.");
        }
        return "redirect:/admin/prices";
    }

    /** Name of the input for a holiday price not set yet: holiday_{SEAT}_{FORMAT}_{SLOT}, e.g. holiday_VIP_TWO_D_FROM_17. */
    static final String NEW_HOLIDAY_PREFIX = "holiday_";

    private static BigDecimal parsePrice(String value) {
        return new BigDecimal(value.trim().replace(".", ""));
    }

    static PricingService.HolidayCell holidayCell(String key) {
        for (SeatType seatType : SeatType.values()) {
            for (ScreenFormat format : ScreenFormat.values()) {
                for (TimeSlot slot : TimeSlot.values()) {
                    if (key.equals(seatType.name() + "_" + format.name() + "_" + slot.name())) {
                        return new PricingService.HolidayCell(seatType, format, slot);
                    }
                }
            }
        }
        throw new BusinessException("Ô giá ngày lễ không hợp lệ");
    }

    // ------------------------------------------------------------------ public holidays

    @GetMapping("/prices/holidays")
    public String holidays(Model model) {
        LocalDate today = LocalDate.now(clock);
        List<Holiday> all = holidayService.findAll();
        model.addAttribute("upcoming", all.stream().filter(h -> !h.getDate().isBefore(today)).toList());
        model.addAttribute("past", all.stream().filter(h -> h.getDate().isBefore(today))
                .sorted(Comparator.comparing(Holiday::getDate).reversed()).toList());
        if (!model.containsAttribute("form")) {
            model.addAttribute("form", new HolidayForm());
        }
        model.addAttribute("menu", "prices");
        return "admin/holidays";
    }

    @PostMapping("/prices/holidays")
    public String addHoliday(@Valid @ModelAttribute("form") HolidayForm form, BindingResult binding,
                             Model model, RedirectAttributes redirect) {
        if (binding.hasErrors()) {
            return holidays(model);
        }
        try {
            HolidayService.Change change = holidayService.add(form.getDate(), form.getName());
            redirect.addFlashAttribute("success", "Đã thêm ngày lễ " + change.holiday().getName() + " ("
                    + DATE.format(change.holiday().getDate()) + "). Đã cập nhật giá ngày lễ cho "
                    + change.repricedSeats() + " ghế còn trống của các suất chiếu ngày này.");
        } catch (BusinessException e) {
            binding.reject("holiday", e.getMessage());
            return holidays(model);
        } catch (DataIntegrityViolationException e) {
            binding.reject("holiday", "Ngày " + DATE.format(form.getDate()) + " đã có trong danh sách ngày lễ");
            return holidays(model);
        }
        return "redirect:/admin/prices/holidays";
    }

    @PostMapping("/prices/holidays/{id}/delete")
    public String deleteHoliday(@PathVariable Long id, RedirectAttributes redirect) {
        try {
            HolidayService.Change change = holidayService.delete(id);
            redirect.addFlashAttribute("success", "Đã xoá ngày lễ " + change.holiday().getName() + " ("
                    + DATE.format(change.holiday().getDate()) + "). Đã trả giá thường cho "
                    + change.repricedSeats() + " ghế còn trống của các suất chiếu ngày này.");
        } catch (NotFoundException | BusinessException e) {
            redirect.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/admin/prices/holidays";
    }

    @GetMapping("/combos")
    public String combos(Model model) {
        model.addAttribute("combos", comboService.findAll());
        model.addAttribute("recipes", inventoryService.recipes());
        if (!model.containsAttribute("form")) {
            model.addAttribute("form", new ComboForm());
        }
        model.addAttribute("menu", "combos");
        return "admin/combos";
    }

    /** What one portion of a combo takes from the stock; selling it then takes this from the cinema's stock. */
    @GetMapping("/combos/{id}/recipe")
    public String recipe(@PathVariable Long id, Model model) {
        model.addAttribute("combo", comboService.getById(id));
        model.addAttribute("items", inventoryService.items());
        java.util.Map<Long, java.math.BigDecimal> current = new java.util.HashMap<>();
        inventoryService.recipes().getOrDefault(id, java.util.List.of())
                .forEach(i -> current.put(i.getItem().getId(), i.getQuantity().stripTrailingZeros()));
        model.addAttribute("current", current);
        model.addAttribute("menu", "combos");
        return "admin/combo-recipe";
    }

    @PostMapping("/combos/{id}/recipe")
    public String saveRecipe(@PathVariable Long id, @RequestParam java.util.Map<String, String> params, RedirectAttributes redirect) {
        java.util.Map<Long, java.math.BigDecimal> quantities = AdminInventoryController.quantities(params, "use_");
        if (quantities == null) {
            redirect.addFlashAttribute("error", "Định lượng phải là số, ví dụ 1 hoặc 0,15");
            return "redirect:/admin/combos/" + id + "/recipe";
        }
        try {
            inventoryService.saveRecipe(id, quantities);
            redirect.addFlashAttribute("success", "Đã lưu công thức combo " + comboService.getById(id).getName());
            return "redirect:/admin/combos";
        } catch (BusinessException e) {
            redirect.addFlashAttribute("error", e.getMessage());
            return "redirect:/admin/combos/" + id + "/recipe";
        }
    }

    @GetMapping("/combos/{id}/edit")
    public String editCombo(@PathVariable Long id, Model model) {
        model.addAttribute("form", comboService.toForm(comboService.getById(id)));
        return combos(model);
    }

    @PostMapping("/combos")
    public String saveCombo(@Valid @ModelAttribute("form") ComboForm form, BindingResult binding,
                            @RequestParam(name = "image", required = false) MultipartFile image,
                            Model model, RedirectAttributes redirect) {
        if (binding.hasErrors()) {
            return combos(model);
        }
        try {
            comboService.save(form, image);
        } catch (BusinessException e) {
            binding.reject("combo", e.getMessage());
            return combos(model);
        }
        redirect.addFlashAttribute("success", "Đã lưu combo " + form.getName());
        return "redirect:/admin/combos";
    }
}
