package com.ninja6.sessionpulse.session;

import static com.ninja6.sessionpulse.session.MilestoneClaimTest.milestones;
import static com.ninja6.sessionpulse.session.MilestoneClaimTest.minutesOf;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ninja6.sessionpulse.config.PluginConfig;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A reload through {@link SessionTracker#applyReload} fires nothing the windows already passed.
 *
 * <p>The publish callback is where these tests put the other threads: a tick or a join
 * landing on either side of the swap is written into it, which is exactly the interleaving
 * Folia allows and a single-threaded server does not.
 */
class MilestoneReloadTest {

    private final TestClock clock = new TestClock();
    private final RecordingSessionStore store = new RecordingSessionStore();
    private PluginConfig config = milestones(120);
    private final SessionTracker tracker = new SessionTracker(() -> config, clock, store);
    private final UUID uuid = UUID.randomUUID();

    private PlayerSession playFor(Duration played) {
        tracker.onJoin(uuid, "Ada");
        clock.advance(played);
        return tracker.accrue(uuid, false);
    }

    @Test
    @DisplayName("lowering a milestone below the current window does not fire it")
    void loweringMinuteDoesNotFire() {
        PlayerSession session = playFor(Duration.ofMinutes(90));
        assertEquals(List.of(), minutesOf(tracker.claimDue(session)));

        tracker.applyReload(milestones(30), published -> config = published);

        assertEquals(List.of(), minutesOf(tracker.claimDue(session)),
                "ninety minutes in, a milestone moved to thirty has already gone by");
        assertTrue(session.hasFired(30));
    }

    @Test
    @DisplayName("a tick on either side of the publish claims nothing the new file lowered")
    void seedBeforePublishClosesRace() {
        PlayerSession session = playFor(Duration.ofMinutes(90));
        List<Integer> claimed = new ArrayList<>();

        tracker.applyReload(milestones(30), published -> {
            claimed.addAll(minutesOf(tracker.claimDue(session)));
            config = published;
            claimed.addAll(minutesOf(tracker.claimDue(session)));
        });

        assertEquals(List.of(), claimed,
                "a tick straight after publication found minute 30 unseeded; the seed has to "
                        + "happen before the new configuration is visible");
    }

    @Test
    @DisplayName("a player joining between the first seed and the publish is seeded too")
    void joinBetweenSeedAndPublishDoesNotRefire() {
        UUID late = UUID.randomUUID();
        store.preload(late, new SessionSnapshot("Bo", 5400L, clock.wallMillis(), 5400L,
                clock.wallMillis()));
        playFor(Duration.ofMinutes(1));

        tracker.applyReload(milestones(30), published -> {
            tracker.onJoin(late, "Bo");
            config = published;
        });

        PlayerSession joined = tracker.accrue(late, false);
        assertEquals(List.of(), minutesOf(tracker.claimDue(joined)),
                "the join was seeded against the old file, so only a seed after publication "
                        + "knows that ninety stored minutes are past thirty");
    }

    @Test
    @DisplayName("a reload running entirely inside a join's compute does not leave it unseeded")
    void reloadInsideJoinComputeDoesNotRefire() {
        SessionTracker[] holder = new SessionTracker[1];
        SessionStore reloadingStore = new SessionStore() {
            @Override
            public SessionSnapshot load(UUID key) {
                // The whole reload overlaps the compute: it cannot see this session yet.
                holder[0].applyReload(milestones(30), published -> config = published);
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

        assertEquals(List.of(), minutesOf(joining.claimDue(joining.accrue(uuid, false))),
                "the compute seeded against the old file; ninety stored minutes are past thirty");
    }

    @Test
    @DisplayName("raising a milestone above the current window leaves it armed")
    void raisingMinuteRearms() {
        config = milestones(30);
        PlayerSession session = playFor(Duration.ofMinutes(31));
        assertEquals(List.of(30), minutesOf(tracker.claimDue(session)));
        clock.advance(Duration.ofMinutes(59));
        tracker.accrue(uuid, false);

        tracker.applyReload(milestones(120), published -> config = published);
        assertEquals(List.of(), minutesOf(tracker.claimDue(session)));

        clock.advance(Duration.ofMinutes(30));
        tracker.accrue(uuid, false);
        assertEquals(List.of(120), minutesOf(tracker.claimDue(session)),
                "seeding marks only what the window has reached, not every configured minute");
    }

    @Test
    @DisplayName("a reload never un-fires a milestone")
    void reloadIsAdditive() {
        config = milestones(60);
        PlayerSession session = playFor(Duration.ofMinutes(61));
        assertEquals(List.of(60), minutesOf(tracker.claimDue(session)));

        tracker.applyReload(milestones(90), published -> config = published);
        assertTrue(session.hasFired(60), "a reload without minute 60 keeps it fired");

        tracker.applyReload(milestones(60, 90), published -> config = published);
        assertEquals(List.of(), minutesOf(tracker.claimDue(session)),
                "putting minute 60 back must not fire it a second time in this window");
    }
}
