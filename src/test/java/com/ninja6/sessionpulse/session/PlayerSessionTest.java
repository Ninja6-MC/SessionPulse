package com.ninja6.sessionpulse.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The three figures a session reports, and how they relate.
 *
 * <p>Built directly rather than through the tracker, because what is under test here is the
 * carrier's own arithmetic and not the rollover rules that decide its bases.
 */
class PlayerSessionTest {

    private static final long NANOS_PER_SECOND = 1_000_000_000L;

    /** A session with the given banked bases and nothing accrued yet. */
    private static PlayerSession session(long windowBaseSeconds, long lifetimeBaseSeconds) {
        return new PlayerSession(UUID.randomUUID(), "Ada", 1_000L, 2_000L, 3_000L,
                windowBaseSeconds, lifetimeBaseSeconds);
    }

    @Test
    @DisplayName("session, window and lifetime seconds are three different numbers")
    void sessionSecondsAndWindowSecondsAreDifferentNumbers() {
        PlayerSession session = session(600L, 7200L);

        session.credit(10L * NANOS_PER_SECOND);

        assertEquals(10L, session.sessionSeconds(), "this sitting");
        assertEquals(610L, session.windowSeconds(), "this counted window");
        assertEquals(7210L, session.lifetimeSeconds(), "ever");
        assertEquals(10L, session.windowMinutes(), "610 seconds is ten whole minutes");
    }

    @Test
    @DisplayName("window minutes truncate rather than round")
    void windowMinutesTruncatesRatherThanRounds() {
        assertEquals(59L, session(3599L, 0L).windowMinutes(),
                "59:59 is not an hour. A milestone the operator wrote as 60 must not fire a "
                        + "second early, because they will time it.");
        assertEquals(60L, session(3600L, 0L).windowMinutes());
    }

    @Test
    @DisplayName("fired milestones are keyed by minute and marking one twice is refused")
    void firedMinutesAreKeyedByMinuteAndAreIdempotent() {
        PlayerSession session = session(0L, 0L);

        assertFalse(session.hasFired(60));
        assertTrue(session.markFired(60), "the first mark is the one that fires");
        assertFalse(session.markFired(60),
                "and the second is refused, which is what makes exactly-once hold even if "
                        + "two ticks overlap");
        assertTrue(session.hasFired(60));
        assertEquals(1, session.firedMinutes().size());
    }

    @Test
    @DisplayName("the fired set handed to a caller cannot be modified")
    void firedMinutesIsUnmodifiableToACaller() {
        PlayerSession session = session(0L, 0L);
        session.seedFired(List.of(60));

        assertThrows(UnsupportedOperationException.class,
                () -> session.firedMinutes().add(120),
                "milestones are recorded through markFired, which reports whether it won");
    }

    @Test
    @DisplayName("a fresh session reports the not-AFK sentinel rather than a time")
    void anActiveSessionReportsTheNotAfkSentinel() {
        PlayerSession session = session(0L, 0L);

        assertFalse(session.isAfk());
        assertEquals(PlayerSession.NOT_AFK, session.afkSinceNanos(),
                "a sentinel, not zero: zero is a legitimate monotonic reading");
    }
}
