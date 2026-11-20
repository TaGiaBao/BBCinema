package com.bbcinema.security;

import com.bbcinema.config.AppSecurityProperties;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * The address of the client that sent a request. X-Forwarded-For is read only when app.security.trust-forwarded-for
 * is on, i.e. the app runs behind a reverse proxy that writes the header; then the last address in the header is
 * used, because that is the one the proxy itself added (earlier entries come from the client and can be faked).
 */
@Component
@RequiredArgsConstructor
public class ClientIp {

    private final AppSecurityProperties properties;

    public String of(HttpServletRequest request) {
        if (properties.trustForwardedFor()) {
            String forwarded = request.getHeader("X-Forwarded-For");
            if (forwarded != null && !forwarded.isBlank()) {
                String[] parts = forwarded.split(",");
                String last = parts[parts.length - 1].trim();
                if (!last.isEmpty()) {
                    return last;
                }
            }
        }
        return request.getRemoteAddr();
    }

    /** The client address of the web request this thread is handling, or null outside a request. */
    public String ofCurrentRequest() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        return attributes instanceof ServletRequestAttributes servlet ? of(servlet.getRequest()) : null;
    }
}
