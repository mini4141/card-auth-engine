package com.cardauth.domain;

import java.time.Instant;

/** Published to card.authorizations (key = cardId) for every decision. */
public record AuthorizationEvent(
        String authId, String cardId, long amount, String merchant, String mcc,
        String decision, String reason, String strategy, long availableAfter, Instant ts) {

    public boolean approved() {
        return Decision.APPROVED.name().equals(decision);
    }
}
