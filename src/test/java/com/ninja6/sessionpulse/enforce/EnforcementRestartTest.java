package com.ninja6.sessionpulse.enforce;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ninja6.sessionpulse.session.PlayerSession;
import java.nio.file.Path;
import java.time.Duration;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent.Result;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A cooldown and the window reset that goes with it, across a server restart and across a
 * crash, through the real data file.
 */
class EnforcementRestartTest {

    @TempDir
    Path dir;

    /**
     * A kick at 240 minutes, on a fresh server over {@link #dir}, with a periodic-flush
     * heartbeat at 239 already in memory. Without it the record would not exist before the
     * cooldown, storage would create one with no window at all, and a missing checkpoint
     * would pass unnoticed.
     */
    private EnforceFixture kicked() {
        EnforceFixture f = new EnforceFixture(dir, EnforceFixture.ENABLED);
        f.join();
        f.tick(Duration.ofMinutes(239));
        f.tracker.snapshotAll().forEach(f.storage::save);
        f.tick(Duration.ofMinutes(1));
        assertEquals(1, f.kicks.size());
        return f;
    }

    /** The next server: refuses now with the right minutes, admits after, and does not re-kick. */
    private static void assertHeldOutThenFresh(EnforceFixture next) {
        AsyncPlayerPreLoginEvent refused = next.preLogin();
        assertEquals(Result.KICK_OTHER, refused.getLoginResult());
        assertTrue(refused.getKickMessage().startsWith("§eBreak Ada "), refused.getKickMessage());
        assertTrue(refused.getKickMessage().endsWith(" 30"), refused.getKickMessage());

        next.clock.advanceWallOnly(Duration.ofMinutes(10));
        assertTrue(next.preLogin().getKickMessage().endsWith(" 20"), "twenty minutes left");

        // Past the cooldown, far short of window-reset-hours.
        next.clock.advanceWallOnly(Duration.ofMinutes(20).plusMillis(1));
        assertEquals(Result.ALLOWED, next.preLogin().getLoginResult());
        PlayerSession rejoined = next.join();
        assertEquals(0L, rejoined.windowSeconds(), "the stored window is the reset one");
        assertEquals(240 * 60L, rejoined.lifetimeSeconds(), "and lifetime came through it");
        next.tick(Duration.ofSeconds(1));
        next.tick(Duration.ofMinutes(60));
        assertTrue(next.kicks.isEmpty(), "a carried 240-minute window would kick here");
    }

    @Test
    @DisplayName("clean stop: kick, quit flush, shutdown; the next start holds the player out, then admits")
    void cleanRestart() {
        EnforceFixture f = kicked();
        f.quit();
        f.scheduler.runOnce();
        f.storage.shutdown();

        assertHeldOutThenFresh(f.restart());
    }

    @Test
    @DisplayName("crash after the kick: only setCooldown's flush ran, and it carried both")
    void crashAfterCooldownFlush() {
        EnforceFixture f = kicked();
        assertEquals(1, f.scheduler.runOnce(), "the one forced flush the cooldown asked for");
        // No quit, no shutdown: the process is gone.

        assertHeldOutThenFresh(f.restart());
    }

    @Test
    @DisplayName("negative control: a crash before any flush leaves nothing for the next start")
    void crashBeforeFlushForgets() {
        EnforceFixture f = kicked();

        EnforceFixture next = f.restart();

        assertEquals(Result.ALLOWED, next.preLogin().getLoginResult(),
                "if this refuses, the tests above are not proving the flush does the work");
    }
}
