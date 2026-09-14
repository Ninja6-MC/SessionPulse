package com.ninja6.sessionpulse.session;

import static com.ninja6.sessionpulse.session.OvertimeClaimTest.minuteOf;
import static com.ninja6.sessionpulse.session.OvertimeClaimTest.overtime;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.ninja6.sessionpulse.config.PluginConfig;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A reload through {@link SessionTracker#applyReload} sends no overtime reminder the windows
 * already passed, and starts each player at the next point on the new series.
 *
 * <p>As in {@link MilestoneReloadTest}, the publish callback is where the other threads go:
 * a tick or a join written into it lands on one side of the swap or the other.
 */
class OvertimeReloadTest {

    private final TestClock clock = new TestClock();
    private final RecordingSessionStore store = new RecordingSessionStore();
    private PluginConfig config = overtime(180, 30);
    private final SessionTracker tracker = new SessionTracker(() -> config, clock, store);
    private final UUID uuid = UUID.randomUUID();

    private Long playAndClaim(Duration played) {
        clock.advance(played);
        return minuteOf(tracker.claimOvertime(tracker.accrue(uuid, false)));
    }

    /** Joins, plays {@code minutes} in one tick and returns what that tick claimed. */
    private Long playFor(long minutes) {
        tracker.onJoin(uuid, "Ada");
        return playAndClaim(Duration.ofMinutes(minutes));
    }

    private void reload(PluginConfig next) {
        tracker.applyReload(next, published -> config = published);
    }

    /** Nothing on the tick after the reload, nothing up to a second short, then the point. */
    private void nextPointIs(long windowMinutesNow, long expected) {
        assertNull(playAndClaim(Duration.ofSeconds(1)), "the tick straight after the reload");
        assertNull(playAndClaim(Duration.ofSeconds((expected - windowMinutesNow) * 60 - 2)),
                "a second short of " + expected);
        assertEquals(expected, playAndClaim(Duration.ofSeconds(1)));
    }

    @Test
    @DisplayName("lowering after-minutes below the window starts at the next point, not a replay")
    void loweringAfter() {
        assertEquals(180L, playFor(200));

        reload(overtime(60, 30));

        nextPointIs(200, 210);
    }

    @Test
    @DisplayName("lowering every-minutes does not send the points it adds behind the window")
    void loweringEvery() {
        assertEquals(180L, playFor(200));

        reload(overtime(180, 10));

        nextPointIs(200, 210);
    }

    @Test
    @DisplayName("raising after-minutes above the window arms the new first point")
    void raisingAfter() {
        assertEquals(240L, playFor(250));

        reload(overtime(300, 30));

        nextPointIs(250, 300);
    }

    @Test
    @DisplayName("enabling overtime mid-session starts at the next point, not on the reload")
    void enablingMidSession() {
        config = overtime(false, 180, 30);
        assertNull(playFor(500));

        reload(overtime(180, 30));

        nextPointIs(500, 510);
    }

    @Test
    @DisplayName("raising every-minutes never re-arms a reminder already sent")
    void raisingEvery() {
        assertEquals(240L, playFor(250));

        reload(overtime(180, 60));

        nextPointIs(250, 300);
    }

    @Test
    @DisplayName("a tick on either side of the publish claims nothing the new file lowered")
    void seedBeforePublishClosesRace() {
        tracker.onJoin(uuid, "Ada");
        clock.advance(Duration.ofMinutes(90));
        PlayerSession session = tracker.accrue(uuid, false);
        List<Long> claimed = new ArrayList<>();

        tracker.applyReload(overtime(60, 30), published -> {
            claimed.add(minuteOf(tracker.claimOvertime(session)));
            config = published;
            claimed.add(minuteOf(tracker.claimOvertime(session)));
        });

        assertEquals(Arrays.asList(null, null), claimed,
                "a tick straight after publication found minute 90 unseeded; the seed has to "
                        + "happen before the new configuration is visible");
    }

    @Test
    @DisplayName("a player joining between the first seed and the publish is seeded too")
    void joinBetweenSeedAndPublishDoesNotSend() {
        UUID late = UUID.randomUUID();
        store.preload(late, new SessionSnapshot("Bo", 5400L, clock.wallMillis(), 5400L,
                clock.wallMillis()));
        playFor(1);

        tracker.applyReload(overtime(60, 30), published -> {
            tracker.onJoin(late, "Bo");
            config = published;
        });

        assertNull(tracker.claimOvertime(tracker.accrue(late, false)),
                "the join was seeded against the old file, so only a seed after publication "
                        + "knows that ninety stored minutes are past sixty");
    }

    @Test
    @DisplayName("a reload running entirely inside a join's compute does not leave it unseeded")
    void reloadInsideJoinComputeDoesNotSend() {
        SessionTracker[] holder = new SessionTracker[1];
        SessionStore reloadingStore = new SessionStore() {
            @Override
            public SessionSnapshot load(UUID key) {
                holder[0].applyReload(overtime(60, 30), published -> config = published);
                return new SessionSnapshot("Ada", 5400L, clock.wallMillis(), 5400L,
                        clock.wallMillis());
            }

            @Override
            public void save(UUID key, SessionSnapshot snapshot) {
            }
        };
        SessionTracker joining = new SessionTracker(() -> config, clock, reloadingStore);
        holder[0] = joining;

        joining.onJoin(uuid, "Ada");
        clock.advance(Duration.ofSeconds(1));

        assertNull(joining.claimOvertime(joining.accrue(uuid, false)),
                "the compute seeded against the old file; ninety stored minutes are past sixty");
    }
}
