package com.ninja6.sessionpulse.config;

/**
 * The optional layer that ends a session rather than commenting on it.
 *
 * <p>Off by default, and the README's promise depends on it staying that way: "It never
 * kicks anyone by default". The default is in the shipped file as well as here, so an
 * operator reading their own config sees the promise rather than having to trust it.
 *
 * @param enabled         off unless the operator turned it on
 * @param atMinutes       counted-window minutes at which the player is disconnected.
 *                        Measured against the counted window, never the session - reading
 *                        the session would let a player quit and rejoin for a fresh
 *                        allowance, which is the bypass the counted window exists to close.
 *                        Clamped to {@code 1-10080}
 * @param kickMessage     MiniMessage shown on the disconnect screen, never {@code null}.
 *                        Rendered through the legacy serializer, because
 *                        {@code Player#kickPlayer} is String-only on spigot-api
 * @param cooldownMinutes minutes before the player may rejoin. Clamped to {@code 1-1440}:
 *                        a zero would let them reconnect still over the limit and be
 *                        kicked again immediately, which is a boot loop rather than a
 *                        disabled cooldown. Turning enforcement off is how you disable it
 */
public record EnforcementPolicy(boolean enabled,
                                int atMinutes,
                                String kickMessage,
                                int cooldownMinutes) {
}
