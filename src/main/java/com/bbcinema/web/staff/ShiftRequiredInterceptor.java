package com.bbcinema.web.staff;

import com.bbcinema.entity.enums.Role;
import com.bbcinema.security.AppUserPrincipal;
import com.bbcinema.service.ShiftService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/** Counter staff work inside a till shift: without an open one, every /staff page leads to "open shift". */
@Component
@RequiredArgsConstructor
public class ShiftRequiredInterceptor implements HandlerInterceptor {

    private final ShiftService shiftService;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof AppUserPrincipal user && user.getRole() == Role.STAFF
                && shiftService.findOpen(user.getId()).isEmpty()) {
            response.sendRedirect(request.getContextPath() + ShiftController.OPEN_PATH);
            return false;
        }
        return true;
    }
}
