package com.bbcinema.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.LocaleResolver;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.i18n.CookieLocaleResolver;
import org.springframework.web.servlet.i18n.LocaleChangeInterceptor;

import java.nio.file.Path;
import java.time.Duration;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final AppProperties properties;

    public WebConfig(AppProperties properties) {
        this.properties = properties;
    }

    /** The visitor's language (vi or en) is kept in the BB_LANG cookie for a year; Vietnamese by default. */
    @Bean
    public LocaleResolver localeResolver() {
        CookieLocaleResolver resolver = new CookieLocaleResolver("BB_LANG");
        resolver.setDefaultLocale(I18n.VIETNAMESE);
        resolver.setCookieMaxAge(Duration.ofDays(365));
        return resolver;
    }

    /** ?lang=en or ?lang=vi on any page switches the language. */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        LocaleChangeInterceptor interceptor = new LocaleChangeInterceptor() {
            @Override
            public boolean preHandle(jakarta.servlet.http.HttpServletRequest request,
                                     jakarta.servlet.http.HttpServletResponse response, Object handler)
                    throws jakarta.servlet.ServletException {
                String lang = request.getParameter(getParamName());
                // Only the two languages the site has; anything else is ignored.
                return lang == null || lang.equals("vi") || lang.equals("en") ? super.preHandle(request, response, handler) : true;
            }
        };
        interceptor.setParamName("lang");
        registry.addInterceptor(interceptor);
    }

    /** Serves uploaded images from the upload folder at /uploads/**. */
    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        String location = Path.of(properties.upload().dir()).toAbsolutePath().normalize().toUri().toString();
        registry.addResourceHandler("/uploads/**").addResourceLocations(location);
    }
}
