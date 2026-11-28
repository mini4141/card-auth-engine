package com.cardauth.domain;

public final class RedisKeys {
    public static String available(String cardId) { return "card:" + cardId + ":available"; }
    public static String holds(String cardId) { return "card:" + cardId + ":holds"; }
    public static String profile(String cardId) { return "card:" + cardId + ":profile"; }
    public static String idem(String key) { return "idem:" + key; }
    public static String auth(String authId) { return "auth:" + authId; }
    public static String lock(String cardId) { return "lock:card:" + cardId; }
    private RedisKeys() {}
}
