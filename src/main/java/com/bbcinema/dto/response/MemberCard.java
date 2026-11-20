package com.bbcinema.dto.response;

/**
 * What counter staff see after looking up a member: enough to greet them and price the order, nothing more
 * (no account id, no e-mail, no full phone number, no point balance). {@code age} is null when the birth date is
 * unknown. {@code matchedBy} is CODE or PHONE: a phone match needs staff to confirm the name with the customer.
 * The sale form sends back {@code memberCode}, which the server resolves again.
 */
public record MemberCard(
        String fullName,
        String memberCode,
        String maskedPhone,
        String tier,
        String tierLabel,
        int discountPercent,
        Integer age,
        String matchedBy) {
}
