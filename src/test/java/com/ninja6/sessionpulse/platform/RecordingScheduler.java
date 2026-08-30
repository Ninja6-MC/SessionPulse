package com.ninja6.sessionpulse.platform;

import java.util.ArrayList;
import java.util.List;
import org.bukkit.entity.Entity;

/**
 * A {@link Scheduler} that records rather than schedules, and runs what it is given only
 * when a test asks it to.
 *
 * <p>It proves little on its own today. It is written here, with the seam, because every
 * issue from the session tracker onward schedules through {@code Scheduler} and will need
 * exactly this fixture - and because a seam whose test double is awkward to write is
 * usually a seam that is shaped wrong. This one took two lists, which is the answer the
 * exercise was looking for.
 */
final class RecordingScheduler implements Scheduler {

    /** One scheduled repeating task, and whether it is still live. */
    static final class Recorded implements Task {

        final Runnable body;
        final long delayTicks;
        final long periodTicks;
        final boolean async;
        int runs;
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

    final List<Recorded> scheduled = new ArrayList<>();
    final List<Entity> entityTargets = new ArrayList<>();

    @Override
    public Task globalRepeating(Runnable task, long delayTicks, long periodTicks) {
        Recorded recorded = new Recorded(task, delayTicks, periodTicks, false);
        scheduled.add(recorded);
        return recorded;
    }

    @Override
    public void entity(Entity entity, Runnable task, Runnable retired) {
        entityTargets.add(entity);
        task.run();
    }

    @Override
    public Task async(Runnable task, long delayTicks, long periodTicks) {
        Recorded recorded = new Recorded(task, delayTicks, periodTicks, true);
        scheduled.add(recorded);
        return recorded;
    }

    /** Runs one tick: every task that has not been cancelled fires once. */
    void tick() {
        for (Recorded recorded : scheduled) {
            if (!recorded.isCancelled()) {
                recorded.runs++;
                recorded.body.run();
            }
        }
    }
}
