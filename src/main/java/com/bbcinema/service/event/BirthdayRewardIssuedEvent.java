package com.bbcinema.service.event;

/** A member was given this year's birthday code; the congratulation e-mail is sent after the transaction commits. */
public record BirthdayRewardIssuedEvent(Long rewardId) {
}
