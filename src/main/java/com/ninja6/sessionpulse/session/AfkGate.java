package com.ninja6.sessionpulse.session;

import org.bukkit.entity.Player;

/**
 * The one question the session tick asks about a player before crediting their time.
 *
 * <p>The plugin passes {@code afk.AfkService}, which answers through whichever detector
 * {@code tracking.afk.mode} resolved to. This package names none of them, so the tracker's
 * tests run on {@link #NEVER}, the behaviour {@code tracking.afk.mode: OFF} describes:
 * nobody is ever AFK and the clock never pauses.
 *
 * <p>Called once per online player per tick, on the session tick's thread. On Folia that is
 * the global region, which does not own the player, so an implementation must not read
 * player data here. It must also be cheap, must not block, and must not throw: the tick
 * does not catch around it.
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
