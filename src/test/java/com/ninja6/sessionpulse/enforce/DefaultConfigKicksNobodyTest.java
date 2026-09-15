package com.ninja6.sessionpulse.enforce;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ninja6.sessionpulse.session.PlayerSession;
import com.ninja6.sessionpulse.session.SessionSnapshot;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent.Result;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The README's promise, against the file the jar actually ships: "It never kicks anyone by
 * default".
 *
 * <p>Read from {@code src/main/resources/config.yml} by relative path, as the configuration
 * tests do, so flipping the shipped default fails here and not on somebody's server.
 */
class DefaultConfigKicksNobodyTest {

    @TempDir
    Path dir;

    private static String shipped() throws IOException {
        return Files.readString(Path.of("src", "main", "resources", "config.yml"),
                StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("the shipped config never kicks, never writes a cooldown, and the gate admits")
    void shippedConfigKicksNobody() throws IOException {
        EnforceFixture f = new EnforceFixture(dir, shipped());
        assertFalse(f.config.enforcement().enabled(), "enforcement.enabled ships true");

        // The longest window the threshold can be clamped to, already on record.
        f.storage.save(f.id(), new SessionSnapshot("Ada", 10_080 * 60L, f.clock.wallMillis,
                10_080 * 60L, f.clock.wallMillis));
        PlayerSession session = f.join();
        int milestoneTasks = f.scheduler.entityTargets.size();
        for (int i = 0; i < 20; i++) {
            f.clock.advance(Duration.ofMinutes(1_000));
            f.service.afterAccrual(f.player, f.tracker.accrue(f.id(), false));
        }

        assertEquals(milestoneTasks, f.scheduler.entityTargets.size(),
                "enforcement scheduled an entity task");
        assertTrue(f.kicks.isEmpty());
        assertEquals(0L, f.storage.cooldownExpiresMillis(f.id()));
        assertTrue(f.scheduler.once.isEmpty(), "enforcement asked for a flush");
        assertEquals(session, f.tracker.session(f.id()), "the window was reset");

        // A cooldown planted by an earlier run with enforcement on stays inert once it is off.
        f.storage.setCooldown(f.id(), "Ada", f.clock.wallMillis + 3_600_000L);
        assertEquals(Result.ALLOWED, f.preLogin().getLoginResult());
    }
}
