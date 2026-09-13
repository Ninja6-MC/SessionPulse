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
 * <p>The store is deliberately not wired here: {@link SessionTracker} already holds it to
 * load a joining player's window, and it saves on quit itself. Two wiring sites for one
 * store is a way to swap in the real implementation on the load path only, and get a
 * plugin that reads real windows and saves into a void with nothing in the log. What this
 * class is handed instead is a request to flush, which can store nothing on its own.
 */
public final class PlayerConnectionListener implements Listener {

    private final SessionTracker tracker;
    private final Runnable afterQuit;

    /**
     * Creates the listener.
     *
     * @param tracker   the tracker to start and finish sessions on
     * @param afterQuit run once the quitting player's session has been stored; the plugin
     *                  passes a request to flush storage off the server thread
     */
    public PlayerConnectionListener(SessionTracker tracker, Runnable afterQuit) {
        this.tracker = tracker;
        this.afterQuit = afterQuit;
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
     * Credits the tail of the session, stores the result, then asks for it to be flushed.
     *
     * <p>In that order. A flush requested before the save could run and find nothing new,
     * and the quit would then wait for the periodic flush.
     *
     * @param event the quit
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        tracker.onQuit(event.getPlayer().getUniqueId());
        afterQuit.run();
    }
}
