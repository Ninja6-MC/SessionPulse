package com.ninja6.sessionpulse.storage;

import com.ninja6.sessionpulse.session.SessionSnapshot;

/**
 * Everything storage keeps about one player: the counted state and the cooldown.
 *
 * <p>One field more than {@link SessionSnapshot}, and that is the whole difference. The
 * snapshot is what the tracker hands across; the cooldown is written by enforcement, from a
 * different thread, and neither writer may overwrite the half it does not own. The two
 * {@code with} methods are shaped around that rule: each replaces only its own fields.
 *
 * <p>Every numeric field is epoch milliseconds or whole seconds, for the same reason the
 * snapshot's are. Zero is never a real calendar value here: {@code lastSeenMillis == 0}
 * means "never recorded", and {@code cooldownExpiresMillis == 0} means "no cooldown".
 *
 * @param name                  the player's name when the record was last written, or
 *                              {@code null} if no writer ever supplied one
 * @param lifetimeSeconds       counted seconds over the player's whole history
 * @param windowStartMillis     calendar time the current counted window began
 * @param windowSeconds         counted seconds banked in the current window
 * @param lastSeenMillis        calendar time the counted state was last stamped, or
 *                              {@code 0} if it never was
 * @param cooldownExpiresMillis calendar time the player's cooldown ends, or {@code 0} for
 *                              none
 */
public record StoredPlayer(String name,
                           long lifetimeSeconds,
                           long windowStartMillis,
                           long windowSeconds,
                           long lastSeenMillis,
                           long cooldownExpiresMillis) {

    /**
     * A record holding only counted state.
     *
     * @param snapshot what the tracker stored
     * @return a record with no cooldown
     */
    static StoredPlayer fromSession(SessionSnapshot snapshot) {
        return new StoredPlayer(snapshot.name(), snapshot.lifetimeSeconds(),
                snapshot.windowStartMillis(), snapshot.windowSeconds(),
                snapshot.lastSeenMillis(), 0L);
    }

    /**
     * A record holding only a cooldown.
     *
     * <p>Its counted state is all zero, so {@link #toSnapshot()} reads as unknown and the
     * player's next join starts a fresh window. That is correct for a player storage has
     * never counted, and the cooldown is what keeps them out in the meantime.
     *
     * @param name          the player's name, or {@code null}
     * @param expiresMillis when the cooldown ends
     * @return a record with no counted state
     */
    static StoredPlayer fromCooldown(String name, long expiresMillis) {
        return new StoredPlayer(name, 0L, 0L, 0L, 0L, expiresMillis);
    }

    /**
     * This record with its counted state replaced and its cooldown untouched.
     *
     * @param snapshot the new counted state. Its name replaces this one only when present
     * @return the updated record
     */
    StoredPlayer withSession(SessionSnapshot snapshot) {
        return new StoredPlayer(snapshot.name() != null ? snapshot.name() : name,
                snapshot.lifetimeSeconds(), snapshot.windowStartMillis(),
                snapshot.windowSeconds(), snapshot.lastSeenMillis(), cooldownExpiresMillis);
    }

    /**
     * This record with its cooldown replaced and its counted state untouched.
     *
     * @param newName       replaces this record's name only when non-null
     * @param expiresMillis when the cooldown ends
     * @return the updated record
     */
    StoredPlayer withCooldown(String newName, long expiresMillis) {
        return new StoredPlayer(newName != null ? newName : name, lifetimeSeconds,
                windowStartMillis, windowSeconds, lastSeenMillis, expiresMillis);
    }

    /**
     * The counted half, in the shape the tracker reads.
     *
     * @return the snapshot, which is unknown when {@link #lastSeenMillis()} is {@code 0}
     */
    public SessionSnapshot toSnapshot() {
        return new SessionSnapshot(name, lifetimeSeconds, windowStartMillis, windowSeconds,
                lastSeenMillis);
    }
}
