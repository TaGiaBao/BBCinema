package com.bbcinema.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.oauth2.client.CommonOAuth2Provider;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;

/**
 * "Đăng nhập bằng Google". Only switched on when app.google.client-id (GOOGLE_CLIENT_ID) is set;
 * otherwise the button is hidden and nothing about OAuth is active. Create the client in Google Cloud
 * Console (APIs &amp; Services, Credentials, OAuth client ID, Web application) with the redirect URI
 * {APP_BASE_URL}/login/oauth2/code/google.
 */
@Configuration
@ConditionalOnExpression("!'${app.google.client-id:}'.isBlank()")
public class GoogleLoginConfig {

    @Bean
    public ClientRegistrationRepository clientRegistrationRepository(
            @Value("${app.google.client-id}") String clientId,
            @Value("${app.google.client-secret:}") String clientSecret) {
        return new InMemoryClientRegistrationRepository(CommonOAuth2Provider.GOOGLE.getBuilder("google")
                .clientId(clientId)
                .clientSecret(clientSecret)
                .scope("openid", "profile", "email")
                .build());
    }
}
