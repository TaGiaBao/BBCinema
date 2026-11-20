package com.bbcinema.util;

import java.security.SecureRandom;

/** Generates short booking codes without look-alike characters (no 0/O, 1/I/L). */
public final class CodeGenerator {

    private static final char[] ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789".toCharArray();
    private static final SecureRandom RANDOM = new SecureRandom();
    public static final String MEMBER_CODE_PREFIX = "BB";

    private CodeGenerator() {
    }

    public static String bookingCode() {
        return random(8);
    }

    /** Member code shown at the counter: "BB" followed by 8 random characters, e.g. BB7KQ2MXPA. */
    public static String memberCode() {
        return MEMBER_CODE_PREFIX + random(8);
    }

    /** True when the text has the shape of a member code (upper case, no look-alike characters). */
    public static boolean isMemberCode(String text) {
        return text != null && text.matches(MEMBER_CODE_PREFIX + "[ABCDEFGHJKMNPQRSTUVWXYZ2-9]{8}");
    }

    public static String random(int length) {
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(ALPHABET[RANDOM.nextInt(ALPHABET.length)]);
        }
        return sb.toString();
    }
}
