package com.ninja6.sessionpulse.session;

import com.ninja6.sessionpulse.config.Milestone;
import com.ninja6.sessionpulse.config.PluginConfig;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * The counted window: all of the arithmetic, and none of the Bukkit.
 *
 * <p>Not one type from {@code org.bukkit} is named in this file, which is what makes every
 * path here reachable from plain JUnit with an injected {@link SessionClock} and no server
 * and no mock framework. The class that does resolve a {@link UUID} to a player is
 * {@code SessionTickTask}, and it decides nothing.
 *
 * <h2>Invariants</h2>
 *
 * <ul>
 *   <li><b>I1</b> - a monotonic reading never leaves {@link PlayerSession}. It is never
 *       persisted, never compared against a calendar value, and never returned by a public
 *       accessor except {@code afkSinceNanos()}, which is named for what it is.</li>
 *   <li><b>I2</b> - every persisted value is calendar milliseconds or whole seconds.
 *       {@link SessionSnapshot} is the only type that crosses to storage and it holds
 *       nothing else.</li>
 *   <li><b>I3</b> - the nanoseconds-to-seconds conversion is confined to
 *       {@link PlayerSession}'s four second-and-minute accessors. No other class in the
 *       plugin divides by a nanosecond scale.</li>
 *   <li><b>I4</b> - a window reset is decided <em>only</em> at join, which is why the
 *       window fields on {@link PlayerSession} can be {@code final}.</li>
 * </ul>
 *
 * <h2>The configuration is a supplier, never the object</h2>
 *
 * <p>This class is handed {@code plugin::config} and reads it on every call that needs a
 * value. {@link PluginConfig} is an immutable snapshot that {@code /spulse reload}
 * replaces; a service that captured the object would keep running the previous file's
 * settings for ever, with the command reporting success.
 */
public final class SessionTracker {

    private static final long MILLIS_PER_HOUR = 3_600_000L;

    private final Supplier<PluginConfig> config;
    private final SessionClock clock;
    private final SessionStore store;
    private final Map<UUID, PlayerSession> sessions = new ConcurrentHashMap<>();

    /**
     * Creates a tracker.
     *
     * @param config a supplier of the configuration in force, read per call. Never the
     *               object itself
     * @param clock  the time seam
     * @param store  where counted state is loaded from and saved to
     */
    public SessionTracker(Supplier<PluginConfig> config, SessionClock clock, SessionStore store) {
        this.config = config;
        this.clock = clock;
        this.store = store;
    }

    /**
     * Starts tracking a player, resuming or resetting their counted window.
     *
     * <p><strong>Idempotent.</strong> A player already being tracked gets their existing
     * session back, unchanged. That is not defensive tidiness: the plugin seeds itself from
     * the already-online list at enable and also listens for joins, and a hard client drop
     * whose quit event never arrived is followed by a join for a player still in the map.
     * A second {@code put} would re-run the reset decision against the same stale stored
     * record and throw away everything accrued since.
     *
     * <p>The reset decision compares two calendar values and nothing else. It cannot
     * involve a monotonic reading, because the previous session may have run in a
     * different JVM. The comparison is strictly greater-than: the counted window resets
     * once the player has been offline <em>longer than</em>
     * {@code tracking.window-reset-hours}, so a gap of exactly that many hours keeps the
     * window.
     *
     * <p>A backwards system clock produces a negative gap and therefore never grants a
     * fresh window. Accepted, and the safe direction to fail in.
     *
     * @param uuid the player
     * @param name their name
     * @return their live session, new or existing, never {@code null}
     */
    public PlayerSession onJoin(UUID uuid, String name) {
        return sessions.computeIfAbsent(uuid, key -> {
            long wallNow = clock.wallMillis();
            long nanoNow = clock.nanoTime();
            SessionSnapshot stored = store.load(key);

            PluginConfig current = config.get();
            long gapMillis = wallNow - stored.lastSeenMillis();
            long thresholdMillis = (long) current.windowResetHours() * MILLIS_PER_HOUR;
            boolean reset = stored.isUnknown() || gapMillis > thresholdMillis;

            PlayerSession session = new PlayerSession(
                    key,
                    name,
                    nanoNow,
                    wallNow,
                    reset ? wallNow : stored.windowStartMillis(),
                    reset ? 0L : stored.windowSeconds(),
                    // Lifetime survives whatever happened above. It is never reset.
                    stored.lifetimeSeconds());

            if (!reset) {
                session.seedFired(passedMilestones(current, session.windowMinutes()));
            }
            return session;
        });
    }

    /**
     * Credits one tick of counted time, or discards it because the player is away.
     *
     * <p>The rule in one sentence: a tick's interval is credited if and only if the player
     * is not AFK at the moment that tick runs.
     *
     * <p>The claim happens on every tick, AFK or not, which is what makes the pause exact:
     * no unclaimed interval is left behind for a later tick to back-date. The AFK test sits
     * <em>above</em> the zero-interval guard so that a tick which claims nothing still
     * records the transition.
     *
     * <p>Delta-based, not "add the tick period". A lagging server, a skipped tick, or a
     * later issue changing the tick period all leave the arithmetic exact; a period-based
     * accumulator would drift on every one of those and nothing would report it.
     *
     * @param uuid the player
     * @param afk  what the AFK gate said about them, right now
     * @return their session, or {@code null} if they are not being tracked
     */
    public PlayerSession accrue(UUID uuid, boolean afk) {
        PlayerSession session = sessions.get(uuid);
        if (session == null) {
            return null;
        }
        long now = clock.nanoTime();
        long delta = session.claimDeltaNanos(now);

        if (afk) {
            if (!session.isAfk()) {
                session.markAfk(now);
            }
            // The interval is discarded. This is the pause.
            return session;
        }
        if (session.isAfk()) {
            session.clearAfk();
        }
        // Guarded rather than trusted: a monotonic clock does not go backwards, but a
        // second claim in the same nanosecond legitimately yields zero.
        if (delta > 0L) {
            session.credit(delta);
        }
        return session;
    }

    /**
     * Stops tracking a player, credits the tail of their session and stores the result.
     *
     * <p>Removes first, then finalises. A tick already holding the same reference races the
     * quit for the tail; the two claims partition the interval, so nothing is counted
     * twice. A tick that has not yet started finds nothing and does nothing.
     *
     * <p>A player quitting while AFK contributes no tail, consistent with the tick rule.
     *
     * <p>This method saves, rather than returning the snapshot for a caller to save. The
     * store is wired in one place - here - so that swapping one store for another cannot
     * land on the load path and miss the save path.
     *
     * @param uuid the player
     * @return what was stored, or {@code null} if they were not being tracked
     */
    public SessionSnapshot onQuit(UUID uuid) {
        PlayerSession session = sessions.remove(uuid);
        if (session == null) {
            return null;
        }
        long delta = session.claimDeltaNanos(clock.nanoTime());
        if (delta > 0L && !session.isAfk()) {
            session.credit(delta);
        }
        SessionSnapshot snapshot = snapshotOf(session, clock.wallMillis());
        store.save(uuid, snapshot);
        return snapshot;
    }

    /**
     * A player's live session.
     *
     * @param uuid the player
     * @return their session, or {@code null} if they are offline or untracked
     */
    public PlayerSession session(UUID uuid) {
        return sessions.get(uuid);
    }

    /**
     * Every live session.
     *
     * <p>A live, weakly consistent view: iterating it while a player joins or quits neither
     * throws nor needs a copy, which is exactly what the session tick wants. Unmodifiable,
     * because the backing collection is the map's own value view and a stray
     * {@code remove} on it would silently drop a live session with nothing in the log.
     *
     * @return the sessions, never {@code null}
     */
    public Collection<PlayerSession> sessions() {
        return Collections.unmodifiableCollection(sessions.values());
    }

    /**
     * Every online player's counted state, stamped with the current calendar time.
     *
     * <p><strong>This is a heartbeat, not merely a dump, and the stamp is load-bearing.</strong>
     * The offline gap that decides a window reset is measured from the stored last-seen
     * time. If a periodic flush wrote records for online players without refreshing it,
     * then a player who joined on Tuesday evening and was still online when the server
     * died on Wednesday morning would carry Monday's stamp - and on rejoining would be
     * handed a brand new counted window, because the gap computed from that stale stamp is
     * enormous. That is precisely the "quit and rejoin for a fresh allowance" bypass the
     * counted window exists to prevent, arriving through the back door of a crash.
     *
     * <p>Stamping here bounds the damage instead: after a hard crash the stored last-seen
     * is at most one flush interval old, so a window reset can trigger at most that early.
     *
     * @return a fresh map, safe to hand to an off-thread flush
     */
    public Map<UUID, SessionSnapshot> snapshotAll() {
        long wallNow = clock.wallMillis();
        Map<UUID, SessionSnapshot> all = new HashMap<>();
        for (Map.Entry<UUID, PlayerSession> entry : sessions.entrySet()) {
            all.put(entry.getKey(), snapshotOf(entry.getValue(), wallNow));
        }
        return all;
    }

    /**
     * Starts a player's counted window again from zero, here and now.
     *
     * <p>For the administrative reset command. The window fields on a session are final, so
     * this replaces the object rather than mutating it - which also zeroes "this session":
     * the new object's session start is now, and its accrued time is nothing. That is
     * stated rather than fixed, because an operator resetting somebody's window is not
     * usually interested in preserving the session figure, and preserving it would mean
     * giving up the final fields that make the window rules enforceable by the compiler.
     *
     * <p>Lifetime is carried across untouched. Offline players are the storage layer's
     * problem, not this method's.
     *
     * @param uuid the player
     * @return their new session, or {@code null} if they are not online
     */
    public PlayerSession resetWindow(UUID uuid) {
        return sessions.computeIfPresent(uuid, (key, previous) -> {
            // One reading each, used twice. Two calls could straddle a millisecond and
            // leave the session start and the window start disagreeing about "now".
            long wallNow = clock.wallMillis();
            return new PlayerSession(key, previous.name(), clock.nanoTime(), wallNow,
                    wallNow, 0L, previous.lifetimeSeconds());
        });
    }

    /**
     * Claims every milestone the counted window has reached and not yet fired, in ascending
     * minute order.
     *
     * <p>Claiming is the decision, and it happens here, on the session tick, where the window
     * is measured. A milestone is returned only by the call whose {@link PlayerSession#markFired}
     * won, so two overlapping ticks can never both return it. Delivery is somebody else's job
     * and runs later on the player's own region; a claim whose delivery never runs stays
     * claimed, which loses the alert rather than repeating it.
     *
     * <p>Reached means {@code minute <= windowMinutes}, the same comparison seeding uses, so
     * a claim and a seed can never disagree about a boundary. A milestone missed by a lagging
     * tick is still claimed on the next one.
     *
     * <p>The walk stops at the first milestone not yet reached, which relies on
     * {@link PluginConfig#milestones()} being sorted by minute.
     *
     * @param session the player's live session, with this tick already credited
     * @return the milestones to deliver now; empty, and shared, in the common case
     */
    public List<Milestone> claimDue(PlayerSession session) {
        PluginConfig current = config.get();
        if (current == null) {
            return List.of();
        }
        // Read once. Two reads could straddle a minute and claim against two windows.
        long windowMinutes = session.windowMinutes();
        List<Milestone> due = null;
        for (Milestone milestone : current.milestones()) {
            if (!reached(milestone.minute(), windowMinutes)) {
                break;
            }
            if (session.markFired(milestone.minute())) {
                if (due == null) {
                    due = new ArrayList<>(2);
                }
                due.add(milestone);
            }
        }
        return due == null ? List.of() : due;
    }

    /**
     * Stores one player's counted state now, rather than at the next periodic flush.
     *
     * <p>For a milestone claim. The fired set is not persisted; what suppresses a milestone
     * after a restart is the stored window having reached its minute. The periodic flush can
     * be a whole interval behind, so without this a crash just after an alert would store a
     * window short of it and the alert would fire again on rejoin.
     *
     * @param session the player's live session
     */
    public void checkpoint(PlayerSession session) {
        store.save(session.uuid(), snapshotOf(session, clock.wallMillis()));
    }

    /**
     * Publishes a new configuration without letting it fire anything the windows already
     * passed.
     *
     * <p>For {@code /spulse reload}: an operator who adds a milestone at minute 30 while
     * somebody has been playing for two hours should not have it fire at them immediately.
     * The order is seed, publish, seed again, and each step is load-bearing:
     *
     * <ul>
     *   <li>Seeding against {@code next} <em>before</em> it is published closes the race with
     *       the tick. On Folia a player's {@code /spulse reload} runs on their region thread
     *       and the tick on the global one, so a tick landing between publish and seed would
     *       claim the new milestone for everyone already past it.</li>
     *   <li>Seeding again <em>after</em> it is published covers a player who joined in
     *       between and was seeded against the previous configuration.</li>
     * </ul>
     *
     * <p>Seeding is additive and idempotent: a milestone already fired stays fired, and the
     * second pass only adds what the first could not see. A milestone the new file removes
     * can still be claimed from the old configuration by a tick running between the first
     * seed and the publish, for a player crossing it at that instant. Accepted.
     *
     * @param next    the configuration about to be in force
     * @param publish makes {@code next} the configuration the supplier returns
     */
    public void applyReload(PluginConfig next, Consumer<PluginConfig> publish) {
        seedAll(next);
        publish.accept(next);
        seedAll(next);
    }

    /**
     * Re-seeds every live session's fired-milestone set against the configuration in force.
     *
     * <p>Seeding is additive - a milestone already fired stays fired. A reload goes through
     * {@link #applyReload}, which orders this against publication.
     */
    public void reseedFiredMilestones() {
        seedAll(config.get());
    }

    private void seedAll(PluginConfig source) {
        for (PlayerSession session : sessions.values()) {
            session.seedFired(passedMilestones(source, session.windowMinutes()));
        }
    }

    /** The minutes of every milestone in {@code source} the given counted window has passed. */
    private static List<Integer> passedMilestones(PluginConfig source, long windowMinutes) {
        List<Integer> passed = new ArrayList<>();
        for (Milestone milestone : source.milestones()) {
            if (reached(milestone.minute(), windowMinutes)) {
                passed.add(milestone.minute());
            }
        }
        return passed;
    }

    /**
     * Whether a counted window of {@code windowMinutes} has reached a milestone. The one
     * boundary claiming and seeding share: 59 minutes has not reached 60, and 60 has.
     */
    private static boolean reached(int minute, long windowMinutes) {
        return minute <= windowMinutes;
    }

    /** The one shape a session takes when it crosses to storage. */
    private static SessionSnapshot snapshotOf(PlayerSession session, long wallNow) {
        return new SessionSnapshot(
                session.name(),
                session.lifetimeSeconds(),
                session.windowStartMillis(),
                session.windowSeconds(),
                wallNow);
    }
}
