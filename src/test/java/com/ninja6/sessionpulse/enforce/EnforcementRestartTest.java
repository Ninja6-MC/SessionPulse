package com.ninja6.sessionpulse.enforce;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ninja6.sessionpulse.config.PluginConfig;
import com.ninja6.sessionpulse.notify.TestNotifiers;
import com.ninja6.sessionpulse.platform.Scheduler;
import com.ninja6.sessionpulse.session.PlayerSession;
import com.ninja6.sessionpulse.session.SessionSnapshot;
import com.ninja6.sessionpulse.session.SessionStore;
import com.ninja6.sessionpulse.session.SessionTracker;
import com.ninja6.sessionpulse.storage.YamlDataStorage;
import java.io.IOException;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
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
        assertEquals("§eBreak Ada 4.0 240 30", refused.getKickMessage(),
                "hours and minutes are the limit reached, not the reset window's zero");

        next.clock.advanceWallOnly(Duration.ofMinutes(10));
        assertEquals("§eBreak Ada 4.0 240 20", next.preLogin().getKickMessage(),
                "twenty minutes left");

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
    @DisplayName("crash after the kick with only the one-shot flush run: it carried both")
    void crashAfterCooldownFlush() {
        EnforceFixture f = kicked();
        assertEquals(1, f.scheduler.runOnce(),
                "the cooldown's and the checkpoint's requests collapse into one queued flush");
        // No quit, no shutdown: the process is gone.

        assertHeldOutThenFresh(f.restart());
    }

    @Test
    @DisplayName("a flush after any save: no write the disk sees has a zeroed window without its cooldown")
    void flushAfterEverySaveNeverStoresZeroWindowWithoutCooldown() {
        diskVersionsThroughOneKick(true);
    }

    @Test
    @DisplayName("the cooldown's flush already ran: the zeroed window still reaches disk before the kick")
    void zeroedWindowFlushedEvenAfterCooldownFlushRan() {
        diskVersionsThroughOneKick(false);
    }

    /**
     * Drives one kick with every one-shot flush run inline, and checks every version of the
     * file that resulted. With {@code flushOnEverySave} a flush also follows each tracker save,
     * standing in for a periodic flush landing at the worst moment; without it the cooldown's
     * own flush has already run before the checkpoint, so only the explicit request can carry
     * the zeroed window.
     */
    private void diskVersionsThroughOneKick(boolean flushOnEverySave) {
        List<String> versions = new ArrayList<>();
        Path file = dir.resolve("data.yml");
        EnforceFixture.Clock clock = new EnforceFixture.Clock();
        PluginConfig config = EnforceFixture.parse(EnforceFixture.ENABLED);
        // Every one-shot runs at once and every result is kept, so each flush the region task
        // provokes is a separate crash point.
        Scheduler inline = new Scheduler() {
            @Override
            public Task globalRepeating(Runnable task, long delayTicks, long periodTicks) {
                throw new UnsupportedOperationException();
            }

            @Override
            public void entity(Entity entity, Runnable task, Runnable retired) {
                task.run();
            }

            @Override
            public Task async(Runnable task, long delayTicks, long periodTicks) {
                throw new UnsupportedOperationException();
            }

            @Override
            public void asyncOnce(Runnable task) {
                task.run();
                try {
                    versions.add(Files.exists(file) ? Files.readString(file) : "");
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
        };
        Logger logger = Logger.getAnonymousLogger();
        logger.setUseParentHandlers(false);
        YamlDataStorage storage = new YamlDataStorage(file, inline, () -> config, logger);
        storage.loadFromDisk();
        // A periodic flush after every save the tracker makes: the worst moment it could land.
        SessionStore flushingStore = new SessionStore() {
            @Override
            public SessionSnapshot load(UUID uuid) {
                return storage.load(uuid);
            }

            @Override
            public void save(UUID uuid, SessionSnapshot snapshot) {
                storage.save(uuid, snapshot);
                if (flushOnEverySave) {
                    storage.flushAsync();
                }
            }
        };
        SessionTracker tracker = new SessionTracker(() -> config, clock, flushingStore);
        EnforcementService service = new EnforcementService(tracker, inline,
                TestNotifiers.recording(() -> config, new TestNotifiers.RecordingAudience()),
                storage, clock, () -> config);
        UUID uuid = UUID.randomUUID();
        List<String> kicks = new ArrayList<>();
        Player player = (Player) Proxy.newProxyInstance(Player.class.getClassLoader(),
                new Class<?>[] {Player.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "hasPermission" -> false;
                    case "kickPlayer" -> kicks.add((String) args[0]);
                    case "getUniqueId" -> uuid;
                    default -> throw new UnsupportedOperationException(method.getName());
                });

        tracker.onJoin(uuid, "Ada");
        clock.advance(Duration.ofMinutes(239));
        tracker.checkpoint(tracker.accrue(uuid, false));
        clock.advance(Duration.ofMinutes(1));
        versions.clear();
        service.afterAccrual(player, tracker.accrue(uuid, false));

        assertEquals(1, kicks.size());
        assertFalse(versions.isEmpty());
        for (String version : versions) {
            YamlConfiguration yaml = YamlConfiguration.loadConfiguration(new StringReader(version));
            String path = "players." + uuid + ".";
            if (yaml.getLong(path + "window-seconds") == 0L) {
                assertTrue(yaml.getLong(path + "cooldown-expires") > 0L,
                        "a crash here restores a fresh allowance with no break:\n" + version);
            }
        }
        String last = versions.get(versions.size() - 1);
        YamlConfiguration finalYaml = YamlConfiguration.loadConfiguration(new StringReader(last));
        assertEquals(0L, finalYaml.getLong("players." + uuid + ".window-seconds"),
                "the zeroed window reaches the disk before the kick, not at the next flush");
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
