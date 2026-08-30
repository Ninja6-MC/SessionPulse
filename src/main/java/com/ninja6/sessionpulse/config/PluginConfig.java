package com.ninja6.sessionpulse.config;

import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

/**
 * An immutable snapshot of the plugin's configuration.
 *
 * <p>Nothing here is reloadable, and that is the design. {@code /spulse reload} builds a
 * <b>new</b> instance and swaps the plugin's reference to it; a long-lived service holds
 * {@code Supplier<PluginConfig>} and reads it per call rather than capturing the object.
 * The pattern is {@code SpawnProtector} in SpiralGenesis, which takes its provider and its
 * claim size as suppliers read per call rather than captured, because {@code /sgen reload}
 * replaces them and a captured one would keep a provider built against the previous
 * configuration alive forever.
 *
 * <h2>Warnings, not exceptions</h2>
 *
 * <p>No path in this class throws. A malformed file produces a working plugin and a list
 * of complaints; the caller logs them. Kept as text rather than logged from here so that
 * this class stays constructible from a plain {@link ConfigurationSection} in a unit test,
 * with no server and no logger - which is what the whole test suite for this issue
 * depends on.
 *
 * <p>Two different corrections, and the difference is load-bearing:
 * <ul>
 *   <li>a <b>scalar</b> out of range is <b>clamped</b>, and the warning names the value
 *       written, the range, and the value used;</li>
 *   <li>a <b>milestone entry</b> that is wrong is <b>skipped</b>, never replaced with a
 *       default, and the warning names its position in the file so the operator can find
 *       it.</li>
 * </ul>
 */
public final class PluginConfig {

    // -----------------------------------------------------------------------------
    // Clamp ranges. Every constant here has a boundary case in ClampBoundaryTest, and
    // that is enforced mechanically - ClampBoundaryTest reflects over these fields and
    // fails if one is neither in its table nor in its named exemption set. Adding a clamp
    // without a test is a build failure, not an oversight somebody notices later.
    // -----------------------------------------------------------------------------

    /** Below an hour, an ordinary tea break would start a fresh counted window. */
    static final int MIN_WINDOW_RESET_HOURS = 1;
    /** A week. Above this the window never resets in practice and the setting is a lie. */
    static final int MAX_WINDOW_RESET_HOURS = 168;

    /** Half a minute. Below this, reading a sign counts as going AFK. */
    static final int MIN_AFK_IDLE_SECONDS = 30;
    /** An hour. Above this nobody is ever detected as idle before they log off. */
    static final int MAX_AFK_IDLE_SECONDS = 3600;

    /** Flushing more than once a minute is disk traffic with nothing to buy. */
    static final int MIN_FLUSH_INTERVAL_MINUTES = 1;
    /** An hour of unflushed play is what a crash would cost. */
    static final int MAX_FLUSH_INTERVAL_MINUTES = 60;

    /** A milestone at minute 0 fires before the player has played. */
    static final int MIN_MILESTONE_MINUTE = 1;
    /** A week of continuous counted play. Nothing above this can ever fire. */
    static final int MAX_MILESTONE_MINUTE = 10_080;

    static final int MIN_OVERTIME_AFTER_MINUTES = 1;
    static final int MAX_OVERTIME_AFTER_MINUTES = 10_080;
    /** Zero would schedule a reminder every tick. */
    static final int MIN_OVERTIME_EVERY_MINUTES = 1;
    /** A day. Above this the "recurring" reminder recurs less often than most sessions. */
    static final int MAX_OVERTIME_EVERY_MINUTES = 1440;

    static final int MIN_ENFORCEMENT_AT_MINUTES = 1;
    static final int MAX_ENFORCEMENT_AT_MINUTES = 10_080;
    /**
     * Zero is not "no cooldown", it is a boot loop: the player reconnects still over the
     * limit and is kicked again. Turning enforcement off is how you disable enforcement.
     */
    static final int MIN_ENFORCEMENT_COOLDOWN_MINUTES = 1;
    static final int MAX_ENFORCEMENT_COOLDOWN_MINUTES = 1440;

    // -----------------------------------------------------------------------------
    // Defaults. These are duplicated in src/main/resources/config.yml on purpose, and
    // DefaultConfigResourceTest asserts the two agree field for field. An operator who
    // deletes a key must get the same behaviour as one who never touched it.
    // -----------------------------------------------------------------------------

    static final int DEFAULT_WINDOW_RESET_HOURS = 8;
    static final int DEFAULT_AFK_IDLE_SECONDS = 300;
    static final int DEFAULT_FLUSH_INTERVAL_MINUTES = 5;
    static final String DEFAULT_PREFIX = "<gray>[<aqua>SessionPulse</aqua>]</gray> ";
    static final int DEFAULT_OVERTIME_AFTER_MINUTES = 180;
    static final int DEFAULT_OVERTIME_EVERY_MINUTES = 30;
    static final String DEFAULT_OVERTIME_MESSAGE =
            "<red>You have been playing for <white><hours></white> hours.</red>";
    static final int DEFAULT_ENFORCEMENT_AT_MINUTES = 240;
    static final String DEFAULT_KICK_MESSAGE = "<yellow>Time for a break.</yellow>";
    static final int DEFAULT_ENFORCEMENT_COOLDOWN_MINUTES = 30;

    /**
     * The milestones a file that does not mention them gets.
     *
     * <p>Identical to the two in the shipped config.yml. Distinct from an operator writing
     * {@code milestones: []}, which means "no milestones" and is honoured literally with
     * no warning - see {@link #readMilestones}.
     */
    static final List<Milestone> DEFAULT_MILESTONES = List.of(
            new Milestone(60,
                    "<aqua>You have been playing for an hour.</aqua> "
                            + "<gray>Stretch and drink some water.</gray>",
                    "<aqua>1 hour</aqua>", null, null, Sound.BLOCK_NOTE_BLOCK_CHIME),
            new Milestone(120,
                    "<yellow>Two hours in.</yellow> "
                            + "<gray>Look at something 20 feet away for 20 seconds.</gray>",
                    null, "<yellow>Eye break</yellow>", "<gray>20 seconds, 20 feet</gray>", null));

    private static final String MILESTONES = "reminders.milestones";

    private final int windowResetHours;
    private final AfkMode afkMode;
    private final int afkIdleSeconds;
    private final int flushIntervalMinutes;
    private final String reminderPrefix;
    private final List<Milestone> milestones;
    private final OvertimePolicy overtime;
    private final EnforcementPolicy enforcement;
    private final List<String> warnings = new ArrayList<>();

    /**
     * Reads, validates and clamps a configuration.
     *
     * @param config any section - {@code JavaPlugin#getConfig()} in production, a
     *               standalone {@code YamlConfiguration} in every test. Never {@code null}
     */
    public PluginConfig(ConfigurationSection config) {
        this.windowResetHours = clamped(config, "tracking.window-reset-hours",
                DEFAULT_WINDOW_RESET_HOURS, MIN_WINDOW_RESET_HOURS, MAX_WINDOW_RESET_HOURS);
        this.afkMode = enumValue(config, "tracking.afk.mode", AfkMode.AUTO,
                name -> AfkMode.parse(name, AfkMode.AUTO));
        this.afkIdleSeconds = clamped(config, "tracking.afk.idle-seconds",
                DEFAULT_AFK_IDLE_SECONDS, MIN_AFK_IDLE_SECONDS, MAX_AFK_IDLE_SECONDS);
        this.flushIntervalMinutes = clamped(config, "tracking.flush-interval-minutes",
                DEFAULT_FLUSH_INTERVAL_MINUTES,
                MIN_FLUSH_INTERVAL_MINUTES, MAX_FLUSH_INTERVAL_MINUTES);

        this.reminderPrefix = message(config, "reminders.prefix", DEFAULT_PREFIX);
        this.milestones = readMilestones(config);

        this.overtime = new OvertimePolicy(
                config.getBoolean("reminders.overtime.enabled", false),
                clamped(config, "reminders.overtime.after-minutes",
                        DEFAULT_OVERTIME_AFTER_MINUTES,
                        MIN_OVERTIME_AFTER_MINUTES, MAX_OVERTIME_AFTER_MINUTES),
                clamped(config, "reminders.overtime.every-minutes",
                        DEFAULT_OVERTIME_EVERY_MINUTES,
                        MIN_OVERTIME_EVERY_MINUTES, MAX_OVERTIME_EVERY_MINUTES),
                message(config, "reminders.overtime.message", DEFAULT_OVERTIME_MESSAGE));

        this.enforcement = new EnforcementPolicy(
                config.getBoolean("enforcement.enabled", false),
                clamped(config, "enforcement.at-minutes", DEFAULT_ENFORCEMENT_AT_MINUTES,
                        MIN_ENFORCEMENT_AT_MINUTES, MAX_ENFORCEMENT_AT_MINUTES),
                message(config, "enforcement.kick-message", DEFAULT_KICK_MESSAGE),
                clamped(config, "enforcement.cooldown-minutes",
                        DEFAULT_ENFORCEMENT_COOLDOWN_MINUTES,
                        MIN_ENFORCEMENT_COOLDOWN_MINUTES, MAX_ENFORCEMENT_COOLDOWN_MINUTES));
    }

    // -----------------------------------------------------------------------------
    // Scalars
    // -----------------------------------------------------------------------------

    /**
     * Reads an int, corrects it into range, and names the correction.
     *
     * <p>Both numbers appear in the warning, because the operator will read the value back
     * out of their own file and believe it. A value already in range is silent.
     */
    private int clamped(ConfigurationSection config, String key, int fallback, int min, int max) {
        int configured = config.getInt(key, fallback);
        int corrected = Math.max(min, Math.min(max, configured));
        if (corrected != configured) {
            warnings.add(key + " is " + configured + ", which is outside the supported range "
                    + min + "-" + max + ". Using " + corrected + " instead.");
        }
        return corrected;
    }

    /**
     * Reads an enum-valued key and names an unrecognised one.
     *
     * <p>A typo in an enum value is the one correction with no visible symptom: the plugin
     * keeps running, the file still reads the way the operator wrote it, and the behaviour
     * is simply not what it says.
     */
    private <E extends Enum<E>> E enumValue(ConfigurationSection config, String key,
                                            E fallback,
                                            Function<String, E> parser) {
        String configured = config.getString(key);
        if (configured == null || configured.isBlank()) {
            return fallback;
        }
        E parsed = parser.apply(configured);
        if (!parsed.name().equalsIgnoreCase(configured.trim())) {
            warnings.add(key + " is '" + configured + "', which is not a recognised value. Using "
                    + parsed.name() + " instead. Valid values: "
                    + Arrays.toString(fallback.getDeclaringClass().getEnumConstants()));
        }
        return parsed;
    }

    /**
     * Reads a MiniMessage scalar, falling back to the shipped default when it is unusable.
     *
     * <p>Falls back rather than skipping, and the asymmetry against a milestone entry is
     * deliberate. A milestone is one alert out of any number and losing it costs that
     * alert. The prefix, the overtime message and the kick message are each the only one
     * of their kind, and skipping one would either silence a feature the operator switched
     * on or - for the kick message - disconnect somebody with a blank screen. A typo must
     * not become a policy change, so the default is substituted and the substitution is
     * named.
     */
    private String message(ConfigurationSection config, String key, String fallback) {
        String configured = config.getString(key);
        if (configured == null) {
            return fallback;
        }
        String problem = MessageCheck.problem(configured);
        if (problem == null) {
            return configured;
        }
        warnings.add(key + " is not valid MiniMessage (" + problem
                + "). Using the built-in default instead: " + fallback);
        return fallback;
    }

    // -----------------------------------------------------------------------------
    // Milestones
    // -----------------------------------------------------------------------------

    /**
     * Reads the milestone list, skipping every entry it cannot use and naming each one.
     *
     * <p>Uses {@code get}, not {@code getMapList}. {@code getMapList} silently drops
     * anything that is not a map, and silently is the one thing this method may not be:
     * an operator who wrote {@code - 60} instead of {@code - minute: 60} would otherwise
     * lose the entry with nothing in the log.
     *
     * <p>An <b>absent</b> key means "the operator did not express a preference" and gets
     * {@link #DEFAULT_MILESTONES}, which is what their file said before they deleted the
     * block. An <b>empty list</b> means "no milestones" and is honoured literally, with no
     * warning: switching the feature off in the file must not produce a complaint.
     *
     * <p>Entries are numbered from 1 as an operator counts them, and the numbering is of
     * the <b>file</b>, not of the survivors - so entry 4 stays entry 4 whether or not
     * entries 2 and 3 were skipped. The config.yml comment says so, because a log line
     * naming a position the reader counts differently is worse than no position at all.
     */
    private List<Milestone> readMilestones(ConfigurationSection config) {
        Object raw = config.get(MILESTONES);
        if (raw == null) {
            return DEFAULT_MILESTONES;
        }
        if (!(raw instanceof List<?> entries)) {
            warnings.add(MILESTONES + " is not a list. It must be a series of `- minute:` "
                    + "blocks. Using the built-in milestones instead.");
            return DEFAULT_MILESTONES;
        }

        List<Milestone> parsed = new ArrayList<>();
        Map<Integer, Integer> minuteToEntry = new LinkedHashMap<>();
        for (int i = 0; i < entries.size(); i++) {
            int entry = i + 1;
            Milestone milestone = readMilestone(entries.get(i), entry, minuteToEntry);
            if (milestone != null) {
                minuteToEntry.put(milestone.minute(), entry);
                parsed.add(milestone);
            }
        }
        // Sorted so the tracker can walk them in order and stop at the first one it has
        // not reached, rather than scanning the whole list every tick. An operator is free
        // to write them in any order; the file is theirs to organise.
        parsed.sort(Comparator.comparingInt(Milestone::minute));
        return Collections.unmodifiableList(parsed);
    }

    /** One entry, or {@code null} when it was skipped. Every {@code null} adds a warning. */
    private Milestone readMilestone(Object raw, int entry, Map<Integer, Integer> seen) {
        String where = MILESTONES + " entry " + entry;

        if (!(raw instanceof Map<?, ?> map)) {
            warnings.add(where + " is not a block of settings, so there is nothing to read a "
                    + "minute from. Skipping it; it will not fire.");
            return null;
        }

        Object minuteValue = map.get("minute");
        if (minuteValue == null) {
            warnings.add(where + " has no 'minute', so there is no time for it to fire at. "
                    + "Skipping it; it will not fire.");
            return null;
        }
        Integer minute = asInt(minuteValue);
        if (minute == null) {
            warnings.add(where + " has minute '" + minuteValue + "', which is not a whole "
                    + "number of minutes. Skipping it; it will not fire.");
            return null;
        }
        // Skipped, NOT clamped, and this is the one place the two corrections part company.
        // Clamping minute -5 up to 1 would invent an alert the operator never asked for and
        // fire it at everyone who joins. There is no defensible value to substitute, so
        // there is no substitution.
        if (minute < MIN_MILESTONE_MINUTE || minute > MAX_MILESTONE_MINUTE) {
            warnings.add(where + " has minute " + minute + ", which is outside "
                    + MIN_MILESTONE_MINUTE + "-" + MAX_MILESTONE_MINUTE + ". Skipping it; it "
                    + "will not fire. A milestone minute is not clamped into range, because "
                    + "there is no minute that could stand in for one you did not choose.");
            return null;
        }
        Integer duplicateOf = seen.get(minute);
        if (duplicateOf != null) {
            warnings.add(where + " has minute " + minute + ", which "
                    + MILESTONES + " entry " + duplicateOf + " already uses. Only one alert "
                    + "can fire at a given minute. Skipping entry " + entry + "; entry "
                    + duplicateOf + " is the one that will fire.");
            return null;
        }

        String message = text(map, "message");
        String actionBar = text(map, "action-bar");
        String title = text(map, "title");
        String subtitle = text(map, "subtitle");

        for (String[] field : new String[][] {
                {"message", message}, {"action-bar", actionBar},
                {"title", title}, {"subtitle", subtitle}}) {
            String problem = MessageCheck.problem(field[1]);
            if (problem != null) {
                warnings.add(where + " (minute " + minute + ") has a '" + field[0]
                        + "' that is not valid MiniMessage: " + problem
                        + " Skipping the whole entry; it will not fire. Fix the tag rather "
                        + "than deleting the line - a milestone is never replaced with a "
                        + "default.");
                return null;
            }
        }

        Sound sound = null;
        Object soundValue = map.get("sound");
        if (soundValue != null) {
            String name = String.valueOf(soundValue).trim();
            try {
                // org.bukkit.Sound is an enum on the 1.20 API this plugin compiles against
                // and the constants are read from the RUNNING server, so a sound added in a
                // later Minecraft version resolves on a server that has it. Bukkit's
                // registry migration turns Sound into an interface in a later API, at which
                // point this call site has to become a Registry.SOUNDS lookup.
                sound = Sound.valueOf(name.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                // Warned and dropped, and the entry survives. The sound is decoration on an
                // alert whose point is its text; skipping the whole milestone over it would
                // cost the operator the reminder to save the chime.
                warnings.add(where + " (minute " + minute + ") names sound '" + name
                        + "', which this server does not have. The milestone will fire "
                        + "silently. Sound names are the Bukkit enum, for example "
                        + "BLOCK_NOTE_BLOCK_CHIME.");
            }
        }

        Milestone milestone = new Milestone(minute, message, actionBar, title, subtitle, sound);
        if (!milestone.hasAnythingToShow()) {
            warnings.add(where + " (minute " + minute + ") has no message, action-bar, title, "
                    + "subtitle or sound, so it would fire and do nothing. Skipping it.");
            return null;
        }
        return milestone;
    }

    /**
     * A map value as text, exactly as written, or {@code null} when absent or blank.
     *
     * <p>Deliberately not trimmed. A trailing space in a message is a formatting choice -
     * the shipped {@code reminders.prefix} ends in one - and this method is not the place
     * to overrule it.
     */
    private static String text(Map<?, ?> map, String key) {
        Object value = map.get(key);
        if (value == null) {
            return null;
        }
        String string = String.valueOf(value);
        return string.isBlank() ? null : string;
    }

    /**
     * A YAML scalar as an int, or {@code null} when it is not one.
     *
     * <p>Snakeyaml gives an {@code Integer} for {@code minute: 60} and a {@code String} for
     * {@code minute: "60"}. Both are accepted, because quoting a number is not the mistake
     * this method exists to catch.
     *
     * <p>A decimal is rejected rather than truncated. {@code minute: 60.9} arrives as a
     * {@code Double}, and {@code Number#intValue()} would silently make it 60 - inventing a
     * value the operator did not write, which is the same failure this class refuses for
     * {@code minute: -5}. It falls through to the "not a whole number of minutes" warning
     * instead.
     */
    private static Integer asInt(Object value) {
        if (value instanceof Number number) {
            if (number.doubleValue() != number.intValue()) {
                return null;
            }
            return number.intValue();
        }
        try {
            return Integer.valueOf(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // -----------------------------------------------------------------------------
    // Accessors. Record-style, no `get` prefix, matching SessionPulsePlugin#audiences()
    // and #scheduler() and the four records this class returns.
    // -----------------------------------------------------------------------------

    /**
     * Offline gap, in hours, that starts a fresh counted window.
     *
     * @return the gap in hours, always within {@code 1-168}
     */
    public int windowResetHours() {
        return windowResetHours;
    }

    /**
     * How the session clock decides a player has stopped playing.
     *
     * @return the configured mode, never {@code null}
     */
    public AfkMode afkMode() {
        return afkMode;
    }

    /**
     * Idle seconds before the built-in detector calls a player AFK.
     *
     * @return the idle threshold in seconds, always within {@code 30-3600}
     */
    public int afkIdleSeconds() {
        return afkIdleSeconds;
    }

    /**
     * Minutes between writes of the counted window to storage.
     *
     * @return the flush interval in minutes, always within {@code 1-60}
     */
    public int flushIntervalMinutes() {
        return flushIntervalMinutes;
    }

    /**
     * MiniMessage prepended to every chat message this plugin sends.
     *
     * @return the prefix, never {@code null} and possibly empty
     */
    public String reminderPrefix() {
        return reminderPrefix;
    }

    /**
     * The usable milestones, sorted by minute.
     *
     * @return an unmodifiable list, possibly empty, never {@code null}
     */
    public List<Milestone> milestones() {
        return milestones;
    }

    /**
     * The recurring overtime reminder.
     *
     * @return the policy, never {@code null}
     */
    public OvertimePolicy overtime() {
        return overtime;
    }

    /**
     * The optional disconnect policy.
     *
     * @return the policy, never {@code null}
     */
    public EnforcementPolicy enforcement() {
        return enforcement;
    }

    /**
     * Complaints about this configuration, in the order they were found, for the caller to
     * log.
     *
     * @return an unmodifiable list, empty when the file needed no correcting
     */
    public List<String> warnings() {
        return Collections.unmodifiableList(warnings);
    }
}
