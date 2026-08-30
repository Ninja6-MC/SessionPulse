package com.ninja6.sessionpulse.platform;

import org.bukkit.entity.Entity;

/**
 * The plugin's only scheduling seam.
 *
 * <p>No implementation detail of the underlying scheduler appears here: every type named
 * in this interface is either {@code java.*}, {@code org.bukkit.*} or declared inside it.
 * That is what lets {@code FoliaLibScheduler} remain the only class in the plugin that
 * names the scheduling library, and it is checked by a unit test rather than left to
 * review.
 *
 * <p><strong>There is deliberately no {@code cancelAll} here.</strong> The tracking issue
 * records the trap: {@code /spulse reload} must cancel and reschedule the flush and the
 * session tick <em>individually</em>, through the {@link Task} handles it already holds.
 * A blanket cancel on reload also kills the session tick, the plugin silently stops
 * counting, and nothing in the log says so - the command reports success and the feature
 * is simply gone. Keeping the blanket cancel off this interface and on the concrete
 * {@code FoliaLibScheduler}, which only {@code SessionPulsePlugin} holds by concrete type,
 * turns that mistake into a compile error instead of something a reviewer has to catch.
 * This is a deliberate departure from the four-method list in the issue.
 */
public interface Scheduler {

    /**
     * A single scheduled task, and the only way to stop one.
     *
     * <p>Every scheduling method that produces something cancellable returns one of these.
     * That is deliberate: it means the ordinary way to stop a task is to cancel the handle
     * you were given, and there is no convenient blanket alternative reachable through
     * this interface at all.
     */
    interface Task {

        /** Stops this task. Calling it more than once is harmless. */
        void cancel();

        /** Whether this task has been cancelled. */
        boolean isCancelled();
    }

    /**
     * Schedules a repeating task on the global region.
     *
     * @param task        what to run
     * @param delayTicks  ticks before the first run; must be at least 1. The library logs a
     *                    warning for any lower value and silently promotes it to 1.
     * @param periodTicks ticks between runs; must be at least 1
     * @return the handle, which is the only way to stop it
     */
    Task globalRepeating(Runnable task, long delayTicks, long periodTicks);

    /**
     * Runs a task once on the region that owns {@code entity}.
     *
     * <p>On Folia a player's data may only be touched from that player's own region
     * thread, so anything reading or writing a player - including sending them a message
     * through an audience - goes through here. On Paper and Spigot this is the main thread
     * and the call is equivalent to a one-tick task.
     *
     * <p>Returns nothing, because there is nothing meaningful to cancel: the task runs
     * once, at the next opportunity.
     *
     * @param entity  the entity whose region owns the work
     * @param task    what to run
     * @param retired run instead of {@code task} if the entity has been removed, or its
     *                scheduler retired, before the task could run. Must not be
     *                {@code null}; pass {@code () -> {}} to ignore the case explicitly
     *                rather than by omission.
     */
    void entity(Entity entity, Runnable task, Runnable retired);

    /**
     * Schedules a repeating task off the server thread.
     *
     * <p>Nothing scheduled here may touch Bukkit state. Its use in this plugin is the
     * storage flush.
     *
     * @param task        what to run
     * @param delayTicks  ticks before the first run; must be at least 1
     * @param periodTicks ticks between runs; must be at least 1
     * @return the handle, which is the only way to stop it
     */
    Task async(Runnable task, long delayTicks, long periodTicks);
}
