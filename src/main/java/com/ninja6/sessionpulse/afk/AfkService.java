package com.ninja6.sessionpulse.afk;

import com.ninja6.sessionpulse.config.AfkMode;
import com.ninja6.sessionpulse.config.PluginConfig;
import com.ninja6.sessionpulse.platform.Scheduler;
import com.ninja6.sessionpulse.session.AfkGate;
import org.bukkit.entity.Player;

import java.util.UUID;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Holds the AFK detector in force, and decides which one that is.
 *
 * <p>The plugin hands this to the session tick as its {@link AfkGate}. Every tick reads
 * {@link #current()} afresh, so re-resolving publishes a new detector with no reschedule.
 *
 * <h2>Resolution</h2>
 *
 * <table>
 *   <caption>What each mode resolves to</caption>
 *   <tr><th>mode</th><th>EssentialsX</th><th>detector</th></tr>
 *   <tr><td>OFF</td><td>any</td><td>nobody is AFK</td></tr>
 *   <tr><td>BUILT_IN</td><td>any</td><td>idle timer</td></tr>
 *   <tr><td>ESSENTIALS</td><td>bound</td><td>EssentialsX; a WARNING if its auto-afk is off</td></tr>
 *   <tr><td>ESSENTIALS</td><td>absent, or the bind failed</td><td>nobody is AFK, with a WARNING</td></tr>
 *   <tr><td>AUTO</td><td>bound, auto-afk above 0</td><td>EssentialsX, and the idle timer for
 *       players without {@code essentials.afk.auto}</td></tr>
 *   <tr><td>AUTO</td><td>bound, auto-afk 0 or below</td><td>idle timer</td></tr>
 *   <tr><td>AUTO</td><td>absent, or the bind failed</td><td>idle timer; a WARNING if the bind
 *       failed</td></tr>
 * </table>
 *
 * <p>Every resolve names the result in the log, so an operator can read the mode off it. Nothing here throws: a failure to find or bind EssentialsX is one of the rows above.
 *
 * <h2>When it runs</h2>
 *
 * <p>At enable, on every reload, and whenever EssentialsX is enabled or disabled while
 * SessionPulse is running. {@code synchronized}, because a reload from the console can race
 * a plugin being disabled on the main thread, and the later decision has to be the one
 * published. Each resolve builds a fresh EssentialsX cache, so a player who is really away is
 * credited at most one tick across it.
 */
public final class AfkService implements AfkGate {

    private final EssentialsLookup lookup;
    private final BuiltInAfkDetector builtIn;
    private final Scheduler scheduler;
    private final Logger logger;
    private final Supplier<PluginConfig> config;

    /**
     * volatile: published by {@link #resolve()} on the main or console thread, read by the
     * session tick on the global region.
     */
    private volatile AfkDetector current = NoOpAfkDetector.INSTANCE;

    /**
     * Creates the service. Nobody is AFK until the first {@link #resolve()}.
     *
     * @param lookup    finds EssentialsX
     * @param builtIn   the idle timer; the listener feeds it whatever the mode, so it is warm
     *                  whenever a resolve picks it
     * @param scheduler handed to the EssentialsX detector for its region refreshes
     * @param logger    where each resolution is reported
     * @param config    read at each resolve for the mode and idle threshold
     */
    public AfkService(EssentialsLookup lookup, BuiltInAfkDetector builtIn, Scheduler scheduler,
                      Logger logger, Supplier<PluginConfig> config) {
        this.lookup = lookup;
        this.builtIn = builtIn;
        this.scheduler = scheduler;
        this.logger = logger;
        this.config = config;
    }

    @Override
    public boolean isAfk(Player player) {
        return current.isAfk(player);
    }

    /**
     * The detector in force.
     *
     * @return the detector, never {@code null}
     */
    public AfkDetector current() {
        return current;
    }

    /**
     * The idle timer, which the activity listener feeds.
     *
     * @return the timer
     */
    public BuiltInAfkDetector builtIn() {
        return builtIn;
    }

    /** Decides the detector from the configuration and the plugin list, and publishes it. */
    public synchronized void resolve() {
        publish(false);
    }

    /**
     * Re-resolves as though EssentialsX were absent.
     *
     * <p>For the disable event: Bukkit fires it while the plugin still reports itself enabled,
     * so an ordinary resolve at that moment would bind to the plugin that is going away.
     */
    public synchronized void resolveWithoutEssentials() {
        publish(true);
    }

    /**
     * Drops a departed player from the idle timer and from the detector in force.
     *
     * @param uuid the player who left
     */
    public void forget(UUID uuid) {
        builtIn.forget(uuid);
        current.forget(uuid);
    }

    /** Publishes the detector that pauses nobody. Disable only. */
    public synchronized void retire() {
        current = NoOpAfkDetector.INSTANCE;
    }

    private void publish(boolean essentialsGone) {
        PluginConfig settings = config.get();
        AfkMode mode = settings.afkMode();
        int idleSeconds = settings.afkIdleSeconds();
        String timer = "built-in idle timer, " + idleSeconds + "s";

        switch (mode) {
            case OFF -> {
                current = NoOpAfkDetector.INSTANCE;
                logger.info("AFK detection: off (tracking.afk.mode OFF).");
            }
            case BUILT_IN -> {
                current = builtIn;
                logger.info("AFK detection: " + timer + ".");
            }
            case ESSENTIALS -> resolveEssentials(essentialsGone);
            case AUTO -> resolveAuto(essentialsGone, timer, idleSeconds);
        }
    }

    private void resolveEssentials(boolean essentialsGone) {
        EssentialsAfkDetector.Hook hook;
        try {
            hook = essentialsGone ? null : bind();
        } catch (Throwable thrown) {
            current = NoOpAfkDetector.INSTANCE;
            logger.log(Level.WARNING, "AFK detection: tracking.afk.mode is ESSENTIALS and "
                    + "EssentialsX is installed but could not be bound; nobody will be treated "
                    + "as AFK.", thrown);
            return;
        }
        if (hook == null) {
            current = NoOpAfkDetector.INSTANCE;
            logger.warning("AFK detection: tracking.afk.mode is ESSENTIALS but EssentialsX is "
                    + "not installed or not enabled; nobody will be treated as AFK.");
            return;
        }
        current = new EssentialsAfkDetector(hook, null, scheduler, logger);
        if (hook.autoAfkSeconds > 0) {
            logger.info("AFK detection: EssentialsX (auto-afk " + hook.autoAfkSeconds
                    + "s; only players with " + EssentialsAfkDetector.AUTO_AFK_PERMISSION
                    + " are marked automatically, others only by /afk).");
        } else {
            logger.warning("AFK detection: EssentialsX, but its auto-afk is disabled; only /afk "
                    + "will pause the clock.");
        }
    }

    private void resolveAuto(boolean essentialsGone, String timer, int idleSeconds) {
        EssentialsAfkDetector.Hook hook;
        try {
            hook = essentialsGone ? null : bind();
        } catch (Throwable thrown) {
            current = builtIn;
            logger.log(Level.WARNING, "AFK detection: EssentialsX is installed but could not be "
                    + "bound; falling back to the built-in idle timer.", thrown);
            logger.info("AFK detection: " + timer + " (AUTO; EssentialsX could not be bound).");
            return;
        }
        if (hook == null) {
            current = builtIn;
            logger.info("AFK detection: " + timer + " (AUTO; EssentialsX not installed).");
        } else if (hook.autoAfkSeconds <= 0) {
            current = builtIn;
            logger.info("AFK detection: " + timer
                    + " (AUTO; EssentialsX present but auto-afk is disabled).");
        } else {
            current = new EssentialsAfkDetector(hook, builtIn, scheduler, logger);
            logger.info("AFK detection: EssentialsX (AUTO; auto-afk " + hook.autoAfkSeconds
                    + "s; players without " + EssentialsAfkDetector.AUTO_AFK_PERMISSION
                    + " use the built-in " + idleSeconds + "s timer).");
        }
    }

    /** Finds and binds EssentialsX, or returns {@code null} when it is not there to bind. */
    private EssentialsAfkDetector.Hook bind() throws ReflectiveOperationException {
        Object essentials = lookup.enabledPlugin();
        return essentials == null ? null : EssentialsAfkDetector.Hook.bind(essentials);
    }
}
