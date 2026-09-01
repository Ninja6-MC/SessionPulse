package com.ninja6.sessionpulse.session;

import java.util.UUID;

/**
 * Where counted state comes from and goes back to.
 *
 * <p>Two methods, and deliberately no more. The storage issue implements this, or adapts
 * its own data store to it; until then {@link #EMPTY} lets the tracker boot, count and
 * roll a window over on its own, forgetting everything on restart.
 *
 * <h2>What this interface assumes of its implementation</h2>
 *
 * <ol>
 *   <li>{@link #load(UUID)} is an <strong>in-memory read</strong>, not disk I/O. It is
 *       called from the join event, on the server thread. An implementation that touches
 *       the disk here stalls every login.</li>
 *   <li>{@link #save(UUID, SessionSnapshot)} updates the in-memory record and marks it
 *       dirty. It does not itself write to disk; the flush task owns that.</li>
 *   <li>The stored record carries a <strong>last-seen calendar time</strong>. The counted
 *       window resets after an offline gap, and offline gap is
 *       {@code now - lastSeen}. It cannot be derived from the window start: a player
 *       online continuously for nine hours has a nine-hour-old window start and must not
 *       reset.</li>
 * </ol>
 */
public interface SessionStore {

    /**
     * Reads a player's stored counted state.
     *
     * @param uuid the player
     * @return their record, or {@link SessionSnapshot#UNKNOWN} if there is none. Never
     *         {@code null}
     */
    SessionSnapshot load(UUID uuid);

    /**
     * Writes a player's counted state back.
     *
     * @param uuid     the player
     * @param snapshot what to store, never {@code null}
     */
    void save(UUID uuid, SessionSnapshot snapshot);

    /**
     * A store that remembers nothing.
     *
     * <p>Every player is new, every window starts fresh, and a restart forgets the lot.
     * This is what the plugin runs on until the storage issue lands, and it is what makes
     * this issue shippable on its own rather than half of a pair.
     */
    SessionStore EMPTY = new SessionStore() {

        @Override
        public SessionSnapshot load(UUID uuid) {
            return SessionSnapshot.UNKNOWN;
        }

        @Override
        public void save(UUID uuid, SessionSnapshot snapshot) {
            // Deliberately nothing.
        }

        @Override
        public String toString() {
            return "SessionStore.EMPTY";
        }
    };
}
