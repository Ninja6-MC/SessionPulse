package com.ninja6.sessionpulse;

import com.ninja6.sessionpulse.afk.AfkService;
import com.ninja6.sessionpulse.afk.BuiltInAfkDetector;
import com.ninja6.sessionpulse.afk.EssentialsLookup;
import com.ninja6.sessionpulse.commands.SessionPulseCommand;
import com.ninja6.sessionpulse.config.PluginConfig;
import com.ninja6.sessionpulse.enforce.EnforcementService;
import com.ninja6.sessionpulse.listeners.LoginGateListener;
import com.ninja6.sessionpulse.listeners.PlayerActivityListener;
import com.ninja6.sessionpulse.listeners.PlayerConnectionListener;
import com.ninja6.sessionpulse.reminder.ReminderObserver;
import com.ninja6.sessionpulse.notify.Notifier;
import com.ninja6.sessionpulse.notify.Placeholders;
import com.ninja6.sessionpulse.platform.FoliaLibScheduler;
import com.ninja6.sessionpulse.platform.Scheduler;
import com.ninja6.sessionpulse.session.SessionClock;
import com.ninja6.sessionpulse.session.PlayerSession;
import com.ninja6.sessionpulse.session.SessionObserver;
import com.ninja6.sessionpulse.session.SessionTickSchedule;
import com.ninja6.sessionpulse.session.SessionTickTask;
import com.ninja6.sessionpulse.session.SessionTracker;
import com.ninja6.sessionpulse.storage.DataStorage;
import com.ninja6.sessionpulse.storage.YamlDataStorage;
import org.bukkit.Sound;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Plugin lifecycle entrypoint for SessionPulse.
 *
 * <p>It owns the things that have to outlive a single call and be torn down in a defined
 * order: the scheduler seam, storage, and the notifier that is the plugin's only route to
 * a player's screen. It also wires {@code /spulse} to {@link SessionPulseCommand}, and
 * {@link #reload()} is the one thing that command calls to reload the configuration.
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
     * Which AFK detector is in force. The session tick holds it as its gate, and
     * {@link #reload()} and EssentialsX coming or going re-resolve it in place.
     */
    private AfkService afk;

    /**
     * The session tick, held by its own schedule rather than cancelled in bulk.
     *
     * <p>{@code /spulse reload} has to replace this one task individually, which
     * {@link SessionTickSchedule#reschedule()} does by handle. The blanket cancel is
     * disable-only, and reaching for it here would stop the plugin counting while the
     * command reported success.
     */
    private SessionTickSchedule sessionTick;

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
        // One clock for both, so the idle timer and the counted window agree on what a
        // second is.
        SessionClock clock = SessionClock.system();
        this.tracker = new SessionTracker(this::config, clock, storage);
        this.afk = new AfkService(EssentialsLookup.of(getServer().getPluginManager()),
                new BuiltInAfkDetector(clock, this::config), scheduler, getLogger(), this::config);

        // BEFORE registerEvents, and the order is load-bearing. Players are already online
        // whenever the plugin is enabled by a plugin manager rather than at boot; without
        // this loop they are never in the map and are never counted, for as long as they
        // stay connected, with nothing in the log. Registering the listener first would
        // leave a window in which a player joining mid-enable is passed to onJoin twice -
        // harmless only because onJoin is idempotent, and not something to rely on.
        for (Player online : getServer().getOnlinePlayers()) {
            tracker.onJoin(online.getUniqueId(), online.getName());
            afk.builtIn().seed(online.getUniqueId());
        }
        getServer().getPluginManager()
                .registerEvents(new PlayerConnectionListener(tracker, storage::flushAsync), this);
        // After loadFromDisk, which is what makes a cooldown written before a restart visible
        // to the first connection after it.
        getServer().getPluginManager()
                .registerEvents(new LoginGateListener(storage, clock, this::config, notifier), this);
        // Before the first resolve, so an EssentialsX disabled between the two is seen.
        getServer().getPluginManager().registerEvents(new PlayerActivityListener(afk), this);
        afk.resolve();

        // tracker::snapshotAll is the heartbeat: every periodic flush re-stamps last-seen
        // for everyone still online, so a crash cannot leave it hours stale.
        storage.startFlushing(tracker::snapshotAll);

        // The service is the gate, not the detector it holds, so a resolve reaches the tick
        // without a reschedule. Milestones and overtime share the first observer; enforcement
        // is the second. storage::flushAsync, so a claimed reminder reaches disk ahead of the
        // periodic flush and a crash cannot refire it. Reminders first, so a milestone on the
        // disconnect minute is handed to the region ahead of the kick.
        SessionObserver reminders =
                new ReminderObserver(tracker, scheduler, notifier, storage::flushAsync);
        SessionObserver enforcement =
                new EnforcementService(tracker, scheduler, notifier, storage, clock, this::config);
        // The period is a supplier, read at every schedule, so a reload's reschedule would pick
        // up a changed one. Today it is the constant.
        this.sessionTick = new SessionTickSchedule(scheduler,
                new SessionTickTask(tracker, afk, List.of(reminders, enforcement), getLogger()),
                SessionTickTask.DELAY_TICKS, () -> SessionTickTask.PERIOD_TICKS);
        sessionTick.start();

        // Last, once everything it reaches exists. A missing command is a broken plugin.yml,
        // not a reason to stop counting: say so and run without it.
        PluginCommand command = getCommand("spulse");
        if (command == null) {
            getLogger().severe("plugin.yml does not declare /spulse; the command is unavailable.");
        } else {
            SessionPulseCommand executor = new SessionPulseCommand(tracker, storage, notifier,
                    clock, this::config, this::reload, getLogger(),
                    getServer()::getPlayerExact, getServer()::getOnlinePlayers);
            command.setExecutor(executor);
            command.setTabCompleter(executor);
        }

        getLogger().info("SessionPulse enabled (scheduler: " + scheduler.platformName() + ").");

        // Not decoration. Nothing joins a CI server, so without these two statements the boot
        // legs would prove the jar enables and nothing about whether the relocated Adventure
        // pipeline links. The console call renders a chat line (configured prefix included), an
        // action bar, a title and a sound through the same methods a player gets; the console
        // discards the last three, and linking them is the point. The sound runs
        // Sound#getKey(), compiled against the 1.20.4 enum, on 1.21.x where Sound is an
        // interface; a mismatch surfaces as IncompatibleClassChangeError. The logged legacy
        // render is the only proof anywhere that the relocated legacy serializer resolves,
        // which enforcement depends on. A broken service file surfaces here as
        // ServiceConfigurationError. The boot script greps for all of it.
        notifier.console("<gray>MiniMessage pipeline <green>ready</green>.</gray>",
                "<gray>action bar ready</gray>", "<gray>title ready</gray>",
                "<gray>subtitle ready</gray>", Sound.BLOCK_NOTE_BLOCK_CHIME);
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
        // The tick is retired first, under its own lock, the order YamlDataStorage#shutdown
        // uses: a reload racing this disable then finds the flag set and cannot schedule a
        // tick after the blanket cancel below has run.
        if (sessionTick != null) {
            sessionTick.retire();
        }
        if (scheduler != null) {
            scheduler.cancelAll();
            scheduler = null;
        }
        this.sessionTick = null;
        if (afk != null) {
            afk.retire();
            afk = null;
        }

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
     *
     * <p>Once the tracker exists, publication goes through
     * {@link SessionTracker#applyReload}, which seeds every live session's fired milestones
     * against the new file on both sides of the swap. Assigning the field directly would let
     * a tick between the swap and the seed fire a lowered milestone at everybody past it.
     */
    private void loadConfiguration() {
        PluginConfig next = new PluginConfig(getConfig());
        if (tracker == null) {
            this.config = next;
        } else {
            tracker.applyReload(next, published -> this.config = published);
        }
        // Every load, including a reload: a value the plugin corrected is one the operator
        // reads back out of their own file and believes, so it has to be said each time.
        for (String warning : next.warnings()) {
            getLogger().warning(warning);
        }
        getLogger().info("Configuration loaded: " + next.milestones().size()
                + " milestone(s), overtime "
                + (next.overtime().enabled() ? "on" : "off")
                + ", enforcement "
                + (next.enforcement().enabled() ? "on" : "off") + ".");
    }

    /**
     * Re-reads config.yml. Called by {@code /spulse reload}, on the sender's thread.
     *
     * <p><strong>The only way to reload the configuration.</strong> Callers do not publish a
     * configuration or seed milestones themselves: this method orders milestone seeding
     * around publication, through {@link SessionTracker#applyReload}, and any other route
     * can fire a lowered milestone at every player already past it.
     *
     * <p>Does NOT call {@code Scheduler#cancelAll}. That is disable-only: cancelling every
     * task here would kill the session tick and the plugin would silently stop counting.
     * The flush and the tick are rescheduled by their own handles instead, after the new
     * file is published so each reads the new values: the flush through
     * {@link DataStorage#rescheduleFlush()}, the tick through
     * {@link SessionTickSchedule#reschedule()}.
     *
     * <p>Re-resolves AFK detection after the new file is published, so a changed
     * {@code tracking.afk.mode} and a changed EssentialsX {@code auto-afk} take effect here.
     *
     * <p>The file is parsed before anything is replaced. {@code JavaPlugin#reloadConfig} logs a
     * YAML syntax error and carries on with an empty document, which would publish every
     * default over the operator's settings and report success. A file that does not parse
     * throws here instead, and the configuration in force stays.
     *
     * <p>Each field is read once into a local: {@code onDisable} nulls them, and on Folia a
     * player's reload runs on their region thread while disable runs on the main one.
     *
     * @throws IllegalStateException if config.yml exists and cannot be parsed
     */
    public void reload() {
        requireParses(new File(getDataFolder(), "config.yml"));
        reloadConfig();
        loadConfiguration();
        AfkService afk = this.afk;
        if (afk != null) {
            afk.resolve();
        }
        YamlDataStorage storage = this.storage;
        if (storage != null) {
            storage.rescheduleFlush();
        }
        SessionTickSchedule sessionTick = this.sessionTick;
        if (sessionTick != null) {
            sessionTick.reschedule();
        }
    }

    /**
     * Throws if {@code file} exists and cannot be parsed as YAML.
     *
     * <p>A missing file passes: {@code reloadConfig} then falls back to the defaults shipped in
     * the jar, the values a fresh install starts with, which is what an operator who deleted
     * the file asked for. Package-private so the check is tested without a server.
     *
     * @param file the configuration file
     * @throws IllegalStateException carrying the parser's error, if the file cannot be parsed
     */
    static void requireParses(File file) {
        if (!file.isFile()) {
            return;
        }
        try {
            new YamlConfiguration().load(file);
        } catch (IOException | InvalidConfigurationException e) {
            throw new IllegalStateException(file.getName() + " could not be parsed; the "
                    + "configuration in force was kept.", e);
        }
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
