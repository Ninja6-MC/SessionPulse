package com.ninja6.sessionpulse.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.ninja6.sessionpulse.config.OvertimePolicy;
import com.ninja6.sessionpulse.config.PluginConfig;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link SessionTracker#claimOvertime}: when the recurring reminder falls due, and that each
 * point on the series is claimed once.
 *
 * <p>Every point here is read from the counted window, driven by {@link TestClock}. The AFK
 * cases move both readings together, as real time does, so a schedule that reads the wall
 * clock or the time since join has something to trip over.
 */
class OvertimeClaimTest {

    private final TestClock clock = new TestClock();
    private final RecordingSessionStore store = new RecordingSessionStore();
    private PluginConfig config = overtime(180, 30);
    private final SessionTracker tracker = new SessionTracker(() -> config, clock, store);
    private final UUID uuid = UUID.randomUUID();

    /** A configuration with overtime enabled and no milestones. */
    static PluginConfig overtime(int after, int every) {
        return overtime(true, after, every);
    }

    /** A configuration with overtime set as given and no milestones. */
    static PluginConfig overtime(boolean enabled, int after, int every) {
        return TestConfigs.parse("reminders:\n  overtime:\n"
                + "    enabled: " + enabled + "\n"
                + "    after-minutes: " + after + "\n"
                + "    every-minutes: " + every + "\n"
                + "    message: \"<red>over</red>\"\n");
    }

    /** The minute claimed, or {@code null}. */
    static Long minuteOf(OvertimeClaim claim) {
        return claim == null ? null : claim.minute();
    }

    /** Real time passes, the tick credits it, and the tick claims. */
    private Long playAndClaim(Duration played) {
        clock.advance(played);
        return minuteOf(tracker.claimOvertime(tracker.accrue(uuid, false)));
    }

    /** Ticks once a second, active, for {@code played}, returning every minute claimed. */
    private List<Long> tickEverySecond(Duration played) {
        List<Long> claimed = new ArrayList<>();
        for (long s = 0; s < played.toSeconds(); s++) {
            Long minute = playAndClaim(Duration.ofSeconds(1));
            if (minute != null) {
                claimed.add(minute);
            }
        }
        return claimed;
    }

    /** Ticks once a second while AFK for {@code away}, asserting nothing is claimed or counted. */
    private void awayEverySecond(Duration away, long windowMinutes) {
        for (long s = 0; s < away.toSeconds(); s++) {
            clock.advance(Duration.ofSeconds(1));
            PlayerSession session = tracker.accrue(uuid, true);
            assertNull(tracker.claimOvertime(session),
                    "claimed while AFK, " + s + "s into the gap");
            assertEquals(windowMinutes, session.windowMinutes());
        }
    }

    @Test
    @DisplayName("the due minute is anchored on after-minutes, with the same boundary as milestones")
    void dueMinuteTable() {
        OvertimePolicy policy = new OvertimePolicy(true, 180, 30, "m");
        long[][] rows = {
                {0, -1}, {179, -1}, {180, 180}, {181, 180}, {209, 180}, {210, 210},
                {239, 210}, {240, 240}, {10_000, 9990},
        };
        for (long[] row : rows) {
            assertEquals(row[1], SessionTracker.dueOvertimeMinute(policy, row[0]),
                    "window " + row[0]);
        }
        assertEquals(190, SessionTracker.dueOvertimeMinute(new OvertimePolicy(true, 190, 30, "m"), 200),
                "anchored on a multiple of every-minutes, 200 would be due 180, before the first");
        assertEquals(PlayerSession.NO_OVERTIME, SessionTracker.dueOvertimeMinute(policy, 179));
        assertEquals(10_080, SessionTracker.dueOvertimeMinute(new OvertimePolicy(true, 1, 1, "m"),
                10_080), "every-minutes of 1 is every counted minute, no division by zero");
    }

    @Test
    @DisplayName("ticking every second claims each point once: 180, 210, 240")
    void onePerPointWhileTickingEverySecond() {
        tracker.onJoin(uuid, "Ada");
        assertNull(playAndClaim(Duration.ofMinutes(179)));

        assertEquals(List.of(180L, 210L, 240L), tickEverySecond(Duration.ofMinutes(62)),
                "a claim on every tick past the threshold, or a repeat of one point, lands here");
    }

    @Test
    @DisplayName("an hour AFK just short of the threshold is one reminder on return, not sixty")
    void longAfkGapAcrossThreshold() {
        tracker.onJoin(uuid, "Ada");
        assertNull(playAndClaim(Duration.ofSeconds(179 * 60 + 30)));

        awayEverySecond(Duration.ofMinutes(60), 179);

        assertEquals(List.of(180L), tickEverySecond(Duration.ofSeconds(30)),
                "180 counted minutes is 30 active seconds after the return, whatever the wall "
                        + "clock or the time since join say");
        assertEquals(List.of(), tickEverySecond(Duration.ofSeconds(29 * 60 + 59)),
                "nothing more through 209:59");
        assertEquals(List.of(210L), tickEverySecond(Duration.ofSeconds(1)));
    }

    @Test
    @DisplayName("AFK after a reminder does not bring the next one forward")
    void afkGapAfterThreshold() {
        tracker.onJoin(uuid, "Ada");
        assertEquals(180L, playAndClaim(Duration.ofMinutes(180)));
        assertNull(playAndClaim(Duration.ofMinutes(15)));

        awayEverySecond(Duration.ofMinutes(90), 195);

        assertEquals(List.of(), tickEverySecond(Duration.ofSeconds(14 * 60 + 59)),
                "measured from the last reminder on the wall clock, 210 would be long past");
        assertEquals(List.of(210L), tickEverySecond(Duration.ofSeconds(1)));
    }

    @Test
    @DisplayName("a window jumping past several points claims only the latest, once")
    void windowJumpClaimsLatestOnly() {
        tracker.onJoin(uuid, "Ada");
        assertNull(playAndClaim(Duration.ofMinutes(179)));

        assertEquals(240L, playAndClaim(Duration.ofMinutes(66)),
                "a walk through 180 and 210 would send a burst");
        assertNull(playAndClaim(Duration.ofMinutes(5)), "250 is not a point on the series");
        assertNull(playAndClaim(Duration.ofSeconds(19 * 60 + 59)));
        assertEquals(270L, playAndClaim(Duration.ofSeconds(1)));
    }

    @Test
    @DisplayName("a rejoin after a checkpointed reminder does not send it again")
    void rejoinSeedsClaimedReminder() {
        store.preload(uuid, new SessionSnapshot("Ada", 210 * 60L, clock.wallMillis(), 210 * 60L,
                clock.wallMillis()));
        tracker.onJoin(uuid, "Ada");

        assertNull(playAndClaim(Duration.ofSeconds(1)));
        assertNull(playAndClaim(Duration.ofSeconds(30 * 60 - 2)), "through 239:59");
        assertEquals(240L, playAndClaim(Duration.ofSeconds(1)));
    }

    @Test
    @DisplayName("a crossing stored short of the threshold fires after the rejoin")
    void rejoinShortOfThresholdStillFires() {
        store.preload(uuid, new SessionSnapshot("Ada", 179 * 60L, clock.wallMillis(), 179 * 60L,
                clock.wallMillis()));
        tracker.onJoin(uuid, "Ada");

        assertEquals(180L, playAndClaim(Duration.ofMinutes(1)),
                "a seed of everything the stored window might have reached would lose this one");
    }

    @Test
    @DisplayName("a reset window starts the series again from after-minutes")
    void resetWindowStartsOver() {
        store.preload(uuid, new SessionSnapshot("Ada", 500 * 60L, clock.wallMillis(), 500 * 60L,
                clock.wallMillis()));
        clock.advanceWallOnly(Duration.ofHours(9));
        tracker.onJoin(uuid, "Ada");

        assertNull(playAndClaim(Duration.ofMinutes(179)));
        assertEquals(180L, playAndClaim(Duration.ofMinutes(1)));
    }

    @Test
    @DisplayName("disabled overtime claims nothing, however long the window")
    void disabledClaimsNothing() {
        config = TestConfigs.defaults();
        tracker.onJoin(uuid, "Ada");
        assertNull(playAndClaim(Duration.ofMinutes(10_000)));

        config = overtime(false, 1, 1);
        assertNull(playAndClaim(Duration.ofMinutes(1)));
        assertEquals(PlayerSession.NO_OVERTIME, tracker.session(uuid).overtimeFiredMinute());
    }

    @Test
    @DisplayName("a detached session claims nothing")
    void detachedSessionClaimsNothing() {
        tracker.onJoin(uuid, "Ada");
        clock.advance(Duration.ofMinutes(180));
        PlayerSession stale = tracker.accrue(uuid, false);
        tracker.onQuit(uuid);
        tracker.onJoin(uuid, "Ada");

        assertNull(tracker.claimOvertime(stale));
    }

    @Test
    @DisplayName("no configuration, as during disable, claims nothing and does not throw")
    void nullConfigClaimsNothing() {
        tracker.onJoin(uuid, "Ada");
        clock.advance(Duration.ofMinutes(180));
        PlayerSession session = tracker.accrue(uuid, false);
        config = null;

        assertNull(tracker.claimOvertime(session));
    }

    @Test
    @DisplayName("the claim carries the message of the configuration that made it")
    void claimCarriesMessage() {
        tracker.onJoin(uuid, "Ada");
        clock.advance(Duration.ofMinutes(180));

        assertEquals(new OvertimeClaim(180, "<red>over</red>"),
                tracker.claimOvertime(tracker.accrue(uuid, false)));
    }
}
