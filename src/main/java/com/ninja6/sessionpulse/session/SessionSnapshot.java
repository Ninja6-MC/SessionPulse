package com.ninja6.sessionpulse.session;

/**
 * A player's counted state as it crosses to and from storage.
 *
 * <p>Every field here is either epoch milliseconds or a whole second count. No nanosecond
 * value appears in this type and none ever may: a monotonic reading is meaningless in the
 * next run of the JVM, and a persisted one would silently produce nonsense offline gaps
 * after every restart. {@code ClockSeamTest} checks the naming half of that mechanically;
 * {@code SessionTrackerTest} checks the values.
 *
 * @param name              the player's name at the time the record was written, so
 *                          {@code /spulse top} can list an offline player
 * @param lifetimeSeconds   counted seconds over this player's whole history
 * @param windowStartMillis calendar time the current counted window began
 * @param windowSeconds     counted seconds banked in the current window
 * @param lastSeenMillis    calendar time this record was last stamped. Written on quit
 *                          <em>and</em> by every periodic flush for players who are still
 *                          online - see {@link SessionTracker#snapshotAll()}. The offline
 *                          gap that decides a window reset is measured from it
 */
public record SessionSnapshot(String name,
                              long lifetimeSeconds,
                              long windowStartMillis,
                              long windowSeconds,
                              long lastSeenMillis) {

    /**
     * What storage hands back for a player it has never seen.
     *
     * <p>A constant rather than {@code null}, so no caller has to remember the null check
     * and no join path can throw on a first-time player. The shape is the house pattern
     * for a do-nothing default.
     */
    public static final SessionSnapshot UNKNOWN = new SessionSnapshot(null, 0L, 0L, 0L, 0L);

    /**
     * Whether this stands for "no record", rather than for a real one.
     *
     * <p>Keyed on {@code lastSeenMillis}, because a genuine record always carries a stamp:
     * a player with zero counted seconds who has been seen is still a player who has been
     * seen, and must not be handed a fresh window on that basis alone.
     *
     * @return {@code true} when storage had nothing for this player
     */
    public boolean isUnknown() {
        return lastSeenMillis <= 0L;
    }
}
