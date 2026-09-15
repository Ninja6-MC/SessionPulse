package com.ninja6.sessionpulse.session;

import com.ninja6.sessionpulse.platform.Scheduler;

import java.util.function.LongSupplier;

/**
 * The session tick's schedule: the one repeating global task that makes the plugin count,
 * held by its own handle so a reload can replace it without touching anything else.
 *
 * <p>This is the only place in the plugin that calls {@code Scheduler#globalRepeating}.
 * {@code SessionTickScheduledTest} counts the call sites, because a plugin whose tick is never
 * scheduled boots clean, logs nothing and counts nobody, and no arithmetic test notices.
 *
 * <h2>Why a reload reschedules at all</h2>
 *
 * <p>The blanket cancel is disable-only. A reload that reached for it would stop the tick and
 * the command would report success over a plugin that no longer counts. So the tick is
 * replaced by handle, and the period is read from {@link #periodTicks} at every schedule
 * rather than captured once, which is what lets a reload change it. Today the supplier is the
 * constant {@link SessionTickTask#PERIOD_TICKS}; the reschedule is still exercised on every
 * reload so the path is proven before anything configurable depends on it.
 *
 * <h2>Ordering</h2>
 *
 * <p>{@link #reschedule()} schedules the new task first and cancels the old handle second. A
 * schedule that throws leaves the old tick counting, and the caller sees the exception. That
 * is the opposite order to {@code YamlDataStorage#rescheduleFlush}, which cancels first: a
 * missed flush costs nothing a later flush does not recover, while a missing tick is a plugin
 * that silently stops counting.
 *
 * <p>The two tasks cannot double-credit. Accrual is a delta claim against the session's own
 * mark, so whichever task runs claims only the interval since the last claim, whoever made it.
 * The new task waits its delay before its first run, and any gap that leaves is credited in
 * full on the next tick, because the mark did not move.
 *
 * <h2>Threading</h2>
 *
 * <p>Every method is synchronized on this object. Enable and disable run on the main thread; a
 * reload runs on the caller's thread, which on Folia is the global region for the console and
 * the player's own region thread for a player. That is safe because FoliaLib's global
 * scheduler accepts work from any thread, and the handle and the retired flag are only read or
 * written under the lock.
 */
public final class SessionTickSchedule {

    private final Scheduler scheduler;
    private final Runnable tick;
    private final long delayTicks;
    private final LongSupplier periodTicks;

    /** The live handle, or {@code null} before {@link #start()} and after {@link #retire()}. */
    private Scheduler.Task task;

    /**
     * Set by {@link #retire()} under the same lock a reschedule checks it under, so a reload
     * racing a disable cannot schedule a tick after disable has cancelled the last one. Mirrors
     * the stopped flag in {@code YamlDataStorage}.
     */
    private boolean retired;

    /**
     * A schedule that does nothing until {@link #start()}.
     *
     * @param scheduler   the seam
     * @param tick        the task body; the plugin passes a {@link SessionTickTask}
     * @param delayTicks  ticks before the first run of every task this schedules
     * @param periodTicks read at every schedule, never captured
     */
    public SessionTickSchedule(Scheduler scheduler, Runnable tick, long delayTicks,
                               LongSupplier periodTicks) {
        this.scheduler = scheduler;
        this.tick = tick;
        this.delayTicks = delayTicks;
        this.periodTicks = periodTicks;
    }

    /** Schedules the tick. Once; a second call, or a call after {@link #retire()}, does nothing. */
    public synchronized void start() {
        if (retired || task != null) {
            return;
        }
        task = schedule();
    }

    /**
     * Replaces the tick with one at the period the supplier gives now.
     *
     * <p>New first, then the old handle cancelled; see the class comment for why. Does nothing
     * after {@link #retire()}.
     */
    public synchronized void reschedule() {
        if (retired) {
            return;
        }
        Scheduler.Task next = schedule();
        Scheduler.Task previous = task;
        task = next;
        if (previous != null) {
            previous.cancel();
        }
    }

    /**
     * Stops the tick for good. Called from {@code onDisable} before the blanket cancel, the
     * same order {@code YamlDataStorage#shutdown} uses, so a reload already waiting on the lock
     * finds the flag set and schedules nothing. Idempotent.
     */
    public synchronized void retire() {
        retired = true;
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    /** The live handle, for tests. */
    synchronized Scheduler.Task task() {
        return task;
    }

    /** The plugin's one {@code globalRepeating} call site. Callers hold the lock. */
    private Scheduler.Task schedule() {
        return scheduler.globalRepeating(tick, delayTicks, periodTicks.getAsLong());
    }
}
