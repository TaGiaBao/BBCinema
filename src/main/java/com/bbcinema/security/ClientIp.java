package com.bbcinema.security;

import com.bbcinema.config.AppSecurityProperties;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

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
}
