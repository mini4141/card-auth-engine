package com.cardauth.domain;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** Atomic available-credit scripts shared by auth-service (hold/release) and settlement-service (capture). */
public final class LuaScripts {
    /** KEYS: available, holds. ARGV: authId, amount. Returns remaining available, -1 declined, -2 card not primed. */
    public static final String HOLD = load("lua/hold.lua");
    /** KEYS: available, holds. ARGV: authId. Returns released amount, 0 if no such hold. */
    public static final String RELEASE = load("lua/release.lua");
    /** KEYS: holds. ARGV: authId. Returns captured amount, 0 if no such hold. */
    public static final String CAPTURE = load("lua/capture.lua");

    private static String load(String path) {
        try (InputStream in = LuaScripts.class.getClassLoader().getResourceAsStream(path)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
    private LuaScripts() {}
}
