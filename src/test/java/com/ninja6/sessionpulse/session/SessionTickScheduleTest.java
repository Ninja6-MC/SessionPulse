package com.ninja6.sessionpulse.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ninja6.sessionpulse.platform.RecordingScheduler;
import com.ninja6.sessionpulse.platform.Scheduler;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.bukkit.entity.Entity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The session tick's schedule across a reload: replaced by handle, at the period read at that
 * moment, and without losing a second of counted time.
 *
 * <p>The body is a lambda calling {@link SessionTracker#accrue} rather than a
 * {@link SessionTickTask}, which resolves players through static {@code Bukkit}. What these
 * tests claim is about the schedule and the clock, and the accrual is the part of the tick
 * that touches the clock.
 */
class SessionTickScheduleTest {

    private final TestClock clock = new TestClock();
    private final RecordingSessionStore store = new RecordingSessionStore();
    private final SessionTracker tracker =
            new SessionTracker(TestConfigs::defaults, clock, store);
    private final UUID uuid = UUID.randomUUID();
    private final RecordingScheduler scheduler = new RecordingScheduler();
    private final AtomicLong period = new AtomicLong(20L);
    private final SessionTickSchedule schedule = new SessionTickSchedule(scheduler,
            () -> tracker.accrue(uuid, false), SessionTickTask.DELAY_TICKS, period::get);

    @Test
    @DisplayName("start schedules one global task at the supplier's period")
    void startSchedulesOneGlobalTask() {
        schedule.start();
        schedule.start();

        assertEquals(1, scheduler.scheduled.size(), "a second start must not add a task");
        RecordingScheduler.Recorded task = scheduler.scheduled.get(0);
        assertFalse(task.async, "the tick runs on the global region, not async");
        assertEquals(SessionTickTask.DELAY_TICKS, task.delayTicks);
        assertEquals(20L, task.periodTicks);
        assertSame(task, schedule.task());
    }

    @Test
    @DisplayName("reschedule schedules at the new period and cancels the old handle")
    void rescheduleUsesTheNewPeriodAndCancelsTheOld() {
        schedule.start();
        RecordingScheduler.Recorded old = scheduler.scheduled.get(0);

        period.set(40L);
        schedule.reschedule();

        assertEquals(2, scheduler.scheduled.size());
        RecordingScheduler.Recorded next = scheduler.scheduled.get(1);
        assertEquals(40L, next.periodTicks, "the period is read at the reschedule, not captured");
        assertTrue(old.isCancelled(), "the old tick would keep running beside the new one");
        assertFalse(next.isCancelled());
        assertSame(next, schedule.task());
    }

    @Test
    @DisplayName("the counted window keeps every second across a reschedule")
    void theClockKeepsCountingAcrossAReschedule() {
        tracker.onJoin(uuid, "Ada");
        schedule.start();
        RecordingScheduler.Recorded old = scheduler.scheduled.get(0);

        clock.advance(Duration.ofSeconds(30));
        scheduler.tick();
        clock.advance(Duration.ofSeconds(30));
        schedule.reschedule();
        clock.advance(Duration.ofSeconds(30));
        scheduler.tick();

        assertEquals(1, old.runs, "a cancelled tick ran after the reschedule");
        assertEquals(1, scheduler.scheduled.get(1).runs);
        assertEquals(90L, tracker.session(uuid).windowSeconds(),
                "the gap around the reschedule is credited on the new task's first run");
    }

    @Test
    @DisplayName("a schedule that throws leaves the old tick counting")
    void aFailingScheduleLeavesTheOldTickLive() {
        boolean[] refuse = {false};
        Scheduler refusing = new Scheduler() {
            @Override
            public Task globalRepeating(Runnable task, long delayTicks, long periodTicks) {
                if (refuse[0]) {
                    throw new IllegalStateException("refused");
                }
                return scheduler.globalRepeating(task, delayTicks, periodTicks);
            }

            @Override
            public void entity(Entity entity, Runnable task, Runnable retired) {
                scheduler.entity(entity, task, retired);
            }

            @Override
            public Task async(Runnable task, long delayTicks, long periodTicks) {
                return scheduler.async(task, delayTicks, periodTicks);
            }

            @Override
            public void asyncOnce(Runnable task) {
                scheduler.asyncOnce(task);
            }
        };
        SessionTickSchedule guarded = new SessionTickSchedule(refusing,
                () -> tracker.accrue(uuid, false), SessionTickTask.DELAY_TICKS, period::get);
        guarded.start();
        RecordingScheduler.Recorded old = scheduler.scheduled.get(0);

        refuse[0] = true;
        assertThrows(IllegalStateException.class, guarded::reschedule);

        assertFalse(old.isCancelled(), "the plugin would have stopped counting");
        assertSame(old, guarded.task());
    }

    @Test
    @DisplayName("retire cancels the tick, and a reschedule after it schedules nothing")
    void rescheduleAfterRetireIsANoOp() {
        schedule.start();
        RecordingScheduler.Recorded old = scheduler.scheduled.get(0);

        schedule.retire();
        schedule.reschedule();
        schedule.start();

        assertTrue(old.isCancelled());
        assertEquals(1, scheduler.scheduled.size(), "a reload racing disable scheduled a tick");
        assertNull(schedule.task());
    }
}
