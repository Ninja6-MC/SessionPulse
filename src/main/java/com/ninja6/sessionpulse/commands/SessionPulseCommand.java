package com.ninja6.sessionpulse.commands;

import com.ninja6.sessionpulse.config.PluginConfig;
import com.ninja6.sessionpulse.notify.Notifier;
import com.ninja6.sessionpulse.notify.Placeholders;
import com.ninja6.sessionpulse.session.PlayerSession;
import com.ninja6.sessionpulse.session.SessionClock;
import com.ninja6.sessionpulse.session.SessionSnapshot;
import com.ninja6.sessionpulse.session.SessionTracker;
import com.ninja6.sessionpulse.storage.DataStorage;
import com.ninja6.sessionpulse.storage.StoredPlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * {@code /spulse}: a player's own time, the leaderboard, and the two staff tools, reset and
 * reload.
 *
 * <h2>Permissions</h2>
 *
 * <p>The check sits at the top of both {@link #onCommand} and {@link #onTabComplete}, the shape
 * every Ninja6-MC command has. {@code plugin.yml} already gates the command on
 * {@link #USE}; the check is repeated rather than trusted to that line, so the command stays
 * closed if the line is ever edited, and a completer that lists online names to someone who
 * cannot run the command would leak the list.
 * {@link #ADMIN} declares {@link #USE} as a child, so a staff member holding only the admin
 * node passes both checks.
 *
 * <p>{@code time <player>} for a name other than the sender's own needs {@link #ADMIN}, and
 * the check comes before any lookup, so a refused sender cannot learn from the reply whether
 * a name has a record.
 *
 * <h2>Output</h2>
 *
 * <p>Every reply goes through {@link Notifier#send}, never {@code sender.sendMessage}: the
 * relocated Adventure is the only renderer, and a raw MiniMessage string sent as text shows the
 * tags. The lines are fixed here rather than configurable, deliberately: they are staff and
 * status text, not the reminders an operator tunes, and adding them to {@code config.yml}
 * would be a schema change for no one's benefit. Names are always passed as placeholders, so a
 * stored name that looks like markup is shown as text.
 *
 * <h2>Threading</h2>
 *
 * <p>A command runs on its sender's thread: the player's own region on Folia, the global
 * region for the console. The tracker's map and the store's records are concurrent, so reads
 * and the writes below are safe from either. The notifier is only ever asked to reach the
 * sender, who may be touched from that thread; nobody else is messaged, which is why
 * {@code reset} does not tell its target.
 *
 * <h2>Accepted limitations</h2>
 *
 * <ul>
 *   <li>An offline reset reads the stored record and writes it back. A quit save for the same
 *       player landing between the two, or a join whose {@code computeIfAbsent} loads the
 *       record in between, can win; the reset re-checks for a live session after its save and
 *       resets that too, which closes the join case once the join is visible. What remains
 *       is a narrow window on a player changing state at that instant, and the operator can
 *       run the reset again.</li>
 *   <li>An enforcement disconnect already past its stale-session check when a reset lands
 *       still kicks, and writes a fresh cooldown after the reset cleared the old one. The kick
 *       was decided against the window the reset replaced; the operator sees the player leave
 *       and can reset again.</li>
 * </ul>
 */
public final class SessionPulseCommand implements CommandExecutor, TabCompleter {

    /** Own time and the leaderboard. Declared in {@code plugin.yml}; never renamed. */
    public static final String USE = "sessionpulse.use";

    /** Another player's time, reset and reload. Declared in {@code plugin.yml}; never renamed. */
    public static final String ADMIN = "sessionpulse.admin";

    /** Rows on the leaderboard. Enough to be worth reading, few enough to fit one chat screen. */
    public static final int TOP_SIZE = 10;

    private static final List<String> USE_SUBCOMMANDS = List.of("time", "top");
    private static final List<String> ADMIN_SUBCOMMANDS = List.of("time", "top", "reset", "reload");

    // The literal "<player>" in usage text is escaped: it is a placeholder tag here, and an
    // unset tag renders as written only by MiniMessage's current leniency.
    static final String NO_PERMISSION = "<red>You do not have permission to do that.</red>";
    static final String HELP_HEADER = "<gold>SessionPulse commands</gold>";
    static final String HELP_TIME_SELF =
            "<yellow>/spulse time</yellow> <gray>- your counted window and lifetime playtime</gray>";
    static final String HELP_TIME_ANY =
            "<yellow>/spulse time [player]</yellow> <gray>- a counted window and lifetime playtime</gray>";
    static final String HELP_TOP =
            "<yellow>/spulse top</yellow> <gray>- the " + TOP_SIZE + " longest lifetimes</gray>";
    static final String HELP_RESET =
            "<yellow>/spulse reset \\<player></yellow> <gray>- clear a counted window and cooldown</gray>";
    static final String HELP_RELOAD =
            "<yellow>/spulse reload</yellow> <gray>- re-read config.yml</gray>";
    static final String TIME_SELF = "<gray>Your counted window: <yellow><hours>h</yellow> "
            + "(<minutes> min). Lifetime: <yellow><lifetime>h</yellow>.</gray>";
    static final String TIME_OTHER = "<gray><white><player></white>'s counted window: "
            + "<yellow><hours>h</yellow> (<minutes> min). Lifetime: <yellow><lifetime>h</yellow>.</gray>";
    static final String TIME_CONSOLE = "<red>From the console, name a player: /spulse time \\<player></red>";
    static final String USAGE_TIME = "<red>Usage: /spulse time [player]</red>";
    static final String NO_RECORD = "<red>No playtime recorded for <player>.</red>";
    static final String TOP_HEADER = "<gold>Top playtime</gold>";
    static final String TOP_ROW =
            "<gray><rank>. <white><player></white> <yellow><lifetime>h</yellow></gray>";
    static final String TOP_EMPTY = "<gray>No playtime recorded yet.</gray>";
    static final String USAGE_TOP = "<red>Usage: /spulse top</red>";
    static final String USAGE_RESET = "<red>Usage: /spulse reset \\<player></red>";
    static final String RESET_DONE = "<green>Reset <player>'s counted window.</green>";
    static final String RESET_DONE_COOLDOWN =
            "<green>Reset <player>'s counted window and cooldown.</green>";
    static final String USAGE_RELOAD = "<red>Usage: /spulse reload</red>";
    static final String RELOAD_DONE =
            "<green>Configuration reloaded.</green> <gray>Any warnings are in the console.</gray>";
    static final String RELOAD_FAILED =
            "<red>Reload failed. The error is in the console.</red>";

    private final SessionTracker tracker;
    private final DataStorage storage;
    private final Notifier notifier;
    private final SessionClock clock;
    private final Supplier<PluginConfig> config;
    private final Runnable reload;
    private final Logger logger;
    private final Function<String, Player> onlineByName;
    private final Supplier<Collection<? extends Player>> online;

    /**
     * The command, with every server lookup passed in so it can be built without a server.
     *
     * @param tracker      the counted window, for live figures and resets
     * @param storage      the stored records, for offline figures, the leaderboard and cooldowns
     * @param notifier     the only route to the sender
     * @param clock        the calendar reading an offline window is judged against
     * @param config       the configuration in force; handed {@code plugin::config}
     * @param reload       the plugin's reload, the only way to reload the configuration
     * @param logger       where a failed reload is reported in full
     * @param onlineByName an exact, case-insensitive online lookup; the plugin passes
     *                     {@code Server#getPlayerExact}
     * @param online       the online players; the plugin passes {@code Server#getOnlinePlayers}
     */
    public SessionPulseCommand(SessionTracker tracker, DataStorage storage, Notifier notifier,
                               SessionClock clock, Supplier<PluginConfig> config, Runnable reload,
                               Logger logger, Function<String, Player> onlineByName,
                               Supplier<Collection<? extends Player>> online) {
        this.tracker = tracker;
        this.storage = storage;
        this.notifier = notifier;
        this.clock = clock;
        this.config = config;
        this.reload = reload;
        this.logger = logger;
        this.onlineByName = onlineByName;
        this.online = online;
    }

    /** Always {@code true}: every path replies itself, so Bukkit's usage line never doubles it. */
    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission(USE)) {
            reply(sender, NO_PERMISSION);
            return true;
        }
        if (args.length == 0) {
            help(sender);
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "time" -> time(sender, args);
            case "top" -> top(sender, args);
            case "reset" -> reset(sender, args);
            case "reload" -> reload(sender, args);
            default -> help(sender);
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias,
                                      String[] args) {
        if (!sender.hasPermission(USE)) {
            return List.of();
        }
        boolean admin = sender.hasPermission(ADMIN);
        if (args.length == 1) {
            return matching(admin ? ADMIN_SUBCOMMANDS : USE_SUBCOMMANDS, args[0]);
        }
        if (args.length == 2) {
            String sub = args[0].toLowerCase(Locale.ROOT);
            if (admin && (sub.equals("time") || sub.equals("reset"))) {
                return onlineNames(sender, args[1]);
            }
            if (sub.equals("time") && sender instanceof Player self) {
                return matching(List.of(self.getName()), args[1]);
            }
        }
        return List.of();
    }

    // -----------------------------------------------------------------------------
    // Subcommands.
    // -----------------------------------------------------------------------------

    /** Only the lines this sender may run, so the help never advertises a refusal. */
    private void help(CommandSender sender) {
        boolean admin = sender.hasPermission(ADMIN);
        reply(sender, HELP_HEADER);
        reply(sender, admin ? HELP_TIME_ANY : HELP_TIME_SELF);
        reply(sender, HELP_TOP);
        if (admin) {
            reply(sender, HELP_RESET);
            reply(sender, HELP_RELOAD);
        }
    }

    private void time(CommandSender sender, String[] args) {
        if (args.length > 2) {
            reply(sender, USAGE_TIME);
            return;
        }
        if (args.length == 1) {
            if (sender instanceof Player self) {
                showTime(sender, self.getUniqueId(), self.getName(), true);
            } else {
                reply(sender, TIME_CONSOLE);
            }
            return;
        }
        String name = args[1];
        boolean own = sender instanceof Player self && self.getName().equalsIgnoreCase(name);
        // Before any lookup: a refusal must read the same whether or not the name exists.
        if (!own && !sender.hasPermission(ADMIN)) {
            reply(sender, NO_PERMISSION);
            return;
        }
        // An online player is identified by their UUID from here on, never by name: a stored
        // record under the same name may belong to someone who has since renamed.
        Player target = onlineByName.apply(name);
        UUID uuid = target != null ? target.getUniqueId() : storage.findByName(name);
        showTime(sender, uuid, target != null ? target.getName() : name, own);
    }

    /**
     * Live figures when the player is being tracked, otherwise the stored ones as the next join
     * would see them: a window that join would reset is shown as zero.
     */
    private void showTime(CommandSender sender, UUID uuid, String typedName, boolean own) {
        String message = own ? TIME_SELF : TIME_OTHER;
        PlayerSession live = uuid == null ? null : tracker.session(uuid);
        if (live != null) {
            reply(sender, message, figures(live.name() != null ? live.name() : typedName,
                    live.windowSeconds(), live.lifetimeSeconds()));
            return;
        }
        SessionSnapshot stored = uuid == null ? SessionSnapshot.UNKNOWN : storage.load(uuid);
        // Unknown covers a record that holds only a cooldown: there is no playtime in it.
        if (stored.isUnknown()) {
            reply(sender, NO_RECORD, Placeholders.none().player(typedName));
            return;
        }
        long window = SessionTracker.windowExpired(stored, config.get(), clock.wallMillis())
                ? 0L : stored.windowSeconds();
        reply(sender, message, figures(stored.name() != null ? stored.name() : typedName,
                window, stored.lifetimeSeconds()));
    }

    private static Placeholders figures(String name, long windowSeconds, long lifetimeSeconds) {
        return Placeholders.none().player(name).hours(windowSeconds).minutes(windowSeconds)
                .lifetime(lifetimeSeconds);
    }

    /** One leaderboard row: who, under which name, with how much. */
    private record Row(UUID uuid, String name, long lifetimeSeconds) {
    }

    /**
     * The longest lifetimes, stored and live together.
     *
     * <p>Built from the union of the store and the tracker, with the live figure and name
     * winning. A player online since before their first flush has no stored record yet, and a
     * leaderboard read from the store alone would leave them off it; one read from the tracker
     * alone would leave off everyone offline. Ties break on name, ignoring case, then on UUID,
     * so the order is the same on every call.
     */
    private void top(CommandSender sender, String[] args) {
        if (args.length != 1) {
            reply(sender, USAGE_TOP);
            return;
        }
        Map<UUID, Row> rows = new HashMap<>();
        for (Map.Entry<UUID, StoredPlayer> entry : storage.records().entrySet()) {
            StoredPlayer record = entry.getValue();
            rows.put(entry.getKey(),
                    new Row(entry.getKey(), record.name(), record.lifetimeSeconds()));
        }
        for (PlayerSession session : tracker.sessions()) {
            Row stored = rows.get(session.uuid());
            String name = session.name() != null ? session.name()
                    : stored == null ? null : stored.name();
            rows.put(session.uuid(), new Row(session.uuid(), name, session.lifetimeSeconds()));
        }
        List<Row> ranked = new ArrayList<>();
        for (Row row : rows.values()) {
            // A nameless row cannot be shown, and a zero is a cooldown-only record or a player
            // who has not yet played a counted second; neither is a place on a leaderboard.
            if (row.name() != null && row.lifetimeSeconds() > 0L) {
                ranked.add(row);
            }
        }
        if (ranked.isEmpty()) {
            reply(sender, TOP_EMPTY);
            return;
        }
        ranked.sort(Comparator.comparingLong(Row::lifetimeSeconds).reversed()
                .thenComparing(Row::name, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(Row::uuid));
        reply(sender, TOP_HEADER);
        for (int i = 0; i < Math.min(TOP_SIZE, ranked.size()); i++) {
            Row row = ranked.get(i);
            reply(sender, TOP_ROW, Placeholders.none().rank(i + 1).player(row.name())
                    .lifetime(row.lifetimeSeconds()));
        }
    }

    /**
     * Clears a player's counted window, and their cooldown if they have one. Lifetime and
     * last-seen are kept.
     *
     * <p>Online, the tracker replaces the session with a fresh object: an empty fired set, a
     * zero window and no enforcement claim, so milestones re-arm and fire once each as the new
     * window crosses them. A disconnect already queued against the old session finds it stale
     * and is dropped. The fresh session is checkpointed before the cooldown is touched, so the
     * store never holds a cleared cooldown next to the old window.
     *
     * <p>Offline, the stored record is written back with a zero window starting now. The save
     * path never touches the cooldown, so the cooldown is cleared separately. Exempt players
     * can be reset; exemption is about reminders, not about staff tools.
     */
    private void reset(CommandSender sender, String[] args) {
        if (!sender.hasPermission(ADMIN)) {
            reply(sender, NO_PERMISSION);
            return;
        }
        if (args.length != 2) {
            reply(sender, USAGE_RESET);
            return;
        }
        String name = args[1];
        Player target = onlineByName.apply(name);
        UUID uuid;
        String shown = null;
        if (target != null) {
            uuid = target.getUniqueId();
            PlayerSession fresh = tracker.resetWindow(uuid);
            if (fresh != null) {
                tracker.checkpoint(fresh);
                shown = fresh.name() != null ? fresh.name() : target.getName();
            }
        } else {
            uuid = storage.findByName(name);
        }
        if (shown == null) {
            StoredPlayer record = uuid == null ? null : storage.records().get(uuid);
            if (record == null) {
                reply(sender, NO_RECORD, Placeholders.none().player(name));
                return;
            }
            storage.save(uuid, new SessionSnapshot(record.name(), record.lifetimeSeconds(),
                    clock.wallMillis(), 0L, record.lastSeenMillis()));
            // A join that landed while the record was being rewritten loaded the old window.
            // A no-op for a player who is still offline.
            PlayerSession joined = tracker.resetWindow(uuid);
            if (joined != null) {
                tracker.checkpoint(joined);
            }
            shown = record.name() != null ? record.name() : name;
        }
        boolean hadCooldown = storage.cooldownExpiresMillis(uuid) != 0L;
        if (hadCooldown) {
            // Zero is "no cooldown": the login gate admits it. setCooldown forces a flush.
            storage.setCooldown(uuid, null, 0L);
        } else {
            // A reset that waits for the periodic flush is one a crash forgets.
            storage.flushAsync();
        }
        reply(sender, hadCooldown ? RESET_DONE_COOLDOWN : RESET_DONE,
                Placeholders.none().player(shown));
    }

    /**
     * Runs the plugin's reload and says whether it worked.
     *
     * <p>A {@link RuntimeException} is caught, logged with its stack trace and reported as a
     * failure line rather than Bukkit's generic "An internal error occurred", which tells the
     * operator nothing and puts nothing in the log that names the reload. Validation warnings
     * are not errors: the plugin logs them and the reply points at the console.
     */
    private void reload(CommandSender sender, String[] args) {
        if (!sender.hasPermission(ADMIN)) {
            reply(sender, NO_PERMISSION);
            return;
        }
        if (args.length != 1) {
            reply(sender, USAGE_RELOAD);
            return;
        }
        try {
            reload.run();
        } catch (RuntimeException e) {
            logger.log(Level.SEVERE, "/spulse reload failed.", e);
            reply(sender, RELOAD_FAILED);
            return;
        }
        reply(sender, RELOAD_DONE);
    }

    // -----------------------------------------------------------------------------
    // Helpers.
    // -----------------------------------------------------------------------------

    private void reply(CommandSender sender, String miniMessage) {
        reply(sender, miniMessage, Placeholders.none());
    }

    private void reply(CommandSender sender, String miniMessage, Placeholders placeholders) {
        notifier.send(sender, miniMessage, placeholders);
    }

    private static List<String> matching(List<String> candidates, String typed) {
        String prefix = typed.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (String candidate : candidates) {
            if (candidate.toLowerCase(Locale.ROOT).startsWith(prefix)) {
                out.add(candidate);
            }
        }
        return out;
    }

    /**
     * Online names starting with {@code typed}, leaving out anyone a player sender cannot see,
     * so completion does not reveal a vanished staff member. Sorted, so the list is stable.
     */
    private List<String> onlineNames(CommandSender sender, String typed) {
        Player viewer = sender instanceof Player player ? player : null;
        List<String> names = new ArrayList<>();
        for (Player candidate : online.get()) {
            if (viewer == null || viewer.canSee(candidate)) {
                names.add(candidate.getName());
            }
        }
        names.sort(String.CASE_INSENSITIVE_ORDER);
        return matching(names, typed);
    }
}
