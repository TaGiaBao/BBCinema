package com.bbcinema.web;

/** Only allows redirects to paths of this site, so a login link cannot send people to another website. */
public final class SafeRedirect {

    private SafeRedirect() {
    }

    public static String localPath(String target, String fallback) {
        if (target == null || target.isBlank()) {
            return fallback;
        }
        if (!target.startsWith("/") || target.startsWith("//") || target.startsWith("/\\") || target.contains("\r") || target.contains("\n")) {
            return fallback;
        }
        return target;
    }
}
