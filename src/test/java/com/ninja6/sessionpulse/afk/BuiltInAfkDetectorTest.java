package com.ninja6.sessionpulse.afk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ninja6.sessionpulse.config.PluginConfig;
import com.ninja6.sessionpulse.platform.RecordingScheduler;
import java.util.UUID;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link BuiltInAfkDetector}: idle for exactly the threshold is AFK, input restarts the timer,
 * and only a join creates an entry.
 */
class BuiltInAfkDetectorTest {

    private final AfkFixture.Clock clock = new AfkFixture.Clock();
    private PluginConfig config = AfkFixture.config("BUILT_IN", 300);
    private final BuiltInAfkDetector detector = new BuiltInAfkDetector(clock, () -> config);
    private final UUID uuid = UUID.randomUUID();
    private final Player player =
            AfkFixture.player(uuid, "Ada", false, new RecordingScheduler(), new int[1]);

    @Test
    @DisplayName("a seeded player is not AFK a second short of the threshold, and is AFK at it")
    void afkAtExactlyTheThreshold() {
        detector.seed(uuid);

        clock.advanceSeconds(299);
        assertFalse(detector.isAfk(player), "299 of 300 seconds idle is not AFK");

        clock.advanceSeconds(1);
        assertTrue(detector.isAfk(player), "300 of 300 seconds idle is AFK");
    }

    @Test
    @DisplayName("input restarts the timer from now")
    void inputRestartsTheTimer() {
        detector.seed(uuid);
        clock.advanceSeconds(299);
        detector.markActive(uuid);

        clock.advanceSeconds(299);
        assertFalse(detector.isAfk(player), "299 seconds since the last input");

        clock.advanceSeconds(1);
        assertTrue(detector.isAfk(player), "300 seconds since the last input");
    }

    @Test
    @DisplayName("a player the timer has never seen is never AFK")
    void anUnseededPlayerIsNeverAfk() {
        clock.advanceSeconds(100_000);

        assertFalse(detector.isAfk(player),
                "a missing entry read as zero would pause everybody at boot");
    }

    @Test
    @DisplayName("forget drops the entry")
    void forgetDropsTheEntry() {
        detector.seed(uuid);
        detector.forget(uuid);
        clock.advanceSeconds(1_000);

        assertEquals(0, detector.tracked());
        assertFalse(detector.isAfk(player));
    }

    @Test
    @DisplayName("input after forget does not bring the player back")
    void inputAfterForgetCreatesNoEntry() {
        detector.seed(uuid);
        detector.forget(uuid);

        detector.markActive(uuid);

        assertEquals(0, detector.tracked(),
                "async chat delivered after the quit would otherwise leak one entry per player");
    }

    @Test
    @DisplayName("the threshold is read from the configuration in force, not captured")
    void aReloadReachesTheThreshold() {
        detector.seed(uuid);
        clock.advanceSeconds(60);
        assertFalse(detector.isAfk(player));

        config = AfkFixture.config("BUILT_IN", 60);

        assertTrue(detector.isAfk(player), "the lowered threshold applies at the next check");
    }
}
