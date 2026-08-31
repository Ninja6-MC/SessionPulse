package com.ninja6.sessionpulse.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ninja6.sessionpulse.config.PluginConfig;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The clock pauses while a player is away, and resumes with nothing back-dated.
 *
 * <p>The rule under test is one sentence: a tick's interval is credited if and only if the
 * player is not AFK at the moment that tick runs. Everything below is that rule at one of
 * its edges.
 */
class AfkAccrualTest {

    private final TestClock clock = new TestClock();
    private final RecordingSessionStore store = new RecordingSessionStore();
    private final PluginConfig config = TestConfigs.defaults();
    private final SessionTracker tracker = new SessionTracker(() -> config, clock, store);
    private final UUID uuid = UUID.randomUUID();

    /** Advances time and runs one tick with the given verdict. */
    private void tick(Duration elapsed, boolean afk) {
        clock.advance(elapsed);
        tracker.accrue(uuid, afk);
    }

    @Test
    @DisplayName("time spent AFK does not accrue")
    void timeSpentAfkDoesNotAccrue() {
        PlayerSession session = tracker.onJoin(uuid, "Ada");
        tick(Duration.ofSeconds(60), false);

        for (int i = 0; i < 5; i++) {
            tick(Duration.ofSeconds(60), true);
        }

        assertEquals(60L, session.windowSeconds(),
                "five minutes of standing still adds nothing to the counted window");
    }

    @Test
    @DisplayName("accrual resumes when the gate reports them back, with no back-dating")
    void accrualResumesWhenTheGateReportsThemBack() {
        PlayerSession session = tracker.onJoin(uuid, "Ada");
        tick(Duration.ofSeconds(60), false);
        for (int i = 0; i < 5; i++) {
            tick(Duration.ofSeconds(60), true);
        }

        tick(Duration.ofSeconds(30), false);

        assertEquals(90L, session.windowSeconds(),
                "60 before the pause plus 30 after it. The five minute gap appears nowhere, "
                        + "which is only true because the accrual mark advanced on every AFK "
                        + "tick and left nothing for the resume to claim.");
    }

    @Test
    @DisplayName("going AFK records the monotonic moment it started, and keeps it")
    void goingAfkRecordsTheMomentItStarted() {
        PlayerSession session = tracker.onJoin(uuid, "Ada");
        tick(Duration.ofSeconds(60), false);

        clock.advance(Duration.ofSeconds(1));
        long wentAfkAt = clock.nanoTime();
        tracker.accrue(uuid, true);

        assertTrue(session.isAfk());
        assertEquals(wentAfkAt, session.afkSinceNanos(),
                "the reading taken by the tick that first saw them away");

        tick(Duration.ofSeconds(120), true);
        assertEquals(wentAfkAt, session.afkSinceNanos(),
                "and it must not creep forward on every subsequent AFK tick - the whole "
                        + "point of the value is how long they have been away");
    }

    @Test
    @DisplayName("coming back clears the AFK mark")
    void comingBackClearsAfkSince() {
        PlayerSession session = tracker.onJoin(uuid, "Ada");
        tick(Duration.ofSeconds(60), true);
        assertTrue(session.isAfk());

        tick(Duration.ofSeconds(1), false);

        assertFalse(session.isAfk());
        assertEquals(PlayerSession.NOT_AFK, session.afkSinceNanos());
    }

    @Test
    @DisplayName("the tick that first sees them AFK discards its own interval")
    void theTickThatFirstSeesAfkDiscardsItsOwnDelta() {
        PlayerSession session = tracker.onJoin(uuid, "Ada");
        tick(Duration.ofSeconds(60), false);

        tick(Duration.ofSeconds(1), true);

        assertEquals(60L, session.windowSeconds(),
                "quantisation at the pause edge: up to one tick period the player was "
                        + "arguably still active for is lost, and that is the pinned choice");
    }

    @Test
    @DisplayName("the tick that sees them return credits its own interval")
    void theTickThatSeesThemReturnCreditsItsOwnDelta() {
        PlayerSession session = tracker.onJoin(uuid, "Ada");
        tick(Duration.ofSeconds(60), false);
        tick(Duration.ofSeconds(300), true);

        tick(Duration.ofSeconds(1), false);

        assertEquals(61L, session.windowSeconds(),
                "quantisation at the resume edge: exactly one period, never the 300 seconds "
                        + "they were away. The two edges err in opposite directions.");
    }

    @Test
    @DisplayName("AFK time is excluded from lifetime exactly as it is from the window")
    void afkTimeIsExcludedFromLifetimeToo() {
        PlayerSession session = tracker.onJoin(uuid, "Ada");
        tick(Duration.ofSeconds(60), false);
        tick(Duration.ofSeconds(600), true);
        tick(Duration.ofSeconds(30), false);

        assertEquals(90L, session.windowSeconds());
        assertEquals(90L, session.lifetimeSeconds(),
                "both figures read the same accumulator, so the two numbers /spulse time "
                        + "prints can never disagree about what playing means");
    }

    @Test
    @DisplayName("ten pause and resume cycles accrue exactly the active time, with no drift")
    void repeatedPauseAndResumeDoesNotDrift() {
        PlayerSession session = tracker.onJoin(uuid, "Ada");

        for (int cycle = 0; cycle < 10; cycle++) {
            tick(Duration.ofSeconds(7), false);
            tick(Duration.ofSeconds(13), true);
        }

        assertEquals(70L, session.windowSeconds(),
                "ten cycles of seven active seconds. Exactly, not approximately: the "
                        + "division happens once at the accessor, so nothing rounds per cycle");
    }
}
