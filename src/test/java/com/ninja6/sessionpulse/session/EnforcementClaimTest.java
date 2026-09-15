package com.ninja6.sessionpulse.session;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ninja6.sessionpulse.config.PluginConfig;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link SessionTracker#claimEnforcement}: the threshold is the counted window, the boundary is
 * the milestones' one, and a connection is claimed once.
 */
class EnforcementClaimTest {

    private final TestClock clock = new TestClock();
    private final RecordingSessionStore store = new RecordingSessionStore();
    private PluginConfig config = enforcement(true, 240);
    private final SessionTracker tracker = new SessionTracker(() -> config, clock, store);
    private final UUID uuid = UUID.randomUUID();

    static PluginConfig enforcement(boolean enabled, int atMinutes) {
        return TestConfigs.parse("enforcement:\n"
                + "  enabled: " + enabled + "\n"
                + "  at-minutes: " + atMinutes + "\n");
    }

    private boolean playAndClaim(Duration played) {
        clock.advance(played);
        PlayerSession session = tracker.accrue(uuid, false);
        return tracker.claimEnforcement(session, session.windowMinutes());
    }

    @Test
    @DisplayName("nothing is claimed while enforcement is disabled, however long the window")
    void disabledClaimsNothing() {
        config = enforcement(false, 240);
        tracker.onJoin(uuid, "Ada");

        assertFalse(playAndClaim(Duration.ofMinutes(10_080)));
    }

    @Test
    @DisplayName("239 minutes and 59 seconds is short of 240; 240 is claimed, exactly once")
    void claimedOnceAtTheMinute() {
        tracker.onJoin(uuid, "Ada");

        assertFalse(playAndClaim(Duration.ofSeconds(240 * 60 - 1)));
        assertTrue(playAndClaim(Duration.ofSeconds(1)));
        assertFalse(playAndClaim(Duration.ofSeconds(1)), "a second tick must not claim again");
        assertFalse(playAndClaim(Duration.ofMinutes(60)));
    }

    @Test
    @DisplayName("the stored window counts: 239 stored minutes plus one played is claimed")
    void readsTheWindowNotTheSession() {
        store.preload(uuid, new SessionSnapshot("Ada", 0L, clock.wallMillis(), 239 * 60L,
                clock.wallMillis()));
        tracker.onJoin(uuid, "Ada");

        assertTrue(playAndClaim(Duration.ofMinutes(1)),
                "a one-minute session must not hide a 240-minute window");
    }

    @Test
    @DisplayName("a session replaced by a reset or removed by a quit claims nothing")
    void staleSessionClaimsNothing() {
        PlayerSession first = tracker.onJoin(uuid, "Ada");
        clock.advance(Duration.ofMinutes(240));
        tracker.accrue(uuid, false);
        tracker.onQuit(uuid);

        assertFalse(tracker.claimEnforcement(first, first.windowMinutes()));
        assertFalse(first.releaseEnforcement(), "and the refusal left no claim behind");
    }

    @Test
    @DisplayName("a released claim is claimed again by the next tick; a rejoin is a new claim")
    void releaseAndRejoinReArm() {
        PlayerSession session = tracker.onJoin(uuid, "Ada");
        assertTrue(playAndClaim(Duration.ofMinutes(240)));
        assertTrue(session.releaseEnforcement());
        assertTrue(playAndClaim(Duration.ofSeconds(1)));

        tracker.onQuit(uuid);
        tracker.onJoin(uuid, "Ada");
        assertTrue(playAndClaim(Duration.ofSeconds(1)),
                "a reconnect still over the limit is judged afresh, not let off by the old claim");
    }
}
