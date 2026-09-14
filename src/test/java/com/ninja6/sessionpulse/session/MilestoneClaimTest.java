package com.ninja6.sessionpulse.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ninja6.sessionpulse.config.Milestone;
import com.ninja6.sessionpulse.config.PluginConfig;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link SessionTracker#claimDue}: which milestones a tick claims, and that each is claimed
 * once per counted window.
 *
 * <p>Every boundary here is the counted window in truncated minutes, never the session and
 * never the wall clock. The duplicate and out-of-order cases are integration with
 * {@link PluginConfig}'s dedup and sort, which the claim's early stop relies on.
 */
class MilestoneClaimTest {

    private final TestClock clock = new TestClock();
    private final RecordingSessionStore store = new RecordingSessionStore();
    private PluginConfig config = milestones(60, 120);
    private final SessionTracker tracker = new SessionTracker(() -> config, clock, store);
    private final UUID uuid = UUID.randomUUID();

    /** A configuration with one chat-only milestone at each minute, in the order given. */
    static PluginConfig milestones(int... minutes) {
        StringBuilder yaml = new StringBuilder("reminders:\n  milestones:\n");
        for (int minute : minutes) {
            yaml.append("    - minute: ").append(minute).append('\n')
                    .append("      message: \"<green>m").append(minute).append("</green>\"\n");
        }
        return TestConfigs.parse(yaml.toString());
    }

    /** The minutes of what was claimed, in the order it was claimed. */
    static List<Integer> minutesOf(List<Milestone> claimed) {
        return claimed.stream().map(Milestone::minute).toList();
    }

    /** Real time passes, the tick credits it, and the tick claims. */
    private List<Integer> playAndClaim(Duration played) {
        clock.advance(played);
        PlayerSession session = tracker.accrue(uuid, false);
        return minutesOf(tracker.claimDue(session));
    }

    @Test
    @DisplayName("59:59 of counted window does not reach the milestone at minute 60")
    void fiftyNineMinutesDoesNotFireSixty() {
        tracker.onJoin(uuid, "Ada");

        assertEquals(List.of(), playAndClaim(Duration.ofMinutes(60).minusSeconds(1)),
                "the window is truncated to whole minutes; 59:59 is minute 59");
    }

    @Test
    @DisplayName("minute 60 fires at 60:00, and not again at 60:01")
    void sixtyFiresSixtyExactlyOnce() {
        tracker.onJoin(uuid, "Ada");

        assertEquals(List.of(60), playAndClaim(Duration.ofMinutes(60)),
                "a window of exactly 60 minutes has reached minute 60");
        assertEquals(List.of(), playAndClaim(Duration.ofSeconds(1)),
                "the next tick must not claim it a second time");
        assertTrue(tracker.session(uuid).hasFired(60));
    }

    @Test
    @DisplayName("a milestone passed between two ticks is still claimed on the later one")
    void sixtyOneStillFiresIfMissed() {
        tracker.onJoin(uuid, "Ada");

        assertEquals(List.of(60), playAndClaim(Duration.ofMinutes(61)),
                "a lagging tick that first sees minute 61 must not lose the minute-60 alert");
    }

    @Test
    @DisplayName("two entries at the same minute fire once, with the first entry's payload")
    void duplicateMinutesFireOnce() {
        config = TestConfigs.parse("""
                reminders:
                  milestones:
                    - minute: 60
                      message: "<green>first</green>"
                    - minute: 60
                      message: "<green>second</green>"
                """);
        tracker.onJoin(uuid, "Ada");
        clock.advance(Duration.ofMinutes(60));

        List<Milestone> claimed = tracker.claimDue(tracker.accrue(uuid, false));

        assertEquals(List.of("<green>first</green>"),
                claimed.stream().map(Milestone::message).toList(),
                "integration with PluginConfig: the duplicate is dropped at load, the first kept");
    }

    @Test
    @DisplayName("milestones written out of order are claimed in ascending minute order")
    void outOfOrderConfigFiresAscending() {
        config = milestones(120, 60);
        tracker.onJoin(uuid, "Ada");

        assertEquals(List.of(60, 120), playAndClaim(Duration.ofMinutes(130)),
                "integration with PluginConfig's sort: the claim stops at the first milestone "
                        + "not reached, so an unsorted list would skip minute 60 here");
    }

    @Test
    @DisplayName("two milestones crossed in one tick are both claimed, ascending")
    void twoMilestonesInOneTick() {
        config = milestones(60, 61);
        tracker.onJoin(uuid, "Ada");

        assertEquals(List.of(), playAndClaim(Duration.ofSeconds(59 * 60 + 30)));
        assertEquals(List.of(60, 61), playAndClaim(Duration.ofMinutes(2)),
                "a two-minute lag spike crosses both; neither may be dropped");
    }

    @Test
    @DisplayName("a milestone is identified by its minute, not its position in the list")
    void indexIsNotIdentity() {
        config = milestones(60);
        tracker.onJoin(uuid, "Ada");
        assertEquals(List.of(60), playAndClaim(Duration.ofMinutes(61)));

        // Swapped with no reseed, so the claim alone decides: 30 is new, 60 is not.
        config = milestones(60, 30);

        assertEquals(List.of(30), playAndClaim(Duration.ofSeconds(1)),
                "keyed by index, inserting 30 in front would refire 60 and suppress 30");
    }

    @Test
    @DisplayName("AFK time does not count towards a milestone")
    void afkTimeDoesNotReachMilestone() {
        tracker.onJoin(uuid, "Ada");
        clock.advance(Duration.ofMinutes(30));
        tracker.accrue(uuid, false);
        clock.advance(Duration.ofMinutes(30));
        PlayerSession session = tracker.accrue(uuid, true);

        assertEquals(List.of(), minutesOf(tracker.claimDue(session)),
                "an hour connected with half of it away is thirty counted minutes");
    }

    @Test
    @DisplayName("the milestone reads the counted window, carried across a rejoin")
    void countedWindowNotSession() {
        store.preload(uuid, new SessionSnapshot("Ada", 3000L, clock.wallMillis(), 3000L,
                clock.wallMillis()));
        tracker.onJoin(uuid, "Ada");

        assertEquals(List.of(60), playAndClaim(Duration.ofMinutes(10)),
                "fifty stored minutes plus ten this session is the hour, though the session is ten");
    }

    @Test
    @DisplayName("no configuration, as during disable, claims nothing and does not throw")
    void nullConfigClaimsNothing() {
        tracker.onJoin(uuid, "Ada");
        clock.advance(Duration.ofMinutes(90));
        PlayerSession session = tracker.accrue(uuid, false);
        config = null;

        assertEquals(List.of(), tracker.claimDue(session));
        assertTrue(session.firedMinutes().isEmpty(), "and marks nothing as fired");
    }
}
