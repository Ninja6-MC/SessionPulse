package com.ninja6.sessionpulse.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ninja6.sessionpulse.config.PluginConfig;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The counted window's arithmetic, driven entirely by an injected clock.
 *
 * <p>No server, no mock framework, no {@code Player}. That is not a compromise forced by
 * the test classpath - it is the property the tracker was shaped to have, and it is what
 * makes the accrual identity, the sub-second remainder and the quit tail checkable at all.
 */
class SessionTrackerTest {

    private final TestClock clock = new TestClock();
    private final RecordingSessionStore store = new RecordingSessionStore();
    private PluginConfig config = TestConfigs.defaults();
    private final SessionTracker tracker = new SessionTracker(() -> config, clock, store);
    private final UUID uuid = UUID.randomUUID();

    // -----------------------------------------------------------------------------
    // Join
    // -----------------------------------------------------------------------------

    @Test
    @DisplayName("a player storage has never seen starts an empty window, here and now")
    void joinWithNoStoredRecordStartsAnEmptyWindow() {
        PlayerSession session = tracker.onJoin(uuid, "Ada");

        assertEquals(0L, session.windowSeconds(), "a new player has no counted window");
        assertEquals(0L, session.lifetimeSeconds(), "a new player has no lifetime");
        assertEquals(clock.wallMillis(), session.windowStartMillis(),
                "the window begins at the calendar time they joined");
        assertEquals(clock.wallMillis(), session.sessionStartMillis(),
                "and so does the session");
        assertTrue(session.firedMinutes().isEmpty(), "nothing has fired yet");
    }

    @Test
    @DisplayName("joining a player who is already tracked returns their session untouched")
    void joiningAPlayerWhoIsAlreadyTrackedIsANoOp() {
        PlayerSession first = tracker.onJoin(uuid, "Ada");
        clock.advance(Duration.ofSeconds(30));
        tracker.accrue(uuid, false);

        PlayerSession again = tracker.onJoin(uuid, "Ada");

        assertSame(first, again,
                "a second join must not replace the live session. The plugin seeds itself "
                        + "from the already-online list AND listens for joins, and a hard "
                        + "client drop whose quit never arrived is followed by a join for a "
                        + "player still in the map.");
        assertEquals(30L, again.windowSeconds(),
                "re-joining must not throw away time already accrued");
    }

    @Test
    @DisplayName("a stored window inside the reset threshold is resumed, not restarted")
    void joinResumesThePersistedWindow() {
        long windowStart = clock.wallMillis() - Duration.ofMinutes(20).toMillis();
        store.preload(uuid, new SessionSnapshot("Ada", 600L, windowStart, 600L,
                clock.wallMillis()));

        clock.advanceWallOnly(Duration.ofHours(1));
        PlayerSession session = tracker.onJoin(uuid, "Ada");

        assertEquals(600L, session.windowSeconds(), "the stored window is carried over");
        assertEquals(windowStart, session.windowStartMillis(),
                "and so is the instant it began");

        clock.advance(Duration.ofSeconds(1));
        tracker.accrue(uuid, false);
        assertEquals(601L, session.windowSeconds(), "new time adds to the stored base");
    }

    @Test
    @DisplayName("lifetime is carried across a window reset, and only the window is zeroed")
    void lifetimeSurvivesAWindowReset() {
        store.preload(uuid, new SessionSnapshot("Ada", 7200L, clock.wallMillis(), 600L,
                clock.wallMillis()));

        clock.advanceWallOnly(Duration.ofHours(9));
        PlayerSession session = tracker.onJoin(uuid, "Ada");

        assertEquals(0L, session.windowSeconds(), "nine hours away resets the window");
        assertEquals(7200L, session.lifetimeSeconds(), "lifetime is never reset");

        clock.advance(Duration.ofSeconds(1));
        tracker.accrue(uuid, false);
        assertEquals(1L, session.windowSeconds(), "the fresh window counts from zero");
        assertEquals(7201L, session.lifetimeSeconds(), "lifetime keeps going");
    }

    // -----------------------------------------------------------------------------
    // Accrual
    // -----------------------------------------------------------------------------

    @Test
    @DisplayName("one second of activity accrues one second, in the window and in lifetime")
    void oneSecondOfActivityAccruesOneSecond() {
        PlayerSession session = tracker.onJoin(uuid, "Ada");

        clock.advance(Duration.ofSeconds(1));
        tracker.accrue(uuid, false);

        assertEquals(1L, session.windowSeconds());
        assertEquals(1L, session.lifetimeSeconds());
        assertEquals(1L, session.sessionSeconds());
    }

    @Test
    @DisplayName("part of a second accrues nothing until it completes, and then all of it")
    void partOfASecondAccruesNothingUntilItCompletes() {
        PlayerSession session = tracker.onJoin(uuid, "Ada");

        clock.advance(Duration.ofMillis(999));
        tracker.accrue(uuid, false);
        assertEquals(0L, session.windowSeconds(), "999ms is not a second");

        clock.advance(Duration.ofMillis(1));
        tracker.accrue(uuid, false);
        assertEquals(1L, session.windowSeconds(),
                "the remainder was kept, not discarded by the first tick");
    }

    @Test
    @DisplayName("a thousand one-millisecond ticks accrue exactly one second, not zero")
    void aThousandTicksOfAMillisecondEachAccrueExactlyOneSecond() {
        PlayerSession session = tracker.onJoin(uuid, "Ada");

        for (int i = 0; i < 1000; i++) {
            clock.advance(Duration.ofMillis(1));
            tracker.accrue(uuid, false);
        }

        assertEquals(1L, session.windowSeconds(),
                "the nanosecond accumulator is divided once, at the accessor. An "
                        + "implementation that truncated per tick would report zero here.");
    }

    @Test
    @DisplayName("accrual is interval-based, so a tick the server missed loses nothing")
    void accrualIsDeltaBasedSoALaggingTickLosesNothing() {
        PlayerSession session = tracker.onJoin(uuid, "Ada");

        clock.advance(Duration.ofSeconds(10));
        tracker.accrue(uuid, false);

        assertEquals(10L, session.windowSeconds(),
                "one tick after a ten second stall credits ten seconds, not one. An "
                        + "accumulator that added the tick period would have lost nine.");
    }

    @Test
    @DisplayName("accruing for a player who is not tracked does nothing and does not throw")
    void accrueForAnUnknownPlayerIsANoOp() {
        assertNull(tracker.accrue(UUID.randomUUID(), false));
        assertNull(tracker.session(UUID.randomUUID()));
    }

    // -----------------------------------------------------------------------------
    // Quit
    // -----------------------------------------------------------------------------

    @Test
    @DisplayName("quit returns a snapshot in calendar milliseconds and whole seconds")
    void quitReturnsAWallClockSnapshotInWholeSeconds() {
        long windowStart = clock.wallMillis() - Duration.ofMinutes(10).toMillis();
        store.preload(uuid, new SessionSnapshot("Ada", 7200L, windowStart, 600L,
                clock.wallMillis()));
        clock.advanceWallOnly(Duration.ofHours(1));

        tracker.onJoin(uuid, "Ada");
        clock.advance(Duration.ofSeconds(90));
        tracker.accrue(uuid, false);

        SessionSnapshot snapshot = tracker.onQuit(uuid);

        assertNotNull(snapshot);
        assertEquals("Ada", snapshot.name());
        assertEquals(clock.wallMillis(), snapshot.lastSeenMillis(),
                "last-seen is the calendar time, taken now");
        assertEquals(windowStart, snapshot.windowStartMillis(),
                "the window start is carried through untouched");
        assertEquals(690L, snapshot.windowSeconds(), "600 banked plus 90 played");
        assertEquals(7290L, snapshot.lifetimeSeconds(), "7200 banked plus 90 played");
        assertFalse(snapshot.isUnknown(), "a stamped record is not an unknown one");
    }

    @Test
    @DisplayName("quit credits the tail of the session exactly once")
    void quitCreditsTheTailOfTheSessionExactlyOnce() {
        tracker.onJoin(uuid, "Ada");
        clock.advance(Duration.ofSeconds(10));
        tracker.accrue(uuid, false);

        clock.advance(Duration.ofSeconds(5));
        SessionSnapshot snapshot = tracker.onQuit(uuid);

        assertEquals(15L, snapshot.windowSeconds(),
                "the five seconds since the last tick are credited on the way out");
        assertNull(tracker.accrue(uuid, false),
                "and the session is gone, so a tick still in flight finds nothing");
        assertEquals(15L, store.stored(uuid).windowSeconds(),
                "which means the stored figure cannot grow after the quit");
    }

    @Test
    @DisplayName("a player who quits while AFK contributes no tail")
    void quittingWhileAfkCreditsNoTail() {
        tracker.onJoin(uuid, "Ada");
        clock.advance(Duration.ofSeconds(10));
        tracker.accrue(uuid, false);

        clock.advance(Duration.ofSeconds(1));
        tracker.accrue(uuid, true);
        clock.advance(Duration.ofSeconds(60));

        SessionSnapshot snapshot = tracker.onQuit(uuid);

        assertEquals(10L, snapshot.windowSeconds(),
                "a minute of standing still before disconnecting is not play time");
    }

    @Test
    @DisplayName("quitting twice is harmless")
    void quittingTwiceIsHarmless() {
        tracker.onJoin(uuid, "Ada");
        assertNotNull(tracker.onQuit(uuid));
        assertNull(tracker.onQuit(uuid), "the second quit has nothing to finalise");
        assertEquals(1, store.saves.size(), "and must not write a second record");
    }

    @Test
    @DisplayName("the tracker saves through the store it was given, not through its caller")
    void onQuitSavesThroughTheTrackersOwnStore() {
        tracker.onJoin(uuid, "Ada");
        clock.advance(Duration.ofSeconds(42));
        tracker.accrue(uuid, false);

        tracker.onQuit(uuid);

        assertEquals(1, store.saves.size(),
                "the store is wired in one place, so swapping in a real implementation "
                        + "cannot land on the load path and miss the save path");
        assertEquals(42L, store.stored(uuid).windowSeconds());
    }

    // -----------------------------------------------------------------------------
    // The whole population
    // -----------------------------------------------------------------------------

    @Test
    @DisplayName("snapshotAll reports every online player and only those")
    void snapshotAllReportsEveryOnlinePlayerAndOnlyThose() {
        UUID second = UUID.randomUUID();
        UUID third = UUID.randomUUID();
        tracker.onJoin(uuid, "Ada");
        tracker.onJoin(second, "Grace");
        tracker.onJoin(third, "Alan");
        tracker.onQuit(second);

        Map<UUID, SessionSnapshot> all = tracker.snapshotAll();

        assertEquals(2, all.size());
        assertEquals(Set.of(uuid, third), all.keySet());
        assertEquals(2, tracker.sessions().size());
    }

    @Test
    @DisplayName("the flush stamps a fresh last-seen for players who are still online")
    void theFlushStampsAFreshLastSeenForOnlinePlayers() {
        tracker.onJoin(uuid, "Ada");
        long windowStart = tracker.session(uuid).windowStartMillis();

        // Ten hours online, flushed every half hour, exactly as the flush task will.
        for (int i = 0; i < 20; i++) {
            clock.advance(Duration.ofMinutes(30));
            tracker.accrue(uuid, false);
            store.flush(tracker.snapshotAll());
        }
        assertEquals(36_000L, store.stored(uuid).windowSeconds(), "ten hours were banked");

        // The crash: the process dies, so no quit event and no final save. Everything the
        // next run knows is what the last flush wrote.
        SessionTracker afterCrash = new SessionTracker(() -> config, clock, store);
        clock.advanceWallOnly(Duration.ofMinutes(5));
        PlayerSession resumed = afterCrash.onJoin(uuid, "Ada");

        assertEquals(36_000L, resumed.windowSeconds(),
                "The window must survive a crash. If the flush did not refresh last-seen, "
                        + "this player's stored stamp would be ten hours old, the computed "
                        + "offline gap would exceed window-reset-hours, and they would be "
                        + "handed a brand new counted window - the exact bypass the counted "
                        + "window exists to prevent, arriving through a crash.");
        assertEquals(windowStart, resumed.windowStartMillis(),
                "and it must be the same window, not a new one that happens to be as long");
    }

    // -----------------------------------------------------------------------------
    // Administrative reset and milestone seeding
    // -----------------------------------------------------------------------------

    @Test
    @DisplayName("resetWindow clears the window and the fired set, but never lifetime")
    void resetWindowClearsTheWindowAndTheFiredSetButNotLifetime() {
        store.preload(uuid, new SessionSnapshot("Ada", 7200L, clock.wallMillis() - 1000L,
                3600L, clock.wallMillis()));
        clock.advanceWallOnly(Duration.ofHours(1));
        PlayerSession before = tracker.onJoin(uuid, "Ada");
        assertTrue(before.hasFired(60), "an hour of window means minute 60 already passed");

        PlayerSession after = tracker.resetWindow(uuid);

        assertNotNull(after);
        assertEquals(0L, after.windowSeconds(), "the window starts again from nothing");
        assertEquals(clock.wallMillis(), after.windowStartMillis(), "beginning now");
        assertTrue(after.firedMinutes().isEmpty(),
                "so every milestone in the new window can fire again");
        assertEquals(7200L, after.lifetimeSeconds(), "lifetime is not an operator's to reset");
        assertSame(after, tracker.session(uuid), "and the live session is the new one");
    }

    @Test
    @DisplayName("resetWindow does nothing for a player who is not online")
    void resetWindowForAnOfflinePlayerIsANoOp() {
        assertNull(tracker.resetWindow(UUID.randomUUID()));
    }

    @Test
    @DisplayName("rejoining inside a window seeds the milestones it has already passed")
    void firedMilestonesAreSeededFromTheWindowOnRejoin() {
        store.preload(uuid, new SessionSnapshot("Ada", 3600L, clock.wallMillis() - 1000L,
                3600L, clock.wallMillis()));
        clock.advanceWallOnly(Duration.ofHours(1));

        PlayerSession session = tracker.onJoin(uuid, "Ada");

        assertEquals(60L, session.windowMinutes());
        assertTrue(session.hasFired(60),
                "minute 60 already went by in this window; it must not fire again");
        assertFalse(session.hasFired(120), "minute 120 has not been reached");
    }

    @Test
    @DisplayName("a window that was reset seeds no fired milestones at all")
    void aFreshWindowSeedsNoFiredMilestones() {
        store.preload(uuid, new SessionSnapshot("Ada", 3600L, clock.wallMillis() - 1000L,
                3600L, clock.wallMillis()));
        clock.advanceWallOnly(Duration.ofHours(9));

        PlayerSession session = tracker.onJoin(uuid, "Ada");

        assertTrue(session.firedMinutes().isEmpty(),
                "a new counted window fires its milestones again from the start");
    }

    @Test
    @DisplayName("reseeding after a reload suppresses milestones the window already passed")
    void reseedFiredMilestonesReadsTheConfigurationInForce() {
        tracker.onJoin(uuid, "Ada");
        clock.advance(Duration.ofMinutes(90));
        tracker.accrue(uuid, false);
        assertTrue(tracker.session(uuid).firedMinutes().isEmpty(),
                "nothing has fired: this issue registers no observer");

        config = TestConfigs.parse("""
                reminders:
                  milestones:
                    - minute: 30
                      message: "<green>thirty</green>"
                    - minute: 120
                      message: "<green>two hours</green>"
                """);
        tracker.reseedFiredMilestones();

        assertTrue(tracker.session(uuid).hasFired(30),
                "a milestone added mid-session below the current window must not fire at once");
        assertFalse(tracker.session(uuid).hasFired(120), "one still ahead of them stays armed");
    }
}
