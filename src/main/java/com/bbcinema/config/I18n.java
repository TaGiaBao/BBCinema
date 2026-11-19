package com.bbcinema.config;

import org.springframework.context.MessageSource;
import org.springframework.context.NoSuchMessageException;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * Translations for code that cannot get the MessageSource injected (enum labels, template helpers).
 * Vietnamese is the default language: messages.properties holds the page texts, and enum labels
 * written in Vietnamese in the code are used whenever a translation is missing.
 */
@Component
public class I18n {

    public static final Locale VIETNAMESE = Locale.forLanguageTag("vi");
    private static MessageSource messages;

    public I18n(MessageSource messageSource) {
        I18n.messages = messageSource;
    }

    /** The message for the current visitor's language, or {@code fallback} if there is none. */
    public static String t(String key, String fallback, Object... args) {
        if (messages == null) {
            return fallback;
        }
        try {
            return messages.getMessage(key, args.length == 0 ? null : args, currentLocale());
        } catch (NoSuchMessageException e) {
            return fallback;
        }
    }

    /** Label of an enum constant: enum.SeatType.VIP in the messages, else the Vietnamese label. */
    public static String label(Enum<?> value, String vietnamese) {
        return t("enum." + value.getDeclaringClass().getSimpleName() + "." + value.name(), vietnamese);
    }

    public static boolean isEnglish() {
        return "en".equals(currentLocale().getLanguage());
    }

    /**
     * The visitor's language during a web request. Outside one (scheduled jobs, e-mails, tests) it is
     * Vietnamese, not the language of the server's operating system.
     */
    public static Locale currentLocale() {
        return LocaleContextHolder.getLocaleContext() != null && LocaleContextHolder.getLocaleContext().getLocale() != null
                ? LocaleContextHolder.getLocaleContext().getLocale() : VIETNAMESE;
    }
}
