package com.bbcinema.web.admin;

import com.bbcinema.service.ComboService;
import com.bbcinema.service.HolidayService;
import com.bbcinema.service.InventoryService;
import com.bbcinema.service.PricingService;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap;

import java.time.Clock;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AdminPricingControllerTest {

    /** A dev database whose day_type column was not widened yet refuses the HOLIDAY row: a message, not a 500. */
    @Test
    void databaseRefusingTheSaveShowsAnError() {
        PricingService pricingService = mock(PricingService.class);
        when(pricingService.updatePrices(anyMap(), anyMap()))
                .thenThrow(new DataIntegrityViolationException("Value not permitted for column DAY_TYPE: 'HOLIDAY'"));
        AdminPricingController controller = new AdminPricingController(pricingService, mock(ComboService.class),
                mock(InventoryService.class), mock(HolidayService.class), Clock.systemDefaultZone());
        RedirectAttributesModelMap redirect = new RedirectAttributesModelMap();

        String view = controller.savePrices(Map.of("holiday_VIP_TWO_D_FROM_17", "120000"), redirect);

        assertThat(view).isEqualTo("redirect:/admin/prices");
        assertThat((String) redirect.getFlashAttributes().get("error")).startsWith("Không lưu được bảng giá");
        assertThat(redirect.getFlashAttributes()).doesNotContainKey("success");
    }
}
