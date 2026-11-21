package com.bbcinema.audit;

import com.bbcinema.security.AppUserPrincipal;
import com.bbcinema.security.ClientIp;
import com.bbcinema.service.AuditService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.ui.ModelMap;
import org.springframework.validation.BindingResult;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.FlashMap;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.support.RequestContextUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Logs every controller action marked {@link Audited} once it has finished: who, what, on which record and
 * whether it worked. It failed when it threw, answered with an error status, redirected with an "error" flash
 * message, or showed its form again because the input was invalid. The flash message, when there is one,
 * becomes the detail ("Đã xoá phim ...").
 */
@Component
@RequiredArgsConstructor
public class AuditInterceptor implements HandlerInterceptor {

    /** An exception handler that turned the action's exception into a page puts the message here. */
    public static final String ERROR_ATTRIBUTE = AuditInterceptor.class.getName() + ".error";

    private static final String INVALID_ATTRIBUTE = AuditInterceptor.class.getName() + ".invalid";
    /** Request parameters that name the record when the URL does not (check-in by code, new account). */
    private static final List<String> TARGET_PARAMS = List.of("code", "email");

    private final AuditService auditService;
    private final ClientIp clientIp;

    @Override
    public void postHandle(HttpServletRequest request, HttpServletResponse response, Object handler,
                           ModelAndView modelAndView) {
        if (modelAndView == null || audited(handler) == null) {
            return;
        }
        ModelMap model = modelAndView.getModelMap();
        for (Object value : model.values()) {
            if (value instanceof BindingResult binding && binding.hasErrors()) {
                request.setAttribute(INVALID_ATTRIBUTE, Boolean.TRUE);
                return;
            }
        }
        if (model.get("error") instanceof String error && !error.isBlank()) {
            request.setAttribute(ERROR_ATTRIBUTE, error);
        }
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler,
                                Exception ex) {
        Audited audited = audited(handler);
        if (audited == null) {
            return;
        }
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof AppUserPrincipal user)) {
            return;
        }
        FlashMap flash = RequestContextUtils.getOutputFlashMap(request);
        String error = firstText(ex == null ? null : ex.getMessage(),
                request.getAttribute(ERROR_ATTRIBUTE), flash == null ? null : flash.get("error"));
        boolean invalid = Boolean.TRUE.equals(request.getAttribute(INVALID_ATTRIBUTE));
        boolean success = ex == null && error == null && !invalid && response.getStatus() < 400;
        String detail;
        if (success) {
            detail = firstText(flash == null ? null : flash.get("success"));
        } else if (invalid) {
            detail = "Dữ liệu nhập chưa hợp lệ";
        } else {
            detail = error != null ? error : "Lỗi " + response.getStatus();
        }
        auditService.record(new AuditService.Entry(user.getId(), user.getEmail(), user.getRole(), audited.value(),
                target(request), detail, success, request.getMethod(), request.getRequestURI(), clientIp.of(request)));
    }

    private static Audited audited(Object handler) {
        return handler instanceof HandlerMethod method ? method.getMethodAnnotation(Audited.class) : null;
    }

    /** The record acted on, from the URL ("id=12") or a naming parameter ("code=K7M2QX9P"). */
    @SuppressWarnings("unchecked")
    private static String target(HttpServletRequest request) {
        List<String> parts = new ArrayList<>();
        Object vars = request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        if (vars instanceof Map<?, ?> map) {
            ((Map<String, String>) map).forEach((name, value) -> parts.add(name + "=" + value));
        }
        for (String name : TARGET_PARAMS) {
            String value = request.getParameter(name);
            if (value != null && !value.isBlank()) {
                parts.add(name + "=" + value.trim());
            }
        }
        return parts.isEmpty() ? null : String.join(", ", parts);
    }

    private static String firstText(Object... values) {
        for (Object value : values) {
            if (value instanceof String text && !text.isBlank()) {
                return text;
            }
        }
        return null;
    }
}
