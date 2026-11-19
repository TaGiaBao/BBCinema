package com.bbcinema.security;

import com.bbcinema.config.AppProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.bbcinema.dto.response.ApiError;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.authentication.session.NullAuthenticatedSessionStrategy;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.util.matcher.RequestMatcher;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * Stateless security with JWT. Browser pages keep the token in an HttpOnly cookie; REST clients
 * send it in the Authorization header. CSRF protection stays on for cookie-based requests.
 */
@Configuration
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final CustomUserDetailsService userDetailsService;
    private final AppProperties properties;
    private final ObjectMapper objectMapper;

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public AuthenticationManager authenticationManager(PasswordEncoder passwordEncoder) {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider();
        provider.setUserDetailsService(userDetailsService);
        provider.setPasswordEncoder(passwordEncoder);
        return provider::authenticate;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .csrf(csrf -> csrf
                        .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                        .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler())
                        // The JWT filter logs the user in on every request; keep the same CSRF token
                        // instead of rotating it each time, otherwise JavaScript calls would fail.
                        .sessionAuthenticationStrategy(new NullAuthenticatedSessionStrategy())
                        .requireCsrfProtectionMatcher(csrfRequired()))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/css/**", "/js/**", "/images/**", "/uploads/**", "/webjars/**",
                                "/favicon.ico", "/error", "/error/**").permitAll()
                        .requestMatchers("/", "/movies/**", "/schedule", "/cinemas", "/prices", "/news/**",
                                "/select-cinema", "/login", "/register", "/internal/login",
                                "/forgot-password", "/reset-password").permitAll()
                        .requestMatchers("/swagger-ui/**", "/swagger-ui.html", "/v3/api-docs/**").permitAll()
                        .requestMatchers("/payment/vnpay-return", "/payment/vnpay-ipn").permitAll()
                        .requestMatchers("/api/auth/**").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/movies/**", "/api/cinemas/**", "/api/showtimes/**",
                                "/api/ticket-prices", "/api/combos").permitAll()
                        .requestMatchers("/admin/**", "/api/admin/**").hasRole("ADMIN")
                        .requestMatchers("/staff/**").hasAnyRole("STAFF", "ADMIN")
                        .anyRequest().authenticated())
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint((request, response, e) -> onUnauthenticated(request, response))
                        .accessDeniedHandler((request, response, e) -> onForbidden(request, response)))
                .logout(logout -> logout
                        .logoutUrl("/logout")
                        .deleteCookies(properties.jwt().cookieName())
                        .logoutSuccessHandler((request, response, auth) -> response.sendRedirect(
                                request.getContextPath() + ("internal".equals(request.getParameter("area"))
                                        ? "/internal/login?logout" : "/?logout"))))
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                .headers(h -> h.frameOptions(f -> f.sameOrigin()));
        return http.build();
    }

    /** CSRF is needed only when the browser sends the cookie automatically, not for Bearer tokens. */
    private static RequestMatcher csrfRequired() {
        return request -> {
            String method = request.getMethod();
            if ("GET".equals(method) || "HEAD".equals(method) || "OPTIONS".equals(method) || "TRACE".equals(method)) {
                return false;
            }
            String auth = request.getHeader("Authorization");
            if (auth != null && auth.startsWith("Bearer ")) {
                return false;
            }
            String path = request.getRequestURI().substring(request.getContextPath().length());
            return !path.startsWith("/api/auth/") && !path.equals("/payment/vnpay-ipn");
        };
    }

    private static boolean isApi(HttpServletRequest request) {
        return request.getRequestURI().substring(request.getContextPath().length()).startsWith("/api/");
    }

    private void onUnauthenticated(HttpServletRequest request, HttpServletResponse response) throws IOException {
        if (isApi(request)) {
            writeJson(response, HttpServletResponse.SC_UNAUTHORIZED, "Unauthorized", "Vui lòng đăng nhập");
            return;
        }
        String target = request.getRequestURI().substring(request.getContextPath().length());
        if (request.getQueryString() != null) {
            target += "?" + request.getQueryString();
        }
        String loginPage = isInternalArea(target) ? "/internal/login" : "/login";
        response.sendRedirect(request.getContextPath() + loginPage + "?redirect="
                + URLEncoder.encode(target, StandardCharsets.UTF_8));
    }

    /** Pages for staff and admins; their visitors are sent to the internal login page. */
    static boolean isInternalArea(String path) {
        return path.equals("/admin") || path.startsWith("/admin/") || path.startsWith("/admin?")
                || path.equals("/staff") || path.startsWith("/staff/") || path.startsWith("/staff?");
    }

    private void onForbidden(HttpServletRequest request, HttpServletResponse response) throws IOException {
        if (isApi(request)) {
            writeJson(response, HttpServletResponse.SC_FORBIDDEN, "Forbidden", "Bạn không có quyền thực hiện thao tác này");
            return;
        }
        response.sendError(HttpServletResponse.SC_FORBIDDEN);
    }

    private void writeJson(HttpServletResponse response, int status, String error, String message) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        objectMapper.writeValue(response.getWriter(), ApiError.of(status, error, message));
    }

}
