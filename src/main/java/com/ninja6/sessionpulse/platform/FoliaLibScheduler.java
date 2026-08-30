package com.ninja6.sessionpulse.platform;

import com.tcoded.folialib.FoliaLib;
import com.tcoded.folialib.impl.PlatformScheduler;
import com.tcoded.folialib.wrapper.task.WrappedTask;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;

/**
 * The only class in this plugin permitted to name FoliaLib.
 *
 * <p>Everything else takes {@link Scheduler}. Keeping the import list of this one file as
 * the whole surface is what makes swapping the library - or dropping it, if Paper and
 * Folia ever converge - a single-file change. A unit test scans {@code src/main/java} and
 * fails if any other file names the library's package.
 *
 * <p>{@link #cancelAll()} lives here and <strong>not</strong> on {@link Scheduler}, which
 * is a deliberate departure from the four-method list in the issue. Only
 * {@code SessionPulsePlugin} holds this type concretely; every other collaborator is
 * handed the interface, so the blanket cancel is simply unreachable from the places that
 * must not use it. See the note on {@link Scheduler} for the failure that shape prevents.
 */
public final class FoliaLibScheduler implements Scheduler {

    private final FoliaLib foliaLib;
    private final PlatformScheduler scheduler;

    /**
     * Creates the scheduler and lets FoliaLib select its platform implementation.
     *
     * @param plugin the owning plugin, which FoliaLib uses for task ownership
     */
    public FoliaLibScheduler(Plugin plugin) {
        this.foliaLib = new FoliaLib(plugin);
        this.scheduler = foliaLib.getScheduler();
    }

    /**
     * Which implementation FoliaLib selected, for the enable log line.
     *
     * <p>Read on the way past, not branched on. Nothing in this plugin is allowed to ask
     * the platform what it is and behave differently - that is what the seam exists to
     * make unnecessary - which is why {@code SessionPulsePlugin#scheduler()} hands out
     * {@link Scheduler} and this method is not on it.
     *
     * @return the implementation name, e.g. {@code FOLIA} or {@code SPIGOT}
     */
    public String platformName() {
        return foliaLib.getImplType().name();
    }

    @Override
    public Task globalRepeating(Runnable task, long delayTicks, long periodTicks) {
        return new WrappedTaskHandle(scheduler.runTimer(task, delayTicks, periodTicks));
    }

    @Override
    public void entity(Entity entity, Runnable task, Runnable retired) {
        // runAtEntityWithFallback, not runAtEntity: the fallback is what handles the entity
        // being gone by the time the region thread gets to it, which on Folia is an
        // ordinary outcome rather than an error. On Spigot FoliaLib resolves this to the
        // main thread and reports SUCCESS or ENTITY_RETIRED the same way.
        scheduler.runAtEntityWithFallback(entity, wrapped -> task.run(), retired);
    }

    @Override
    public Task async(Runnable task, long delayTicks, long periodTicks) {
        return new WrappedTaskHandle(scheduler.runTimerAsync(task, delayTicks, periodTicks));
    }

    /**
     * Cancels every task this scheduler has scheduled.
     *
     * <p><strong>Call this from {@code onDisable} and from nowhere else.</strong> It is
     * not on {@link Scheduler} precisely so that "nowhere else" is enforced by the
     * compiler: a collaborator holding the interface cannot reach it at all.
     *
     * <p>{@code /spulse reload} must cancel and reschedule the flush and the session tick
     * individually, through the {@link Task} handles it holds. Calling this on reload
     * kills the session tick as well, the plugin stops counting, and nothing in the log
     * says so - the command reports success and the feature is simply gone. That failure
     * is silent and survives a restart-free session, which is why the method is placed
     * out of reach rather than merely documented.
     */
    public void cancelAll() {
        scheduler.cancelAllTasks();
    }

    /** Adapts the library's task handle to {@link Task} so the seam stays leak-free. */
    private record WrappedTaskHandle(WrappedTask delegate) implements Task {

        @Override
        public void cancel() {
            delegate.cancel();
        }

        @Override
        public boolean isCancelled() {
            return delegate.isCancelled();
        }
    }
}
