package com.ninja6.sessionpulse.session;

import static com.ninja6.sessionpulse.session.MilestoneClaimTest.milestones;
import static com.ninja6.sessionpulse.session.MilestoneClaimTest.minutesOf;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ninja6.sessionpulse.config.PluginConfig;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * "Once" means once per counted window: a new window re-arms every milestone, and nothing
 * short of one does - not a rejoin, not a restart, not a crash.
 */
class MilestoneWindowTest {

    private final TestClock clock = new TestClock();
    private final RecordingSessionStore store = new RecordingSessionStore();
    private final PluginConfig config = milestones(60);
    private SessionTracker tracker = new SessionTracker(() -> config, clock, store);
    private final UUID uuid = UUID.randomUUID();

    private List<Integer> playAndClaim(Duration played) {
        clock.advance(played);
        return minutesOf(tracker.claimDue(tracker.accrue(uuid, false)));
    }

    private void fireSixty() {
        tracker.onJoin(uuid, "Ada");
        assertEquals(List.of(60), playAndClaim(Duration.ofMinutes(60)));
    }

    @Test
    @DisplayName("an offline gap longer than the threshold re-arms the milestone")
    void windowResetRearms() {
        fireSixty();
        tracker.onQuit(uuid);
        clock.advanceWallOnly(Duration.ofHours(9));

        tracker.onJoin(uuid, "Ada");

        assertEquals(List.of(60), playAndClaim(Duration.ofMinutes(60)),
                "a new counted window fires its milestones again");
    }

    @Test
    @DisplayName("an offline gap of exactly the threshold keeps the milestone fired")
    void gapEqualToThresholdDoesNotRearm() {
        fireSixty();
        tracker.onQuit(uuid);
        clock.advanceWallOnly(Duration.ofHours(8));

        PlayerSession session = tracker.onJoin(uuid, "Ada");

        assertTrue(session.hasFired(60), "exactly eight hours is not longer than eight hours");
        assertEquals(List.of(), playAndClaim(Duration.ofMinutes(1)));
    }

    @Test
    @DisplayName("a restart and rejoin in the same window does not refire")
    void restartRejoinDoesNotRefire() {
        fireSixty();
        tracker.onQuit(uuid);
        tracker = new SessionTracker(() -> config, clock, store);

        PlayerSession session = tracker.onJoin(uuid, "Ada");

        assertTrue(session.hasFired(60), "the stored window is past minute 60");
        assertEquals(List.of(), playAndClaim(Duration.ofMinutes(1)));
    }

    @Test
    @DisplayName("a checkpoint at the claim stops a crash straight afterwards refiring it")
    void checkpointSurvivesCrash() {
        tracker.onJoin(uuid, "Ada");
        clock.advance(Duration.ofSeconds(59 * 60 + 30));
        tracker.accrue(uuid, false);
        store.flush(tracker.snapshotAll());

        clock.advance(Duration.ofSeconds(30));
        PlayerSession session = tracker.accrue(uuid, false);
        assertEquals(List.of(60), minutesOf(tracker.claimDue(session)));
        tracker.checkpoint(session);

        // A crash: no quit, and a new tracker over whatever reached the store.
        clock.advance(Duration.ofSeconds(20));
        tracker = new SessionTracker(() -> config, clock, store);

        assertTrue(store.stored(uuid).windowSeconds() >= 3600L,
                "the checkpoint stored the window the claim was made against");
        assertTrue(tracker.onJoin(uuid, "Ada").hasFired(60),
                "left to the 59:30 flush, the rejoin would fire minute 60 again");
    }

    @Test
    @DisplayName("a session no longer tracked neither claims nor overwrites what its quit stored")
    void detachedSessionClaimsAndCheckpointsNothing() {
        tracker.onJoin(uuid, "Ada");
        clock.advance(Duration.ofMinutes(60));
        PlayerSession held = tracker.accrue(uuid, false);
        SessionSnapshot quit = tracker.onQuit(uuid);
        clock.advance(Duration.ofSeconds(5));

        assertEquals(List.of(), minutesOf(tracker.claimDue(held)),
                "a tick still holding the quit session must not schedule an alert for it");
        assertTrue(held.firedMinutes().isEmpty(), "and must not mark anything fired");
        tracker.checkpoint(held);
        assertEquals(List.of(quit), store.saves, "the quit's save is the last word");
    }

    @Test
    @DisplayName("an administrative window reset re-arms the milestone")
    void adminResetRearms() {
        fireSixty();

        PlayerSession reset = tracker.resetWindow(uuid);

        assertTrue(reset.firedMinutes().isEmpty(), "a reset window carries no fired set over");
        assertEquals(List.of(60), playAndClaim(Duration.ofMinutes(60)));
    }
}
