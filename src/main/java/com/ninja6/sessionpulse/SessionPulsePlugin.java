package com.ninja6.sessionpulse;

import com.ninja6.sessionpulse.config.PluginConfig;
import com.ninja6.sessionpulse.listeners.PlayerConnectionListener;
import com.ninja6.sessionpulse.notify.Notifier;
import com.ninja6.sessionpulse.notify.Placeholders;
import com.ninja6.sessionpulse.platform.FoliaLibScheduler;
import com.ninja6.sessionpulse.platform.Scheduler;
import com.ninja6.sessionpulse.session.AfkGate;
import com.ninja6.sessionpulse.session.SessionClock;
import com.ninja6.sessionpulse.session.PlayerSession;
import com.ninja6.sessionpulse.session.SessionTickTask;
import com.ninja6.sessionpulse.session.SessionTracker;
import com.ninja6.sessionpulse.storage.DataStorage;
import com.ninja6.sessionpulse.storage.YamlDataStorage;
import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Plugin lifecycle entrypoint for SessionPulse.
 *
 * <p>It owns the things that have to outlive a single call and be torn down in a defined
 * order: the scheduler seam, storage, and the notifier that is the plugin's only route to
 * a player's screen. The command declared in {@code plugin.yml} still has no
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

    /** Everything a player sees goes through this; see {@link Notifier}. */
    private Notifier notifier;

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
     * Held by its concrete type, for the same reason as {@link #scheduler}: loading,
     * starting the flush and the final synchronous write are not on {@link DataStorage},
     * so this class is the only place that can reach them. {@link #storage()} hands out the
     * interface.
     */
    private YamlDataStorage storage;

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

        // The only route to a player's screen; see Notifier for why that is a rule, and why
        // open() is here and not on first send. this::config, so a reload reaches the prefix.
        this.notifier = new Notifier(this, this::config);
        notifier.open();

        // Read before the tracker exists and before any player is seeded: a player seeded
        // against an empty store is handed a fresh window, and nothing corrects it later.
        // The one deliberate read on the main thread; see YamlDataStorage.
        this.storage = new YamlDataStorage(getDataFolder().toPath().resolve("data.yml"),
                scheduler, this::config, getLogger());
        storage.loadFromDisk();

        // this::config, never the object. A captured snapshot would keep the tracker on the
        // previous file's window-reset-hours for ever after a reload.
        this.tracker = new SessionTracker(this::config, SessionClock.system(), storage);

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
                .registerEvents(new PlayerConnectionListener(tracker, storage::flushAsync), this);

        // tracker::snapshotAll is the heartbeat: every periodic flush re-stamps last-seen
        // for everyone still online, so a crash cannot leave it hours stale.
        storage.startFlushing(tracker::snapshotAll);

        // AfkGate.NEVER and no observers: this issue counts time and puts nothing on
        // anybody's screen. The AFK issue supplies the real gate; the reminder issues
        // register the observers.
        this.sessionTick = scheduler.globalRepeating(
                new SessionTickTask(tracker, AfkGate.NEVER, List.of(), getLogger()),
                SessionTickTask.DELAY_TICKS, SessionTickTask.PERIOD_TICKS);

        getLogger().info("SessionPulse enabled (scheduler: " + scheduler.platformName() + ").");

        // Not decoration. Nothing joins a CI server, so without these two statements the boot
        // legs would prove the jar enables and nothing about whether the relocated Adventure
        // pipeline links. The console call renders a chat line (configured prefix included), an
        // action bar and a title through the same methods a player gets; the console discards
        // the last two, and linking them is the point. The logged legacy render is the only
        // proof anywhere that the relocated legacy serializer resolves, which enforcement
        // depends on. A broken service file surfaces here as ServiceConfigurationError. The
        // boot script greps for all of it.
        notifier.console("<gray>MiniMessage pipeline <green>ready</green>.</gray>",
                "<gray>action bar ready</gray>", "<gray>title ready</gray>",
                "<gray>subtitle ready</gray>");
        getLogger().info(notifier.legacy(
                "<color:#ff8800>Legacy serializer</color> <green>ready</green>.", Placeholders.none()));
    }

    @Override
    public void onDisable() {
        // Listeners before anything else. On Folia a quit runs on a region thread and can
        // land at any point below. Unregistering stops any further quit from being
        // dispatched here, which narrows the window in which one reaches a store that is
        // closing and drops its save; a handler already running on another thread can still
        // get there. Bukkit would unregister them anyway, but only after this method returns.
        HandlerList.unregisterAll(this);

        // Order matters. Tasks next: a tick still running while the notifier closes would
        // send into a closing provider and throw during shutdown, which the boot legs
        // would - correctly - read as a dirty disable.
        if (scheduler != null) {
            scheduler.cancelAll();
            scheduler = null;
        }
        // Nulled after the tasks are stopped, not before: the handle is only meaningful
        // while the scheduler is alive, and the blanket cancel above has already stopped it.
        this.sessionTick = null;

        // After the tick has stopped, before the notifier closes. A server stopping does not
        // deliver a quit event for the players still online, and neither does a plugin
        // manager disabling this plugin, so their sessions are finalised here or lost. Then
        // the one synchronous write: async work submitted during onDisable has no guarantee
        // of running.
        if (storage != null) {
            if (tracker != null) {
                List<UUID> online = new ArrayList<>();
                for (PlayerSession session : tracker.sessions()) {
                    online.add(session.uuid());
                }
                online.forEach(tracker::onQuit);
            }
            storage.shutdown();
            storage = null;
        }
        this.tracker = null;
        if (notifier != null) {
            notifier.close();
            notifier = null;
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
     * The reload issue re-schedules the flush and tick tasks by their own handles - the
     * flush through {@link DataStorage#rescheduleFlush()}.
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
     * The single door to player output.
     *
     * @return the notifier, or {@code null} once the plugin has been disabled
     */
    public Notifier notifier() {
        return notifier;
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

    /**
     * Persistent player state.
     *
     * <p>Returns {@link DataStorage}, which has no way to write the file, so no later issue
     * can put a disk write on the main thread through it.
     *
     * @return the store, or {@code null} once the plugin has been disabled
     */
    public DataStorage storage() {
        return storage;
    }
}
