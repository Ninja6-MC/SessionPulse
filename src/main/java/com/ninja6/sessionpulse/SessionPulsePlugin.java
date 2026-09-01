package com.ninja6.sessionpulse;

import com.ninja6.sessionpulse.config.PluginConfig;
import com.ninja6.sessionpulse.listeners.PlayerConnectionListener;
import com.ninja6.sessionpulse.platform.FoliaLibScheduler;
import com.ninja6.sessionpulse.platform.Scheduler;
import com.ninja6.sessionpulse.session.AfkGate;
import com.ninja6.sessionpulse.session.SessionClock;
import com.ninja6.sessionpulse.session.SessionStore;
import com.ninja6.sessionpulse.session.SessionTickTask;
import com.ninja6.sessionpulse.session.SessionTracker;
import net.kyori.adventure.platform.bukkit.BukkitAudiences;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;

/**
 * Plugin lifecycle entrypoint for SessionPulse.
 *
 * <p>It owns the two things that have to outlive a single call and be torn down in a
 * defined order: the scheduler seam, and the audience provider that is the plugin's only
 * route to a player's screen. The command declared in {@code plugin.yml} still has no
 * executor, so {@code /spulse} prints its usage string until the command issue lands.
 */
public class SessionPulsePlugin extends JavaPlugin {

    /**
     * Held by its concrete type, not by {@link Scheduler}, and this class is the only
     * place in the plugin that does so. That is what puts
     * {@link FoliaLibScheduler#cancelAll()} within reach of {@link #onDisable()} and out
     * of reach of everything else.
     */
    private FoliaLibScheduler scheduler;

    private BukkitAudiences audiences;

    /**
     * The configuration in force.
     *
     * <p>volatile, and never mutated. {@link #reload()} builds a new {@link PluginConfig}
     * and swaps this reference; the session tick and the flush task will read it from
     * whatever thread FoliaLib gave them, so the write has to publish safely. Mirrors
     * {@code SpiralGenesisPlugin#spawnProtector}, which is volatile for the same reason.
     */
    private volatile PluginConfig config;

    /** The counted window. Everything else in the plugin reads it; only this class builds it. */
    private SessionTracker tracker;

    /**
     * The session tick, held by its handle rather than cancelled in bulk.
     *
     * <p>{@code /spulse reload} has to cancel and reschedule this one task individually.
     * The blanket cancel is disable-only, and reaching for it here would stop the plugin
     * counting while the command reported success.
     */
    private Scheduler.Task sessionTick;

    @Override
    public void onEnable() {
        // Writes config.yml into the data folder on a fresh install and leaves an existing
        // one alone. Must come before the first read, or getConfig() sees an empty file.
        saveDefaultConfig();
        loadConfiguration();

        this.scheduler = new FoliaLibScheduler(this);

        // Created here, closed in onDisable, and it is the ONLY route to a player's screen
        // anywhere in this plugin - Paper included. Our net.kyori is relocated into
        // com.ninja6.sessionpulse.lib.kyori, so our Component is not the Component Paper
        // ships natively. player.sendMessage(Component) would compile against our copy and
        // fail at runtime against Paper's; we compile against spigot-api, where that
        // overload does not exist, so the compiler is the first gate and this field is the
        // second.
        this.audiences = BukkitAudiences.create(this);

        // this::config, never the object. A captured snapshot would keep the tracker on the
        // previous file's window-reset-hours for ever after a reload.
        this.tracker = new SessionTracker(this::config, SessionClock.system(), SessionStore.EMPTY);

        // BEFORE registerEvents, and the order is load-bearing. Players are already online
        // whenever the plugin is enabled by a plugin manager rather than at boot; without
        // this loop they are never in the map and are never counted, for as long as they
        // stay connected, with nothing in the log. Registering the listener first would
        // leave a window in which a player joining mid-enable is passed to onJoin twice -
        // harmless only because onJoin is idempotent, and not something to rely on.
        for (Player online : getServer().getOnlinePlayers()) {
            tracker.onJoin(online.getUniqueId(), online.getName());
        }
        getServer().getPluginManager()
                .registerEvents(new PlayerConnectionListener(tracker), this);

        // AfkGate.NEVER and no observers: this issue counts time and puts nothing on
        // anybody's screen. The AFK issue supplies the real gate; the reminder issues
        // register the observers.
        this.sessionTick = scheduler.globalRepeating(
                new SessionTickTask(tracker, AfkGate.NEVER, List.of(), getLogger()),
                SessionTickTask.DELAY_TICKS, SessionTickTask.PERIOD_TICKS);

        getLogger().info("SessionPulse enabled (scheduler: " + scheduler.platformName() + ").");

        // Not decoration, and not the notifier issue arriving early. The boot legs assert
        // that the relocated Adventure pipeline works, and until something actually
        // deserializes and sends a Component, nothing on the ServiceLoader path ever runs -
        // so a mergeServiceFiles() that silently stopped working would leave every boot leg
        // green. This one line makes the round trip real: MiniMessage parses, a relocated
        // Component is built, and a relocated serializer renders it to the console. A
        // broken service file surfaces here as ServiceConfigurationError, which the boot
        // script greps for.
        audiences.console().sendMessage(
            MiniMessage.miniMessage().deserialize(
                "<gray>SessionPulse: MiniMessage pipeline <green>ready</green>.</gray>"));
    }

    @Override
    public void onDisable() {
        // Order matters. Tasks first: a tick still running while the audience closes would
        // send into a closed provider and throw during shutdown, which the boot legs
        // would - correctly - read as a dirty disable.
        if (scheduler != null) {
            scheduler.cancelAll();
            scheduler = null;
        }
        // Nulled after the tasks are stopped, not before: the handle is only meaningful
        // while the scheduler is alive, and the blanket cancel above has already stopped it.
        // When storage lands, the final synchronous save goes here - after the tick has
        // stopped, before the audience closes.
        this.sessionTick = null;
        this.tracker = null;
        if (audiences != null) {
            audiences.close();
            audiences = null;
        }
        this.config = null;
        getLogger().info("SessionPulse disabled.");
    }

    /**
     * Rebuilds the configuration snapshot from disk and reports what it had to correct.
     *
     * <p>A new object every time, never a mutation of the old one. Anything holding
     * {@code this::config} picks the new one up on its next call; anything that captured
     * the object would keep running the previous file's settings for ever, which is the
     * failure {@code SpawnProtector} in SpiralGenesis exists to prevent.
     */
    private void loadConfiguration() {
        this.config = new PluginConfig(getConfig());
        // Every load, including a reload: a value the plugin corrected is one the operator
        // reads back out of their own file and believes, so it has to be said each time.
        for (String warning : config.warnings()) {
            getLogger().warning(warning);
        }
        getLogger().info("Configuration loaded: " + config.milestones().size()
                + " milestone(s), overtime "
                + (config.overtime().enabled() ? "on" : "off")
                + ", enforcement "
                + (config.enforcement().enabled() ? "on" : "off") + ".");
    }

    /**
     * Re-reads config.yml. Called by {@code /spulse reload} once the command issue lands.
     *
     * <p>Does NOT call {@code Scheduler#cancelAll}. That is disable-only: cancelling every
     * task here would kill the session tick and the plugin would silently stop counting.
     * The reload issue re-schedules the flush and tick tasks by their own handles.
     */
    public void reload() {
        reloadConfig();
        loadConfiguration();
    }

    /**
     * The configuration in force right now.
     *
     * <p>Long-lived services take {@code Supplier<PluginConfig>} and are handed
     * {@code plugin::config}, so a reload reaches them without any of them holding a
     * reference that has to be replaced.
     *
     * @return the current snapshot, never {@code null} between enable and disable
     */
    public PluginConfig config() {
        return config;
    }

    /**
     * The single door to player output. See {@link #onEnable()}.
     *
     * @return the audience provider, or {@code null} once the plugin has been disabled
     */
    public BukkitAudiences audiences() {
        return audiences;
    }

    /**
     * The scheduling seam.
     *
     * <p>Returns {@link Scheduler}, not the concrete type, so no later issue can reach
     * {@code platformName()} and start branching on the platform, or reach
     * {@code cancelAll()} and stop the plugin counting on reload.
     *
     * @return the seam, or {@code null} once the plugin has been disabled
     */
    public Scheduler scheduler() {
        return scheduler;
    }

    /**
     * The counted window.
     *
     * @return the tracker, or {@code null} once the plugin has been disabled
     */
    public SessionTracker tracker() {
        return tracker;
    }
}
