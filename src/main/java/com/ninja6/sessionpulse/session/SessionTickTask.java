package com.ninja6.sessionpulse.session;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The repeating task that drives the counted window.
 *
 * <p>It resolves a {@link java.util.UUID} to a {@link Player}, asks the {@link AfkGate}
 * about them, and hands both to {@link SessionTracker}. It performs no arithmetic and
 * holds no state, because everything worth testing lives in the tracker, which names no
 * Bukkit type at all.
 *
 * <p><strong>Why one second.</strong> The counted window is consumed at minute granularity,
 * so a longer period makes a milestone late by up to that period. The per-player cost is a
 * map lookup, one atomic exchange, one atomic add and one AFK query. Because accrual is
 * interval-based rather than "add the period", the period can change later with no
 * arithmetic consequence at all.
 */
public final class SessionTickTask implements Runnable {

    /** Ticks before the first run. Must be at least 1; the library promotes anything lower. */
    public static final long DELAY_TICKS = 20L;

    /** Ticks between runs. Twenty is one second. */
    public static final long PERIOD_TICKS = 20L;

    private final SessionTracker tracker;
    private final AfkGate afk;
    private final List<SessionObserver> observers;
    private final Logger logger;

    /**
     * Creates the tick.
     *
     * @param tracker   the arithmetic
     * @param afk       the away test, asked once per player per tick
     * @param observers who to tell afterwards, in order. May be empty
     * @param logger    where a misbehaving observer is reported
     */
    public SessionTickTask(SessionTracker tracker, AfkGate afk,
                           List<SessionObserver> observers, Logger logger) {
        this.tracker = tracker;
        this.afk = afk;
        this.observers = List.copyOf(observers);
        this.logger = logger;
    }

    @Override
    public void run() {
        // The map's own value view: weakly consistent, so a player quitting mid-iteration
        // neither throws nor needs a defensive copy every second.
        for (PlayerSession tracked : tracker.sessions()) {
            Player player = Bukkit.getPlayer(tracked.uuid());
            if (player == null) {
                // Between the quit event and the map removal, or an unusual disconnect.
                // Skipping costs no counted time, because the next successful tick claims
                // the whole interval since the last one. It does mean any AFK spell during
                // the gap is credited as active.
                //
                // Do NOT "fix" that by advancing the accrual mark on this path: that would
                // silently discard real playtime whenever a player is momentarily
                // unresolvable, which is a far worse error than crediting a few seconds of
                // idling.
                continue;
            }
            PlayerSession session = tracker.accrue(tracked.uuid(), afk.isAfk(player));
            if (session == null) {
                continue;
            }
            for (SessionObserver observer : observers) {
                try {
                    observer.afterAccrual(player, session);
                } catch (Throwable thrown) {
                    // A repeating task that throws is cancelled by the scheduler, and the
                    // plugin then stops counting with nothing further in the log. One
                    // broken consumer must not be able to do that.
                    logger.log(Level.WARNING,
                            "A session observer threw for " + tracked.name()
                                    + ". Counting continues.", thrown);
                }
            }
        }
    }
}
