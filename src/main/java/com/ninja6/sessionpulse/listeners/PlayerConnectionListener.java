package com.ninja6.sessionpulse.listeners;

import com.ninja6.sessionpulse.session.SessionTracker;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * Starts and finishes a player's tracked session.
 *
 * <p>{@link EventPriority#MONITOR}, because this is purely observational: it changes
 * nothing about the join or the quit and cancels nothing, so it should run after every
 * plugin that might. Neither event is cancellable, so {@code ignoreCancelled} would mean
 * nothing and is omitted rather than written as decoration.
 *
 * <p>It holds the tracker and nothing else. The store is deliberately not wired here:
 * {@link SessionTracker} already holds it to load a joining player's window, and it saves
 * on quit itself. Two wiring sites for one store is a way to swap in the real
 * implementation on the load path only, and get a plugin that reads real windows and saves
 * into a void with nothing in the log.
 */
public final class PlayerConnectionListener implements Listener {

    private final SessionTracker tracker;

    /**
     * Creates the listener.
     *
     * @param tracker the tracker to start and finish sessions on
     */
    public PlayerConnectionListener(SessionTracker tracker) {
        this.tracker = tracker;
    }

    /**
     * Begins tracking, resuming the player's counted window or starting a new one.
     *
     * @param event the join
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        tracker.onJoin(player.getUniqueId(), player.getName());
    }

    /**
     * Credits the tail of the session and stores the result.
     *
     * @param event the quit
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        tracker.onQuit(event.getPlayer().getUniqueId());
    }
}
