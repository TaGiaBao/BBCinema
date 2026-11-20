package com.bbcinema.dto.response;

/**
 * What counter staff see after looking up a member: enough to greet them and price the order, nothing more
 * (no e-mail, no full phone number, no point balance). {@code age} is null when the birth date is unknown.
 */
public record MemberCard(
        Long id,
        String fullName,
        String memberCode,
        String maskedPhone,
        String tier,
        String tierLabel,
        int discountPercent,
        Integer age) {
}
