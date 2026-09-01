package com.ninja6.sessionpulse.session;

import java.util.Collection;
import java.util.Collections;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * One player's live session state, for as long as they are connected.
 *
 * <p>Created by {@link SessionTracker} on join and thrown away on quit. It carries no
 * Bukkit type at all - a {@link UUID} and a name are the whole identity - which is what
 * lets every arithmetic path in this plugin be covered by plain JUnit against an injected
 * {@link SessionClock}.
 *
 * <h2>The two bases are final, and that is a design constraint rather than an accident</h2>
 *
 * <p>A counted-window reset is decided <em>only</em> at join, so {@code windowStartMillis},
 * {@code windowBaseSeconds} and {@code lifetimeBaseSeconds} genuinely never change for the
 * life of an instance. Making them {@code final} means the compiler, not a reviewer,
 * enforces it - and it publishes them safely to the region thread and to the async flush
 * with no fence at the read site. {@code /spulse reset} replaces the whole object rather
 * than mutating one; see {@link SessionTracker#resetWindow(UUID)}.
 *
 * <h2>Rounding happens in exactly four places</h2>
 *
 * <p>{@link #sessionSeconds()}, {@link #windowSeconds()}, {@link #windowMinutes()} and
 * {@link #lifetimeSeconds()} are the only methods in the plugin that divide a nanosecond
 * count down to seconds. Everything downstream - milestones, overtime, enforcement,
 * storage - reads one of those four. Because the division is applied once to a single
 * accumulator and then added to immutable bases, the truncation never compounds: a
 * thousand one-millisecond ticks accrue exactly one second, not zero.
 *
 * <h2>Threads</h2>
 *
 * <ul>
 *   <li>{@code accruedNanos} - written by the session tick and by the quit path, read by
 *       the async flush. {@link AtomicLong}, added to and never assigned.</li>
 *   <li>{@code lastAccrualNanos} - {@code getAndSet} only, never read on its own. The tick
 *       and the quit path can both try to claim the tail of the same interval; each
 *       {@code getAndSet} returns the previous mark, so the two claims
 *       <em>partition</em> the interval between them. No nanosecond is credited twice, and
 *       none between the two claims is dropped. (A tick that loses the race and claims
 *       <em>after</em> the quit snapshot has been built credits an object nobody will read
 *       again, so that last sub-tick sliver is discarded. Bounded by one tick period and
 *       accepted.) A plain {@code volatile long} would not give this: read-then-write is
 *       two operations and both callers could read the same mark.</li>
 *   <li>{@code afkSinceNanos} - {@code volatile}. Single writer (the session tick), many
 *       readers, no compound action.</li>
 *   <li>{@code firedMinutes} - a concurrent set. Written by the tick when a milestone
 *       fires, seeded on the main thread at join and at reload.</li>
 * </ul>
 */
public final class PlayerSession {

    /**
     * The value {@link #afkSinceNanos()} carries when the player is not AFK.
     *
     * <p>A sentinel rather than a {@code Long}, because this field is read on every tick
     * for every online player and boxing it would allocate for nothing.
     */
    public static final long NOT_AFK = Long.MIN_VALUE;

    private static final long NANOS_PER_SECOND = 1_000_000_000L;

    private final UUID uuid;
    private final String name;
    private final long sessionStartNanos;
    private final long sessionStartWallMillis;
    private final long windowStartMillis;
    private final long windowBaseSeconds;
    private final long lifetimeBaseSeconds;

    private final AtomicLong accruedNanos = new AtomicLong();
    private final AtomicLong lastAccrualNanos;
    private final Set<Integer> firedMinutes = ConcurrentHashMap.newKeySet();

    private volatile long afkSinceNanos = NOT_AFK;

    /**
     * Creates a live session.
     *
     * <p>Package-private: {@link SessionTracker} is the only thing that may decide what the
     * bases are, because deciding them is the counted-window rollover rule.
     *
     * @param uuid                   the player
     * @param name                   their name at join, for storage and for the leaderboard
     * @param sessionStartNanos      monotonic reading at join; also the first accrual mark
     * @param sessionStartWallMillis calendar time at join, for display only
     * @param windowStartMillis      calendar time the counted window began - carried over
     *                               from storage, or now if the window was reset
     * @param windowBaseSeconds      counted-window seconds already banked before this
     *                               session, or zero if the window was reset
     * @param lifetimeBaseSeconds    lifetime seconds already banked. Never reset
     */
    PlayerSession(UUID uuid, String name, long sessionStartNanos, long sessionStartWallMillis,
                  long windowStartMillis, long windowBaseSeconds, long lifetimeBaseSeconds) {
        this.uuid = uuid;
        this.name = name;
        this.sessionStartNanos = sessionStartNanos;
        this.sessionStartWallMillis = sessionStartWallMillis;
        this.windowStartMillis = windowStartMillis;
        this.windowBaseSeconds = windowBaseSeconds;
        this.lifetimeBaseSeconds = lifetimeBaseSeconds;
        this.lastAccrualNanos = new AtomicLong(sessionStartNanos);
    }

    /**
     * The player this session belongs to.
     *
     * @return their unique id, never {@code null}
     */
    public UUID uuid() {
        return uuid;
    }

    /**
     * Their name as it was when they joined.
     *
     * @return the name, never {@code null}
     */
    public String name() {
        return name;
    }

    /**
     * The instant this session began, in epoch milliseconds.
     *
     * <p><strong>For display only. Never subtract from it.</strong>
     * {@code now - sessionStartMillis()} is wall-clock time connected, which is not what
     * this plugin counts: it includes every AFK pause, and it moves when the operator
     * moves the system clock. {@link #sessionSeconds()} is the elapsed figure, and the two
     * will legitimately disagree.
     *
     * @return calendar time at join
     */
    public long sessionStartMillis() {
        return sessionStartWallMillis;
    }

    /**
     * The instant the current counted window began, in epoch milliseconds.
     *
     * <p>Carried across quits and rejoins until a gap longer than
     * {@code tracking.window-reset-hours} starts a new one. Persisted.
     *
     * @return calendar time the window began
     */
    public long windowStartMillis() {
        return windowStartMillis;
    }

    /**
     * Counted seconds accrued in <em>this</em> session, excluding AFK time.
     *
     * @return whole seconds, truncated
     */
    public long sessionSeconds() {
        return accruedNanos.get() / NANOS_PER_SECOND;
    }

    /**
     * Counted seconds in the current window: what came out of storage, plus this session.
     *
     * <p>This is the number milestones, overtime and enforcement all measure against.
     *
     * @return whole seconds, truncated
     */
    public long windowSeconds() {
        return windowBaseSeconds + sessionSeconds();
    }

    /**
     * The counted window in whole minutes, truncated rather than rounded.
     *
     * <p>Truncated deliberately: a milestone at minute 60 must not fire at 59 minutes and
     * 30 seconds, because the operator wrote an hour and will time it.
     *
     * @return whole minutes of counted window
     */
    public long windowMinutes() {
        return windowSeconds() / 60L;
    }

    /**
     * Counted seconds over this player's whole history, across every window.
     *
     * <p>Excludes AFK time, exactly as {@link #windowSeconds()} does, so the two figures
     * {@code /spulse time} prints never disagree about what "playing" means.
     *
     * @return whole seconds, truncated
     */
    public long lifetimeSeconds() {
        return lifetimeBaseSeconds + sessionSeconds();
    }

    /**
     * Whether the player is currently counted as away.
     *
     * @return {@code true} while the clock is paused for them
     */
    public boolean isAfk() {
        return afkSinceNanos != NOT_AFK;
    }

    /**
     * The monotonic reading at which the current AFK spell was first observed.
     *
     * <p>Named for what it is because it is a monotonic value and must never be treated as
     * a calendar time. Only meaningful while {@link #isAfk()} is {@code true}.
     *
     * @return the reading, or {@link #NOT_AFK} when the player is active
     */
    public long afkSinceNanos() {
        return afkSinceNanos;
    }

    /**
     * Whether the milestone at this minute has already fired in the current window.
     *
     * @param minute the milestone's minute
     * @return {@code true} if it has fired or was seeded as fired
     */
    public boolean hasFired(int minute) {
        return firedMinutes.contains(minute);
    }

    /**
     * Records a milestone as fired.
     *
     * @param minute the milestone's minute
     * @return {@code true} if this call was the one that marked it, {@code false} if it
     *         had already fired. Callers fire only on {@code true}, which is what makes
     *         "exactly once per counted window" hold even if two ticks overlap
     */
    public boolean markFired(int minute) {
        return firedMinutes.add(minute);
    }

    /**
     * Marks a batch of milestones as already fired.
     *
     * <p>Used at join to suppress every milestone the player already passed earlier in the
     * same counted window, and at reload for the same reason.
     *
     * @param minutes the minutes to suppress; may be empty, never {@code null}
     */
    public void seedFired(Collection<Integer> minutes) {
        firedMinutes.addAll(minutes);
    }

    /**
     * The milestones already fired in this window.
     *
     * @return an unmodifiable snapshot view; use {@link #markFired(int)} to add
     */
    public Set<Integer> firedMinutes() {
        return Collections.unmodifiableSet(firedMinutes);
    }

    /**
     * Takes ownership of the interval since the previous claim.
     *
     * <p>The single atomic operation the whole accrual scheme rests on. See the thread
     * notes on this class.
     *
     * @param now a monotonic reading
     * @return nanoseconds since the previous claim
     */
    long claimDeltaNanos(long now) {
        return now - lastAccrualNanos.getAndSet(now);
    }

    /** Adds counted time. */
    void credit(long nanos) {
        accruedNanos.addAndGet(nanos);
    }

    /** Records the start of an AFK spell at the given monotonic reading. */
    void markAfk(long nanos) {
        afkSinceNanos = nanos;
    }

    /** Ends an AFK spell. */
    void clearAfk() {
        afkSinceNanos = NOT_AFK;
    }

    /** The monotonic reading this session started at. Never leaves the package. */
    long sessionStartNanos() {
        return sessionStartNanos;
    }

    /** The lifetime figure banked before this session, for a window reset to carry over. */
    long lifetimeBaseSeconds() {
        return lifetimeBaseSeconds;
    }
}
