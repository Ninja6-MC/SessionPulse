package com.ninja6.sessionpulse.afk;

import com.ninja6.sessionpulse.session.AfkGate;

import java.util.UUID;

/**
 * One way of deciding that a player has stopped playing.
 *
 * <p>An {@link AfkGate}, so the session tick asks it the same question it always asked and
 * needs no change to take a real one. What it adds is a place to drop per-player state when
 * the player leaves.
 *
 * <p>{@link #isAfk} is called from the session tick, which on Folia is the global region and
 * not the player's own. An implementation must not read player data there, must not block,
 * and must not throw: the tick does not catch around the gate, and a repeating task that
 * throws is cancelled and the plugin stops counting.
 */
public interface AfkDetector extends AfkGate {

    /**
     * Drops anything held for this player. Called on quit; harmless for a player never seen.
     *
     * @param uuid the player who left
     */
    default void forget(UUID uuid) {
    }
}
