package com.cardauth.auth.strategy;

/** One concurrency strategy for placing and releasing a hold on a card's available credit. */
public interface AuthorizationStrategy {

    record HoldResult(boolean approved, long availableAfter, String reason) {
        public static HoldResult approved(long availableAfter) { return new HoldResult(true, availableAfter, "OK"); }
        public static HoldResult declined(long available, String reason) { return new HoldResult(false, available, reason); }
    }

    String name();

    HoldResult hold(String cardId, String authId, long amount);

    void release(String cardId, String authId, long amount);
}
