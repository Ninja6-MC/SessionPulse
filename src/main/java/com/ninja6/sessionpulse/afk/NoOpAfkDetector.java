package com.ninja6.sessionpulse.afk;

import org.bukkit.entity.Player;

/**
 * Nobody is ever AFK.
 *
 * <p>What {@code tracking.afk.mode: OFF} asks for, what ESSENTIALS falls to when there is no
 * EssentialsX to ask, and what the plugin holds before the first resolve and after disable.
 */
public final class NoOpAfkDetector implements AfkDetector {

    /** The only instance. It holds nothing, so there is no reason for a second. */
    public static final NoOpAfkDetector INSTANCE = new NoOpAfkDetector();

    private NoOpAfkDetector() {
    }

    @Override
    public boolean isAfk(Player player) {
        return false;
    }
}
