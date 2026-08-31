package com.ninja6.sessionpulse.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ninja6.sessionpulse.config.PluginConfig;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The counted window rolls over after an offline gap, and at no other time.
 *
 * <p>The gap is simulated with {@code TestClock.advanceWallOnly}, which moves the calendar
 * without moving the monotonic counter - a genuine model of "the player was not here",
 * and the only way to make the discrimination in
 * {@link #theGapIsMeasuredOnTheWallClockAndNotTheMonotonicOne()} meaningful. That test is
 * the one that fails if the reset decision ever reaches for a monotonic reading, which is
 * the subtlest way this feature could ship broken.
 */
class WindowResetTest {

    /** The default from config.yml, and what the tests below are written against. */
    private static final Duration THRESHOLD = Duration.ofHours(8);

    private final TestClock clock = new TestClock();
    private final RecordingSessionStore store = new RecordingSessionStore();
    private PluginConfig config = TestConfigs.defaults();
    private final SessionTracker tracker = new SessionTracker(() -> config, clock, store);
    private final UUID uuid = UUID.randomUUID();

    /** The instant a preloaded record says its window began: twenty minutes before now. */
    private final long windowStart = TestClock.START_WALL_MILLIS - Duration.ofMinutes(20).toMillis();

    /** A stored record for a player last seen right now, mid-window. */
    private void preloadSeenNow(long windowSeconds, long lifetimeSeconds) {
        store.preload(uuid, new SessionSnapshot("Ada", lifetimeSeconds, windowStart,
                windowSeconds, clock.wallMillis()));
    }

    @Test
    @DisplayName("an offline gap shorter than the threshold keeps the window")
    void anOfflineGapShorterThanTheThresholdKeepsTheWindow() {
        preloadSeenNow(600L, 600L);

        clock.advanceWallOnly(THRESHOLD.minusMinutes(1));
        PlayerSession session = tracker.onJoin(uuid, "Ada");

        assertEquals(600L, session.windowSeconds(), "seven hours fifty-nine keeps the window");
        assertEquals(windowStart, session.windowStartMillis(),
                "and the window still begins where it began");
    }

    @Test
    @DisplayName("an offline gap of exactly the threshold does NOT reset the window")
    void anOfflineGapAtExactlyTheThresholdDoesNotResetTheWindow() {
        preloadSeenNow(600L, 600L);

        clock.advanceWallOnly(THRESHOLD);
        PlayerSession session = tracker.onJoin(uuid, "Ada");

        assertEquals(600L, session.windowSeconds(),
                "the window resets once a player has been offline LONGER THAN "
                        + "window-reset-hours. Exactly eight hours is not longer than eight "
                        + "hours, so the comparison is strictly greater-than and the boundary "
                        + "belongs to the preserved side.");
        assertEquals(windowStart, session.windowStartMillis());
    }

    @Test
    @DisplayName("an offline gap longer than the threshold resets the window")
    void anOfflineGapLongerThanTheThresholdResetsTheWindow() {
        preloadSeenNow(600L, 600L);

        clock.advanceWallOnly(Duration.ofHours(9));
        PlayerSession session = tracker.onJoin(uuid, "Ada");

        assertEquals(0L, session.windowSeconds());
        assertEquals(clock.wallMillis(), session.windowStartMillis(),
                "a new window begins the moment they came back");
    }

    @Test
    @DisplayName("a window reset preserves lifetime")
    void aWindowResetPreservesLifetime() {
        preloadSeenNow(600L, 86_400L);

        clock.advanceWallOnly(Duration.ofHours(9));
        PlayerSession session = tracker.onJoin(uuid, "Ada");

        assertEquals(0L, session.windowSeconds());
        assertEquals(86_400L, session.lifetimeSeconds(),
                "a day of recorded play is not erased by a night off");
    }

    @Test
    @DisplayName("a window reset clears the fired milestones so the new window fires again")
    void aWindowResetClearsTheFiredMilestones() {
        preloadSeenNow(7200L, 7200L);

        clock.advanceWallOnly(Duration.ofHours(9));
        PlayerSession session = tracker.onJoin(uuid, "Ada");

        assertTrue(session.firedMinutes().isEmpty(),
                "a two hour window would have seeded minutes 60 and 120 had it survived");
    }

    @Test
    @DisplayName("the reset threshold is read from the config supplier on every join")
    void theResetThresholdIsReadFromTheConfigSupplierPerJoin() {
        preloadSeenNow(600L, 600L);

        clock.advanceWallOnly(Duration.ofHours(2));
        assertEquals(600L, tracker.onJoin(uuid, "Ada").windowSeconds(),
                "two hours is well inside the default eight");
        tracker.onQuit(uuid);

        config = TestConfigs.withWindowResetHours(1);

        clock.advanceWallOnly(Duration.ofHours(2));
        assertEquals(0L, tracker.onJoin(uuid, "Ada").windowSeconds(),
                "the same gap now resets, because the tracker holds the supplier and not a "
                        + "captured PluginConfig. One that captured the object would keep "
                        + "running the previous file's settings for ever after a reload.");
    }

    @Test
    @DisplayName("a backwards system clock never grants a fresh window")
    void aBackwardsSystemClockNeverGrantsAFreshWindow() {
        // The record says they were last seen ten hours from now, which is what an operator
        // winding the clock back produces.
        store.preload(uuid, new SessionSnapshot("Ada", 600L, windowStart, 600L,
                clock.wallMillis() + Duration.ofHours(10).toMillis()));

        PlayerSession session = tracker.onJoin(uuid, "Ada");

        assertEquals(600L, session.windowSeconds(),
                "a negative gap is not a long gap. A backwards clock can only ever delay a "
                        + "reset, never grant one, which is the safe direction to fail in.");
    }

    @Test
    @DisplayName("a player with no stored record starts a fresh window")
    void aPlayerWithNoStoredRecordStartsAFreshWindow() {
        assertTrue(store.load(uuid).isUnknown(), "precondition: storage has never seen them");

        PlayerSession session = tracker.onJoin(uuid, "Ada");

        assertEquals(0L, session.windowSeconds());
        assertEquals(clock.wallMillis(), session.windowStartMillis());
    }

    @Test
    @DisplayName("the window never resets while the player stays online")
    void theWindowNeverResetsWhileThePlayerStaysOnline() {
        PlayerSession session = tracker.onJoin(uuid, "Ada");
        long began = session.windowStartMillis();

        for (int hour = 0; hour < 12; hour++) {
            clock.advance(Duration.ofHours(1));
            tracker.accrue(uuid, false);
        }

        assertEquals(43_200L, session.windowSeconds(),
                "twelve hours in one sitting is one window that keeps growing");
        assertEquals(began, session.windowStartMillis(),
                "a rollover is decided at join and nowhere else. Enforcement ends a session "
                        + "like this; the window does not quietly restart underneath it.");
    }

    @Test
    @DisplayName("the offline gap is measured on the calendar, not on the monotonic clock")
    void theGapIsMeasuredOnTheWallClockAndNotTheMonotonicOne() {
        UUID other = UUID.randomUUID();
        preloadSeenNow(600L, 600L);
        store.preload(other, new SessionSnapshot("Grace", 600L, windowStart, 600L,
                clock.wallMillis()));

        // Nine hours of elapsed time with the calendar standing still. Nobody was offline.
        clock.advanceNanosOnly(Duration.ofHours(9));
        assertEquals(600L, tracker.onJoin(other, "Grace").windowSeconds(),
                "a monotonic reading says nothing about how long somebody was away, and a "
                        + "reset decision that consulted one would fire here");

        // Nine hours on the calendar with no elapsed time in this JVM. That is an offline
        // gap, and it is the only thing that can be measured across a restart.
        clock.advanceWallOnly(Duration.ofHours(9));
        assertEquals(0L, tracker.onJoin(uuid, "Ada").windowSeconds(),
                "and a decision that consulted only the monotonic reading would miss this");
    }
}
