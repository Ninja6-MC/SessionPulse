package com.ninja6.sessionpulse.afk;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ninja6.sessionpulse.config.PluginConfig;
import com.ninja6.sessionpulse.platform.RecordingScheduler;
import com.ninja6.sessionpulse.platform.Scheduler;
import java.util.UUID;
import java.util.logging.Level;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link EssentialsAfkDetector} when EssentialsX misbehaves: nothing reaches the tick, a broken
 * binding is reported once and stops the detector, a transient failure does not, and refreshes
 * never pile up.
 */
class EssentialsHookVanishesTest {

    private final RecordingScheduler scheduler = new RecordingScheduler();
    private final AfkFixture.Clock clock = new AfkFixture.Clock();
    private final AfkFixture.Log log = new AfkFixture.Log();
    private final int[] violations = new int[1];
    private final PluginConfig config = AfkFixture.config("ESSENTIALS", 300);
    private final BuiltInAfkDetector builtIn = new BuiltInAfkDetector(clock, () -> config);
    private final FakeEssentials fake = new FakeEssentials(scheduler, 300);
    private final UUID uuid = UUID.randomUUID();
    private final boolean[] valid = {true};
    private final Player player =
            AfkFixture.player(uuid, "Ada", false, scheduler, violations, valid);

    @AfterEach
    void essentialsWasOnlyCalledOnTheRegion() {
        assertEquals(0, fake.offRegionCalls, "EssentialsX was called from the tick");
        assertEquals(0, violations[0], "a permission was read from the tick");
    }

    private EssentialsAfkDetector bind(Scheduler on) throws ReflectiveOperationException {
        return new EssentialsAfkDetector(EssentialsAfkDetector.Hook.bind(fake), null, on, log.logger);
    }

    /** One tick and its region task. */
    private boolean tick(EssentialsAfkDetector detector) {
        boolean afk = detector.isAfk(player);
        scheduler.runEntity();
        return afk;
    }

    @Test
    @DisplayName("the verdict is read on the region and reaches the tick one tick later")
    void theVerdictComesFromTheRegion() throws Exception {
        EssentialsAfkDetector detector = bind(scheduler);
        fake.user(uuid).afk = true;

        assertFalse(detector.isAfk(player), "nothing is cached before the first refresh");
        assertTrue(detector.isAfk(player), "the inline refresh cached it for the next tick");
    }

    @Test
    @DisplayName("isAfk throwing NoClassDefFoundError: never thrown to the tick, warned once, nothing more scheduled")
    void isAfkLinkageErrorKillsTheDetectorQuietly() throws Exception {
        scheduler.deferEntity = true;
        EssentialsAfkDetector detector = bind(scheduler);
        fake.user(uuid).afk = true;
        tick(detector);
        fake.user(uuid).isAfkThrows = new NoClassDefFoundError("net/ess3/api/IUser");

        for (int i = 0; i < 20; i++) {
            assertDoesNotThrow(() -> tick(detector));
        }

        assertTrue(detector.isDead());
        assertFalse(detector.isAfk(player), "a dead detector in ESSENTIALS pauses nobody");
        assertEquals(1, log.at(Level.WARNING).size(), "reported once, not once a second");
        int scheduled = scheduler.entityTargets.size();
        tick(detector);
        assertEquals(scheduled, scheduler.entityTargets.size(), "a dead detector schedules nothing");
    }

    @Test
    @DisplayName("getUser throwing NoClassDefFoundError: the same")
    void getUserLinkageErrorKillsTheDetectorQuietly() throws Exception {
        scheduler.deferEntity = true;
        EssentialsAfkDetector detector = bind(scheduler);
        fake.getUserThrows = new NoClassDefFoundError("com/earth2me/essentials/User");

        for (int i = 0; i < 20; i++) {
            assertDoesNotThrow(() -> tick(detector));
        }

        assertTrue(detector.isDead());
        assertEquals(1, log.at(Level.WARNING).size());
        assertEquals(1, fake.getUserCalls, "the first failure stopped every later call");
    }

    @Test
    @DisplayName("a null user is not AFK, does not kill the detector, and a later answer still arrives")
    void aNullUserIsTransient() throws Exception {
        scheduler.deferEntity = true;
        EssentialsAfkDetector detector = bind(scheduler);
        fake.nullUser = true;
        fake.user(uuid).afk = true;

        tick(detector);
        assertFalse(tick(detector), "EssentialsX returns null while starting or stopping");
        assertFalse(detector.isDead());
        assertTrue(log.records.isEmpty(), "a null user is not worth a line");

        fake.nullUser = false;
        tick(detector);
        assertTrue(tick(detector));
    }

    @Test
    @DisplayName("a RuntimeException inside EssentialsX costs that refresh only, and is reported once")
    void aRuntimeExceptionIsTransient() throws Exception {
        scheduler.deferEntity = true;
        EssentialsAfkDetector detector = bind(scheduler);
        fake.user(uuid).afk = true;
        fake.user(uuid).isAfkThrows = new IllegalStateException("user map not ready");

        for (int i = 0; i < 5; i++) {
            assertFalse(tick(detector));
        }
        assertFalse(detector.isDead());
        assertEquals(1, log.at(Level.WARNING).size());

        fake.user(uuid).isAfkThrows = null;
        tick(detector);
        assertTrue(tick(detector));
    }

    @Test
    @DisplayName("a refresh already queued is not queued again")
    void refreshesAreNotStacked() throws Exception {
        scheduler.deferEntity = true;
        EssentialsAfkDetector detector = bind(scheduler);

        for (int i = 0; i < 5; i++) {
            detector.isAfk(player);
        }
        assertEquals(1, scheduler.entityTargets.size(), "five ticks with a refresh pending, one task");

        scheduler.runEntity();
        detector.isAfk(player);
        assertEquals(2, scheduler.entityTargets.size(), "a completed refresh frees the next one");
    }

    @Test
    @DisplayName("a retired refresh frees the next one")
    void aRetiredRefreshFreesTheNext() throws Exception {
        scheduler.deferEntity = true;
        EssentialsAfkDetector detector = bind(scheduler);

        detector.isAfk(player);
        scheduler.retireEntity();
        detector.isAfk(player);

        assertEquals(2, scheduler.entityTargets.size());
    }

    @Test
    @DisplayName("forget drops the cached verdict and leaves a queued refresh as the only one")
    void forgetClearsThePlayer() throws Exception {
        scheduler.deferEntity = true;
        EssentialsAfkDetector detector = bind(scheduler);
        fake.user(uuid).afk = true;
        tick(detector);

        detector.isAfk(player);
        detector.forget(uuid);

        assertFalse(detector.isAfk(player), "the cached verdict went with the player");
        assertEquals(2, scheduler.entityTargets.size(),
                "the refresh queued before forget is still the one queued; no second joins it");
    }

    @Test
    @DisplayName("a refresh that runs after its player left caches nothing")
    void aRefreshForADepartedPlayerCachesNothing() throws Exception {
        scheduler.deferEntity = true;
        EssentialsAfkDetector detector = bind(scheduler);
        fake.user(uuid).afk = true;

        detector.isAfk(player);
        detector.forget(uuid);
        valid[0] = false;
        scheduler.runEntity();

        assertFalse(detector.isAfk(player), "the departed player was cached again");
        assertEquals(0, fake.getUserCalls, "EssentialsX was not asked about a departed player");
    }

    @Test
    @DisplayName("a ClassCastException from inside EssentialsX is transient, not a broken binding")
    void aWrappedClassCastIsTransient() throws Exception {
        scheduler.deferEntity = true;
        EssentialsAfkDetector detector = bind(scheduler);
        fake.user(uuid).afk = true;
        fake.getUserThrows = new ClassCastException("one player's userdata");

        for (int i = 0; i < 5; i++) {
            assertFalse(tick(detector));
        }
        assertFalse(detector.isDead(), "one player's bad data must not stop detection for everyone");
        assertEquals(1, log.at(Level.WARNING).size());

        fake.getUserThrows = null;
        tick(detector);
        assertTrue(tick(detector));
    }

    @Test
    @DisplayName("a result of the wrong type is a broken binding")
    void aDirectCastFailureKillsTheDetector() throws Exception {
        scheduler.deferEntity = true;
        EssentialsAfkDetector.Hook hook = EssentialsAfkDetector.Hook.bind(fake);
        EssentialsAfkDetector detector = new EssentialsAfkDetector(
                new EssentialsAfkDetector.Hook(fake, hook.getUser,
                        FakeEssentials.User.class.getMethod("toString"), 300L),
                null, scheduler, log.logger);

        tick(detector);
        tick(detector);

        assertTrue(detector.isDead(), "a String where a boolean belongs repeats on every call");
        assertEquals(1, log.at(Level.WARNING).size());
    }

    @Test
    @DisplayName("a scheduler that throws does not leave the player pending for ever")
    void aThrowingSchedulerDoesNotStrandThePlayer() throws Exception {
        int[] attempts = new int[1];
        Scheduler failingOnce = new Scheduler() {
            @Override
            public Task globalRepeating(Runnable task, long delayTicks, long periodTicks) {
                throw new UnsupportedOperationException();
            }

            @Override
            public void entity(Entity entity, Runnable task, Runnable retired) {
                if (attempts[0]++ == 0) {
                    throw new IllegalStateException("plugin disabling");
                }
                scheduler.entity(entity, task, retired);
            }

            @Override
            public Task async(Runnable task, long delayTicks, long periodTicks) {
                throw new UnsupportedOperationException();
            }

            @Override
            public void asyncOnce(Runnable task) {
                throw new UnsupportedOperationException();
            }
        };
        EssentialsAfkDetector detector = bind(failingOnce);
        fake.user(uuid).afk = true;

        assertDoesNotThrow(() -> detector.isAfk(player));
        detector.isAfk(player);
        assertTrue(detector.isAfk(player), "the second attempt was scheduled and ran");
        assertEquals(1, log.at(Level.WARNING).size());
    }
}
