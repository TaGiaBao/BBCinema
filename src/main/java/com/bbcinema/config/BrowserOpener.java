package com.bbcinema.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * When started by run.bat (app.open-browser=true), opens the home page in the default browser as soon as
 * the application is ready. Only once per run: DevTools restarts after a code change do not open new tabs.
 */
@Slf4j
@Component
public class BrowserOpener {

    private static final String OPENED = "bbcinema.browserOpened";

    private final boolean enabled;
    private final String baseUrl;

    public BrowserOpener(@Value("${app.open-browser:false}") boolean enabled, @Value("${app.base-url}") String baseUrl) {
        this.enabled = enabled;
        this.baseUrl = baseUrl;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void open() {
        // A JVM-wide flag: DevTools reloads the application classes but keeps the same JVM.
        if (!enabled || System.getProperty(OPENED) != null
                || !System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("windows")) {
            return;
        }
        System.setProperty(OPENED, "true");
        try {
            new ProcessBuilder("rundll32", "url.dll,FileProtocolHandler", baseUrl).start();
            log.info("Opened {} in the browser", baseUrl);
        } catch (Exception e) {
            log.info("Open {} in your browser", baseUrl);
        }
    }
}
