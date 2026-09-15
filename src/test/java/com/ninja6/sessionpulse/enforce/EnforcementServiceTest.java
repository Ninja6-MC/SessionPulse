package com.ninja6.sessionpulse.enforce;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ninja6.sessionpulse.session.PlayerSession;
import com.ninja6.sessionpulse.session.SessionSnapshot;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link EnforcementService}: claimed on the tick, carried out on the player's region in the
 * documented order, and never twice for one connection.
 *
 * <p>Storage is the real {@link com.ninja6.sessionpulse.storage.YamlDataStorage} over a
 * temporary file (see {@link EnforceFixture}), so "a flush was queued" means the store's own
 * forced flush sits in {@code scheduler.once}, not that a fake recorded a call.
 */
class EnforcementServiceTest {

    private static final long THIRTY_MINUTES = 30 * 60_000L;

    @TempDir
    Path dir;

    private EnforceFixture enabled() {
        return new EnforceFixture(dir, EnforceFixture.ENABLED);
    }

    @Test
    @DisplayName("at the threshold: one kick on the region, cooldown and zeroed window stored first")
    void atThresholdKicksOnceAfterCooldownAndReset() {
        EnforceFixture f = enabled();
        f.join();
        assertTrue(f.tick(Duration.ofSeconds(240 * 60 - 1)) != null);
        assertTrue(f.kicks.isEmpty(), "239:59 is short of the limit");
        long wallAtClaim = f.clock.wallMillis + 1000L;

        f.tick(Duration.ofSeconds(1));

        assertEquals(1, f.kicks.size());
        EnforceFixture.Kick kick = f.kicks.get(0);
        assertEquals(EnforceFixture.KICK_AT_240, kick.message(),
                "placeholders are the claimed window and the configured cooldown");
        assertTrue(kick.inEntity(), "kickPlayer must run on the player's region");
        assertEquals(wallAtClaim + THIRTY_MINUTES, kick.cooldownAtKick(),
                "the cooldown must be in memory before the kick, or a fast reconnect beats it");
        assertEquals(0L, kick.liveWindowAtKick(), "the window is reset before the kick");

        assertEquals(wallAtClaim + THIRTY_MINUTES, f.storage.cooldownExpiresMillis(f.id()));
        SessionSnapshot stored = f.storage.load(f.id());
        assertEquals(0L, stored.windowSeconds(), "the reset window is checkpointed, not just live");
        assertEquals(240 * 60L, stored.lifetimeSeconds(), "lifetime survives the reset");
        assertEquals(1, f.scheduler.once.size(), "setCooldown's forced flush is queued");
        assertFalse(f.permissionReadInEntity.isEmpty());
        assertFalse(f.permissionReadInEntity.contains(false),
                "sessionpulse.exempt read on the global tick");
    }

    @Test
    @DisplayName("two ticks before the region runs schedule one task, one kick and one cooldown")
    void claimedOnceWhileRegionPending() {
        EnforceFixture f = enabled();
        f.join();
        f.scheduler.deferEntity = true;

        f.tick(Duration.ofMinutes(240));
        f.tick(Duration.ofSeconds(1));
        f.tick(Duration.ofSeconds(1));

        assertEquals(1, f.scheduler.entityTargets.size());
        assertEquals(1, f.scheduler.runEntity());
        assertEquals(1, f.kicks.size());
    }

    @Test
    @DisplayName("a player gone before the region runs: nothing written; the rejoin is kicked")
    void retiredWritesNothingRejoinIsKicked() {
        EnforceFixture f = enabled();
        f.join();
        f.scheduler.deferEntity = true;

        f.tick(Duration.ofMinutes(240));
        assertEquals(1, f.scheduler.retireEntity());
        f.quit();
        assertTrue(f.kicks.isEmpty());
        assertEquals(0L, f.storage.cooldownExpiresMillis(f.id()));

        f.join();
        f.scheduler.deferEntity = false;
        f.tick(Duration.ofSeconds(1));
        assertEquals(1, f.kicks.size(), "the reconnect is still over the limit");
    }

    @Test
    @DisplayName("claimed, then the quit lands before the region task: no cooldown, no kick")
    void quitBetweenClaimAndRegion() {
        EnforceFixture f = enabled();
        f.join();
        f.scheduler.deferEntity = true;

        f.tick(Duration.ofMinutes(240));
        f.quit();
        f.scheduler.runEntity();

        assertTrue(f.kicks.isEmpty());
        assertEquals(0L, f.storage.cooldownExpiresMillis(f.id()));
        assertEquals(240 * 60L, f.storage.load(f.id()).windowSeconds(),
                "the quit's final figure stands; a stale session must not reset it");
    }

    @Test
    @DisplayName("an exempt player is never kicked, has no cooldown and keeps their window")
    void exemptIsLeftAlone() {
        EnforceFixture f = enabled();
        f.exempt = true;
        PlayerSession session = f.join();

        f.tick(Duration.ofMinutes(240));
        f.tick(Duration.ofMinutes(60));

        assertTrue(f.kicks.isEmpty());
        assertEquals(0L, f.storage.cooldownExpiresMillis(f.id()));
        assertTrue(f.scheduler.once.isEmpty(), "nothing was written, so nothing is flushed");
        assertEquals(session, f.tracker.session(f.id()), "the window was not reset");
        assertEquals(1, f.scheduler.entityTargets.size(), "one task per connection, not per tick");
        assertFalse(f.permissionReadInEntity.contains(false),
                "sessionpulse.exempt read on the global tick");
    }

    @Test
    @DisplayName("disabled schedules nothing; enabling it by reload kicks on the next tick")
    void reloadEnables() {
        EnforceFixture f = new EnforceFixture(dir, EnforceFixture.DISABLED);
        f.join();

        f.tick(Duration.ofMinutes(300));
        assertTrue(f.scheduler.entityTargets.isEmpty());

        f.config = EnforceFixture.parse(EnforceFixture.ENABLED);
        f.tick(Duration.ofSeconds(1));
        assertEquals(1, f.kicks.size(), "no seeding: a player already over the limit goes");
    }

    @Test
    @DisplayName("claimed, disabled before the region runs, then enabled again: kicked then")
    void disabledBetweenClaimAndRegionReleasesClaim() {
        EnforceFixture f = enabled();
        PlayerSession session = f.join();
        f.scheduler.deferEntity = true;

        f.tick(Duration.ofMinutes(240));
        f.config = EnforceFixture.parse(EnforceFixture.DISABLED);
        f.scheduler.runEntity();

        assertTrue(f.kicks.isEmpty());
        assertEquals(0L, f.storage.cooldownExpiresMillis(f.id()));
        assertEquals(session, f.tracker.session(f.id()), "nothing reset while disabled");

        f.config = EnforceFixture.parse(EnforceFixture.ENABLED);
        f.scheduler.deferEntity = false;
        f.tick(Duration.ofSeconds(1));
        assertEquals(1, f.kicks.size(), "a claim kept after the disable would never kick again");
    }

    @Test
    @DisplayName("a new connection whose stored window is over the limit is kicked on its first tick")
    void storedWindowOverLimitKickedOnFirstTick() {
        EnforceFixture f = enabled();
        f.storage.save(f.id(), new SessionSnapshot("Ada", 300 * 60L, f.clock.wallMillis,
                250 * 60L, f.clock.wallMillis));
        f.join();

        f.tick(Duration.ofSeconds(1));

        assertEquals(1, f.kicks.size());
    }

    @Test
    @DisplayName("a tick between the kick and its quit claims nothing and sends no milestone")
    void tickBetweenKickAndQuitIsQuiet() {
        EnforceFixture f = new EnforceFixture(dir, EnforceFixture.ENABLED
                .replace("  milestones: []\n", """
                          milestones:
                            - minute: 1
                              message: "<green>one</green>"
                          overtime:
                            enabled: true
                            after-minutes: 1
                            every-minutes: 1
                            message: "<red>over</red>"
                        """));
        f.join();
        f.tick(Duration.ofMinutes(240));
        assertEquals(1, f.kicks.size());
        int sent = f.audience.calls().size();
        assertTrue(sent >= 2, "the minute-1 milestone and minute-240 overtime were delivered");
        int tasks = f.scheduler.entityTargets.size();

        f.tick(Duration.ofSeconds(59));

        assertEquals(tasks, f.scheduler.entityTargets.size(), "the quit has not landed yet");
        assertEquals(sent, f.audience.calls().size());
        assertEquals(1, f.kicks.size());
    }

    @Test
    @DisplayName("the kick ends the window: back after the cooldown, a fresh allowance, no re-kick")
    void rejoinAfterCooldownStartsFresh() {
        EnforceFixture f = enabled();
        PlayerSession first = f.join();
        f.tick(Duration.ofMinutes(240));
        f.quit();
        f.scheduler.runOnce();

        f.clock.advanceWallOnly(Duration.ofMinutes(31));
        assertEquals(org.bukkit.event.player.AsyncPlayerPreLoginEvent.Result.ALLOWED,
                f.preLogin().getLoginResult());
        PlayerSession second = f.join();
        f.tick(Duration.ofSeconds(1));
        f.tick(Duration.ofMinutes(239));

        assertNotSame(first, second);
        assertEquals(List.of(EnforceFixture.KICK_AT_240),
                f.kicks.stream().map(EnforceFixture.Kick::message).toList(),
                "31 minutes offline is well inside window-reset-hours; without the reset the "
                        + "first tick after the rejoin would kick again");
        assertEquals(240 * 60L + 239 * 60L + 1L, second.lifetimeSeconds());
    }
}
