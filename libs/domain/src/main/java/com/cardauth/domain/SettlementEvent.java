package com.cardauth.domain;

import java.time.Instant;

/** Published to card.settlements after a ledger posting. */
public record SettlementEvent(String authId, String cardId, long amount, Instant postedAt) {}
