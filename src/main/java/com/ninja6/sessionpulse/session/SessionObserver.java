package com.ninja6.sessionpulse.session;

import org.bukkit.entity.Player;

/**
 * Notified after a player's time has been credited for a tick.
 *
 * <p>The hook the milestone, overtime and enforcement issues each register on. It is where
 * they <em>decide</em>, and only that: the session tick runs on the global region, which on
 * Folia may not touch a player's own data. Anything an observer actually wants to do to a
 * player - send a message, play a sound, disconnect them - is handed to
 * {@code Scheduler#entity} for the region that owns them.
 *
 * <p>No observer is registered in this issue. Nothing this plugin does yet puts anything on
 * a player's screen.
 */
@FunctionalInterface
public interface SessionObserver {

    /**
     * Called once per player per tick, immediately after accrual.
     *
     * <p>Must not throw, must not block, and must not touch the player's data directly.
     * The tick catches and logs anything thrown here rather than letting one bad observer
     * kill the repeating task and silently stop the plugin counting - but a thrown
     * exception is still a defect in the observer.
     *
     * @param player  the player, resolved and online
     * @param session their session, with this tick already credited
     */
    void afterAccrual(Player player, PlayerSession session);
}
