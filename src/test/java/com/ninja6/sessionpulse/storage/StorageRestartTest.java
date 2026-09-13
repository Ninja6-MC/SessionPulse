package com.ninja6.sessionpulse.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ninja6.sessionpulse.platform.RecordingScheduler;
import com.ninja6.sessionpulse.session.PlayerSession;
import com.ninja6.sessionpulse.session.SessionSnapshot;
import com.ninja6.sessionpulse.session.SessionTracker;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * State survives a restart.
 *
 * <p>A restart is modelled as a second store over the same file. That stand-in is only
 * honest because of {@link #negativeControl_unflushedRecordIsInvisibleToASecondInstance()}:
 * if a second instance could see a record the first never flushed - through a shared
 * static, say - every other test here would pass without the disk being involved at all.
 */
class StorageRestartTest {

    @TempDir
    Path dir;

    private final UUID uuid = UUID.fromString("0f8fad5b-d9cb-469f-a165-70867728950e");
    private final RecordingScheduler scheduler = new RecordingScheduler();
    private final StorageFixture.Log log = new StorageFixture.Log();

    private Path file() {
        return dir.resolve("data.yml");
    }

    private YamlDataStorage loaded() {
        YamlDataStorage store = StorageFixture.store(file(), scheduler, log);
        store.loadFromDisk();
        return store;
    }

    @Test
    @DisplayName("two instances over one file see each other's flushed records")
    void twoInstancesOverOneFileSeeEachOthersFlushedRecords() {
        SessionSnapshot written = new SessionSnapshot("Ada", 5_400L, StorageFixture.WALL,
                1_800L, StorageFixture.WALL + 60_000L);
        YamlDataStorage first = loaded();
        first.save(uuid, written);
        first.writeNow();

        YamlDataStorage second = StorageFixture.restart(file());
        assertEquals(written, second.load(uuid), "the second instance reads what the first wrote");

        RecordingScheduler secondScheduler = new RecordingScheduler();
        YamlDataStorage third = StorageFixture.store(file(), secondScheduler, log);
        third.loadFromDisk();
        third.setCooldown(uuid, null, StorageFixture.WALL + 3_600_000L);
        secondScheduler.runOnce();

        YamlDataStorage fourth = StorageFixture.restart(file());
        assertEquals(written, fourth.load(uuid),
                "a cooldown written by a later instance leaves the counted state alone");
        assertEquals(StorageFixture.WALL + 3_600_000L, fourth.cooldownExpiresMillis(uuid));
    }

    @Test
    @DisplayName("negative control: a record that was never flushed is invisible to a second instance")
    void negativeControl_unflushedRecordIsInvisibleToASecondInstance() {
        YamlDataStorage first = loaded();
        first.save(uuid, new SessionSnapshot("Ada", 60L, StorageFixture.WALL, 60L,
                StorageFixture.WALL));
        first.setCooldown(uuid, "Ada", StorageFixture.WALL + 1L);

        assertFalse(Files.exists(file()), "nothing has run a flush, so nothing is on disk");
        YamlDataStorage second = StorageFixture.restart(file());
        assertTrue(second.load(uuid).isUnknown(),
                "if this sees the record, the instances share memory and the restart tests "
                        + "in this class prove nothing about the file");
        assertEquals(0L, second.cooldownExpiresMillis(uuid));
    }

    @Test
    @DisplayName("a cooldown survives a crash through the forced flush alone")
    void aCooldownSurvivesACrashThroughTheForcedFlushAlone() {
        YamlDataStorage store = loaded();
        long expires = StorageFixture.WALL + Duration.ofHours(2).toMillis();

        store.setCooldown(uuid, "Ada", expires);
        assertTrue(scheduler.scheduled.isEmpty(), "no periodic flush exists in this test");
        assertEquals(1, scheduler.runOnce(), "setCooldown queued exactly one flush");
        // No tick, no shutdown: the process dies here.

        YamlDataStorage restarted = StorageFixture.restart(file());
        assertEquals(expires, restarted.cooldownExpiresMillis(uuid),
                "the cooldown must reach the disk without waiting for the periodic flush or a "
                        + "clean stop, or a crash lets the player straight back in");
    }

    @Test
    @DisplayName("the periodic flush alone carries online players, with a fresh last-seen")
    void thePeriodicFlushAloneCarriesOnlinePlayersWithAFreshLastSeen() {
        YamlDataStorage store = loaded();
        long[] now = {StorageFixture.WALL};
        store.startFlushing(() -> Map.of(uuid,
                new SessionSnapshot("Ada", 900L, StorageFixture.WALL, 900L, now[0])));

        scheduler.tick();
        now[0] += Duration.ofMinutes(5).toMillis();
        scheduler.tick();

        SessionSnapshot restarted = StorageFixture.restart(file()).load(uuid);
        assertEquals(StorageFixture.WALL + Duration.ofMinutes(5).toMillis(),
                restarted.lastSeenMillis(),
                "every periodic flush re-stamps last-seen for players still online; a stale "
                        + "stamp after a crash hands the player a fresh window");
        assertEquals(900L, restarted.windowSeconds());
    }

    @Test
    @DisplayName("through the tracker: the window resumes after a short gap and resets after a long one")
    void trackerRoundTrip_windowResumesAndLifetimeSurvives() {
        StorageFixture.Clock clock = new StorageFixture.Clock();
        YamlDataStorage store = loaded();
        SessionTracker tracker = new SessionTracker(StorageFixture::defaults, clock, store);

        tracker.onJoin(uuid, "Ada");
        clock.advance(Duration.ofMinutes(30).toMillis());
        tracker.onQuit(uuid);
        store.flushAsync();
        scheduler.runOnce();

        clock.advanceWallOnly(Duration.ofHours(1).toMillis());
        SessionTracker afterShortGap = new SessionTracker(StorageFixture::defaults, clock,
                StorageFixture.restart(file()));
        PlayerSession resumed = afterShortGap.onJoin(uuid, "Ada");
        assertEquals(1_800L, resumed.windowSeconds(), "an hour offline keeps the window");
        assertEquals(1_800L, resumed.lifetimeSeconds());

        clock.advanceWallOnly(Duration.ofHours(8).toMillis());
        SessionTracker afterLongGap = new SessionTracker(StorageFixture::defaults, clock,
                StorageFixture.restart(file()));
        PlayerSession reset = afterLongGap.onJoin(uuid, "Ada");
        assertEquals(0L, reset.windowSeconds(),
                "nine hours offline is past the default eight-hour threshold");
        assertEquals(1_800L, reset.lifetimeSeconds(), "lifetime survives a window reset");
    }
}
