package com.ninja6.sessionpulse.session;

import org.bukkit.entity.Player;

/**
 * The one question the session tick asks about a player before crediting their time.
 *
 * <p>Deliberately the same shape as the detector the AFK issue will supply, so wiring the
 * real one in is a single constructor argument and no change here. Until then the plugin
 * runs on {@link #NEVER}, which is exactly the behaviour {@code tracking.afk.mode: OFF}
 * describes: nobody is ever AFK and the clock never pauses.
 *
 * <p>Called once per online player per tick, on the region thread, so an implementation
 * must be cheap and must not block.
 */
@FunctionalInterface
public interface AfkGate {

    /**
     * Whether this player is currently away.
     *
     * @param player the player, never {@code null}
     * @return {@code true} to pause their counted time for this tick
     */
    boolean isAfk(Player player);

    /** Nobody is ever AFK. The behaviour of {@code tracking.afk.mode: OFF}. */
    AfkGate NEVER = player -> false;
}
