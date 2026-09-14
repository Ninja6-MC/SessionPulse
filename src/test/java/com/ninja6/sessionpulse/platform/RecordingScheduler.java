package com.ninja6.sessionpulse.platform;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.bukkit.entity.Entity;

/**
 * A {@link Scheduler} that records rather than schedules, and runs what it is given only
 * when a test asks it to.
 *
 * <p>It is written here, with the seam, because every issue from the session tracker
 * onward schedules through {@code Scheduler} and needs exactly this fixture - and because a
 * seam whose test double is awkward to write is usually a seam that is shaped wrong.
 *
 * <p>{@code public} so the storage tests in their own package can drive it. Test-only
 * visibility, the same reasoning as {@code SourceTree}; no production code is affected.
 */
public final class RecordingScheduler implements Scheduler {

    /** One scheduled repeating task, and whether it is still live. */
    public static final class Recorded implements Task {

        public final Runnable body;
        public final long delayTicks;
        public final long periodTicks;
        public final boolean async;
        public int runs;
        private boolean cancelled;

        Recorded(Runnable body, long delayTicks, long periodTicks, boolean async) {
            this.body = body;
            this.delayTicks = delayTicks;
            this.periodTicks = periodTicks;
            this.async = async;
        }

        @Override
        public void cancel() {
            cancelled = true;
        }

        @Override
        public boolean isCancelled() {
            return cancelled;
        }
    }

    public final List<Recorded> scheduled = new ArrayList<>();
    public final List<Entity> entityTargets = new ArrayList<>();

    /**
     * When {@code true}, {@link #entity} queues instead of running inline, and the queue waits
     * for {@link #runEntity()} or {@link #retireEntity()}. That is how a test puts ticks between
     * a claim and its delivery, which inline execution hides.
     */
    public boolean deferEntity;

    /**
     * {@code true} only while an entity task body is running. A test double for a player reads
     * it to prove a call happened on the region and not on the tick.
     */
    public boolean inEntity;

    /** Entity tasks waiting while {@link #deferEntity} is set, oldest first, as task and retired. */
    private final List<Runnable[]> pendingEntity = new ArrayList<>();

    /**
     * One-shots waiting for {@link #runOnce()}, oldest first.
     *
     * <p>Kept apart from {@link #scheduled}: a one-shot queued from inside a repeating
     * task would otherwise be appended to the list {@link #tick()} is iterating. Synchronized
     * because storage requests a flush from whichever thread wrote, and a concurrency test
     * drives several at once.
     */
    public final List<Runnable> once = Collections.synchronizedList(new ArrayList<>());

    public RecordingScheduler() {
    }

    @Override
    public Task globalRepeating(Runnable task, long delayTicks, long periodTicks) {
        Recorded recorded = new Recorded(task, delayTicks, periodTicks, false);
        scheduled.add(recorded);
        return recorded;
    }

    @Override
    public void entity(Entity entity, Runnable task, Runnable retired) {
        if (retired == null) {
            throw new NullPointerException("retired must not be null; pass () -> {}");
        }
        entityTargets.add(entity);
        if (deferEntity) {
            pendingEntity.add(new Runnable[] {task, retired});
        } else {
            runAsEntity(task);
        }
    }

    @Override
    public Task async(Runnable task, long delayTicks, long periodTicks) {
        Recorded recorded = new Recorded(task, delayTicks, periodTicks, true);
        scheduled.add(recorded);
        return recorded;
    }

    @Override
    public void asyncOnce(Runnable task) {
        once.add(task);
    }

    /** Runs one tick: every task that has not been cancelled fires once. */
    public void tick() {
        for (Recorded recorded : scheduled) {
            if (!recorded.isCancelled()) {
                recorded.runs++;
                recorded.body.run();
            }
        }
    }

    /**
     * Runs every deferred entity task, as though each player were still there.
     *
     * @return how many ran
     */
    public int runEntity() {
        List<Runnable[]> batch = new ArrayList<>(pendingEntity);
        pendingEntity.clear();
        batch.forEach(pair -> runAsEntity(pair[0]));
        return batch.size();
    }

    /**
     * Runs the retired callback of every deferred entity task instead of its body, as though
     * each player had left first.
     *
     * @return how many were retired
     */
    public int retireEntity() {
        List<Runnable[]> batch = new ArrayList<>(pendingEntity);
        pendingEntity.clear();
        batch.forEach(pair -> pair[1].run());
        return batch.size();
    }

    private void runAsEntity(Runnable task) {
        inEntity = true;
        try {
            task.run();
        } finally {
            inEntity = false;
        }
    }

    /**
     * Runs every one-shot queued so far, on the calling thread.
     *
     * <p>A one-shot queued while these run is left queued for the next call, which is what
     * lets a test see that a write landing during a flush asked for a flush of its own.
     *
     * @return how many ran
     */
    public int runOnce() {
        List<Runnable> batch;
        synchronized (once) {
            batch = new ArrayList<>(once);
            once.clear();
        }
        batch.forEach(Runnable::run);
        return batch.size();
    }
}
