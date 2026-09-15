package com.ninja6.sessionpulse.listeners;

import com.ninja6.sessionpulse.config.PluginConfig;
import com.ninja6.sessionpulse.notify.Notifier;
import com.ninja6.sessionpulse.notify.Placeholders;
import com.ninja6.sessionpulse.session.SessionClock;
import com.ninja6.sessionpulse.storage.DataStorage;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;

import java.util.function.Supplier;

/**
 * Refuses a connection while the player's enforcement cooldown is still running.
 *
 * <p>At pre-login, not at join: a join has already put the player in the world, and turning
 * them away from there is a second disconnect screen after a loading screen. Pre-login runs
 * off the server thread, on the connection's own, which is why everything here is an
 * in-memory read - {@link DataStorage#cooldownExpiresMillis} is a map lookup of an immutable
 * record, and {@link Notifier#legacy} renders without touching a player or audience.
 *
 * <p>{@link EventPriority#HIGH}, so a whitelist or ban plugin at the default priority has
 * already spoken, and a connection somebody else refused is left exactly as they left it.
 * Not {@code MONITOR}, because this does change the result.
 *
 * <p>With {@code enforcement.enabled: false} everybody is admitted, cooldown or not. Turning
 * enforcement off is the operator's release valve, and it has to open the door for the
 * players already outside it; the cooldowns stay on record and do nothing.
 *
 * <p>The refusal is the kick message again. {@code <cooldown>} is the time left;
 * {@code <hours>} and {@code <minutes>} are {@code at-minutes}, the limit in force,
 * because the stored window has been reset to zero by then and would read as nothing.
 *
 * <p>There is no {@code sessionpulse.exempt} check, because there is no player to ask before
 * login. None is needed: a cooldown is only ever written for a player who was not exempt
 * when they were disconnected.
 *
 * <p>The comparison is calendar against calendar, so a system clock moved backwards lengthens
 * a cooldown, and one moved forwards shortens it. Accepted, as it is for the window reset.
 */
public final class LoginGateListener implements Listener {

    private final DataStorage storage;
    private final SessionClock clock;
    private final Supplier<PluginConfig> config;
    private final Notifier notifier;

    /**
     * Creates the gate.
     *
     * @param storage  where cooldowns are recorded; loaded before this is registered
     * @param clock    the calendar reading an expiry is compared with
     * @param config   a supplier of the configuration in force, read per call
     * @param notifier renders the refusal
     */
    public LoginGateListener(DataStorage storage, SessionClock clock,
                             Supplier<PluginConfig> config, Notifier notifier) {
        this.storage = storage;
        this.clock = clock;
        this.config = config;
        this.notifier = notifier;
    }

    /**
     * Refuses the connection with the kick message if a cooldown is running.
     *
     * @param event the pre-login, on the connection's thread
     */
    @EventHandler(priority = EventPriority.HIGH)
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        if (event.getLoginResult() != AsyncPlayerPreLoginEvent.Result.ALLOWED) {
            return;
        }
        PluginConfig current = config.get();
        if (current == null || !current.enforcement().enabled()) {
            return;
        }
        long expires = storage.cooldownExpiresMillis(event.getUniqueId());
        long now = clock.wallMillis();
        // Zero is "no cooldown on record", and an expiry at or before now has lapsed.
        if (expires <= now) {
            return;
        }
        // Up to whole seconds here, then up to whole minutes in the placeholder, so a player
        // with a millisecond left is told one minute rather than none.
        long remainingSeconds = (expires - now + 999L) / 1000L;
        long limitSeconds = current.enforcement().atMinutes() * 60L;
        event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER,
                notifier.legacy(current.enforcement().kickMessage(), Placeholders.none()
                        .player(event.getName())
                        .hours(limitSeconds)
                        .minutes(limitSeconds)
                        .cooldown(remainingSeconds)));
    }
}
