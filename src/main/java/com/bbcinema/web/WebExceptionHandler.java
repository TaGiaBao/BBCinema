package com.bbcinema.web;

import com.bbcinema.exception.AccessDeniedBookingException;
import com.bbcinema.exception.BusinessException;
import com.bbcinema.exception.NotFoundException;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.annotation.Order;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;

/** Turns service errors into friendly pages for the Thymeleaf part of the site. */
@ControllerAdvice(basePackages = "com.bbcinema.web")
@Order(1)
@lombok.RequiredArgsConstructor
public class WebExceptionHandler {

    private final GlobalModelAdvice layoutModel;
    private final jakarta.servlet.http.HttpServletRequest request;

    @ExceptionHandler(NotFoundException.class)
    public String notFound(NotFoundException e, Model model, HttpServletResponse response) {
        response.setStatus(HttpServletResponse.SC_NOT_FOUND);
        model.addAttribute("status", 404);
        model.addAttribute("message", e.getMessage());
        request.setAttribute(com.bbcinema.audit.AuditInterceptor.ERROR_ATTRIBUTE, e.getMessage());
        layoutModel.addTo(model, request);
        return "error/message";
    }

    @ExceptionHandler(AccessDeniedBookingException.class)
    public String forbidden(AccessDeniedBookingException e, Model model, HttpServletResponse response) {
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        model.addAttribute("status", 403);
        model.addAttribute("message", e.getMessage());
        request.setAttribute(com.bbcinema.audit.AuditInterceptor.ERROR_ATTRIBUTE, e.getMessage());
        layoutModel.addTo(model, request);
        return "error/message";
    }

    @ExceptionHandler(BusinessException.class)
    public String business(BusinessException e, Model model, HttpServletResponse response) {
        response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
        model.addAttribute("status", 400);
        model.addAttribute("message", e.getMessage());
        request.setAttribute(com.bbcinema.audit.AuditInterceptor.ERROR_ATTRIBUTE, e.getMessage());
        layoutModel.addTo(model, request);
        return "error/message";
    }
}
