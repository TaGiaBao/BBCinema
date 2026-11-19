package com.bbcinema.security;

import com.bbcinema.service.GoogleLoginService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;

/** After Google confirms who the visitor is: sign them in with our own JWT cookie and drop the session. */
@Component
@RequiredArgsConstructor
public class GoogleLoginSuccessHandler implements AuthenticationSuccessHandler {

    private final GoogleLoginService googleLoginService;
    private final TokenCookies tokenCookies;

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response, Authentication auth)
            throws IOException {
        OAuth2User google = (OAuth2User) auth.getPrincipal();
        String target;
        try {
            AppUserPrincipal user = googleLoginService.signIn(google.getAttribute("email"),
                    Boolean.TRUE.equals(google.getAttribute("email_verified")), google.getAttribute("name"));
            tokenCookies.signIn(response, user);
            target = "/";
        } catch (GoogleLoginService.GoogleLoginException e) {
            target = "/login?google=" + e.getCode();
        }
        // The app is stateless: the session only carried the OAuth handshake.
        SecurityContextHolder.clearContext();
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        response.sendRedirect(request.getContextPath() + target);
    }
}
