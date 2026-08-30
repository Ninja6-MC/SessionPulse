package com.ninja6.sessionpulse.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The behaviour the seam's shape exists to buy: cancelling one task leaves the others
 * running.
 *
 * <p>This is the reload path, expressed against a double. It is the reason handles are the
 * only cancellation route the interface offers, and the reason the blanket cancel is not
 * on it.
 */
class TaskHandleTest {

    @Test
    @DisplayName("cancelling one handle leaves the other tasks running")
    void cancellingOneTaskLeavesTheOthersLive() {
        RecordingScheduler scheduler = new RecordingScheduler();

        Scheduler.Task tick = scheduler.globalRepeating(() -> { }, 20L, 20L);
        Scheduler.Task flush = scheduler.async(() -> { }, 100L, 6000L);

        scheduler.tick();
        scheduler.tick();

        // The reload path: the flush interval is reconfigured, so its handle is cancelled
        // and a replacement scheduled. The session tick must not notice.
        flush.cancel();
        Scheduler.Task replacement = scheduler.async(() -> { }, 100L, 1200L);

        scheduler.tick();

        assertTrue(flush.isCancelled(), "The handle that was cancelled reports it.");
        assertFalse(tick.isCancelled(), "Cancelling the flush must not touch the session tick.");
        assertFalse(replacement.isCancelled(), "The replacement is live.");

        assertEquals(3, ((RecordingScheduler.Recorded) tick).runs,
            "The session tick kept counting across the reload.");
        assertEquals(2, ((RecordingScheduler.Recorded) flush).runs,
            "The cancelled flush stopped when it was cancelled.");
        assertEquals(1, ((RecordingScheduler.Recorded) replacement).runs,
            "The replacement flush started at the tick after it was scheduled.");
    }

    @Test
    @DisplayName("the seam carries delay, period and asyncness through to the implementation")
    void seamCarriesDelayAndPeriod() {
        RecordingScheduler scheduler = new RecordingScheduler();
        scheduler.globalRepeating(() -> { }, 20L, 20L);
        scheduler.async(() -> { }, 100L, 6000L);

        assertEquals(2, scheduler.scheduled.size());
        assertFalse(scheduler.scheduled.get(0).async, "globalRepeating is not async.");
        assertTrue(scheduler.scheduled.get(1).async, "async is.");
        assertEquals(20L, scheduler.scheduled.get(0).delayTicks);
        assertEquals(6000L, scheduler.scheduled.get(1).periodTicks);
    }

    @Test
    @DisplayName("entity work is handed the entity it was scheduled against")
    void entityWorkRunsAgainstItsEntity() {
        RecordingScheduler scheduler = new RecordingScheduler();
        boolean[] ran = {false};

        // A null entity is acceptable to the double and is what a unit test can offer: a
        // real Entity needs a server. What is under test here is the seam's contract - the
        // work runs, and the retired branch does not - not the library's dispatch.
        scheduler.entity(null, () -> ran[0] = true, () -> {
            throw new AssertionError("The retired branch must not run when the work does.");
        });

        assertTrue(ran[0], "The scheduled work ran.");
        assertEquals(1, scheduler.entityTargets.size());
    }
}
