package com.ninja6.sessionpulse.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ninja6.sessionpulse.config.PluginConfig;
import com.ninja6.sessionpulse.platform.RecordingScheduler;
import com.ninja6.sessionpulse.platform.Scheduler;
import com.ninja6.sessionpulse.session.SessionSnapshot;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import org.bukkit.entity.Entity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The disk is written by scheduled tasks, at the configured interval, and never by the
 * thread that made the change.
 *
 * <p>These cover "never on the main thread" for the periodic, quit and cooldown flushes.
 * The read at enable and the final write at disable are on the main thread by design.
 */
class FlushSchedulingTest {

    @TempDir
    Path dir;

    private final UUID uuid = UUID.fromString("6ba7b810-9dad-41d1-80b4-00c04fd430c8");
    private final RecordingScheduler scheduler = new RecordingScheduler();
    private final StorageFixture.Log log = new StorageFixture.Log();
    private final AtomicInteger writes = new AtomicInteger();
    private PluginConfig config = StorageFixture.defaults();

    private Path file() {
        return dir.resolve("data.yml");
    }

    private YamlDataStorage store(YamlDataStorage.DataFileWriter writer) {
        YamlDataStorage store = new YamlDataStorage(file(), scheduler, () -> config, log.logger,
                (target, text) -> {
                    writes.incrementAndGet();
                    writer.write(target, text);
                });
        store.loadFromDisk();
        return store;
    }

    private YamlDataStorage store() {
        return store(YamlDataStorage::writeAtomically);
    }

    private SessionSnapshot session(long seconds) {
        return new SessionSnapshot("Ada", seconds, StorageFixture.WALL, seconds,
                StorageFixture.WALL);
    }

    @Test
    @DisplayName("the periodic flush is one async repeating task at the configured interval")
    void periodicFlushIsOneAsyncRepeatingTaskAtTheConfiguredInterval() {
        config = StorageFixture.config("tracking:\n  flush-interval-minutes: 2\n");
        store().startFlushing(Map::of);

        assertEquals(1, scheduler.scheduled.size());
        RecordingScheduler.Recorded flush = scheduler.scheduled.get(0);
        assertTrue(flush.async, "a disk write must never be scheduled on a region thread");
        assertEquals(2_400L, flush.delayTicks, "two minutes is 2400 ticks");
        assertEquals(2_400L, flush.periodTicks);
    }

    @Test
    @DisplayName("rescheduling cancels the old handle and reads the interval again")
    void rescheduleCancelsTheOldHandleAndReadsTheSupplierAgain() {
        YamlDataStorage store = store();
        store.startFlushing(Map::of);
        RecordingScheduler.Recorded old = scheduler.scheduled.get(0);

        config = StorageFixture.config("tracking:\n  flush-interval-minutes: 7\n");
        store.rescheduleFlush();

        assertTrue(old.isCancelled(), "the previous flush is stopped by its own handle");
        assertEquals(2, scheduler.scheduled.size());
        assertEquals(8_400L, scheduler.scheduled.get(1).periodTicks,
                "the new interval is read from the supplier, not captured at construction");
        assertFalse(scheduler.scheduled.get(1).isCancelled());
    }

    @Test
    @DisplayName("no writer touches the disk on the calling thread")
    void noWriterTouchesTheDiskOnTheCallingThread() {
        YamlDataStorage store = store();

        store.save(uuid, session(10L));
        store.setCooldown(uuid, "Ada", StorageFixture.WALL + 1L);
        store.flushAsync();

        assertEquals(0, writes.get(), "save, setCooldown and flushAsync only queue work");
        assertFalse(Files.exists(file()));

        scheduler.runOnce();
        assertEquals(1, writes.get(), "the queued flush writes exactly once");
        assertTrue(Files.exists(file()));
    }

    @Test
    @DisplayName("a failed write is retried at the next flush, not dropped")
    void aFailedWriteIsRetriedNotDropped() {
        AtomicInteger attempts = new AtomicInteger();
        YamlDataStorage store = store((target, text) -> {
            if (attempts.getAndIncrement() == 0) {
                throw new IOException("disk full");
            }
            YamlDataStorage.writeAtomically(target, text);
        });
        store.startFlushing(Map::of);
        store.save(uuid, session(30L));

        scheduler.tick();
        assertTrue(log.has(Level.SEVERE, "Failed to save", file().toString()));
        assertFalse(Files.exists(file()));

        scheduler.tick();
        assertEquals(30L, StorageFixture.restart(file()).load(uuid).lifetimeSeconds(),
                "the failed write left the store dirty, so the next tick wrote it");
    }

    @Test
    @DisplayName("shutdown writes synchronously, cancels the flush, and closes the store")
    void shutdownWritesSynchronouslyCancelsAndCloses() throws IOException {
        YamlDataStorage store = store();
        store.startFlushing(Map::of);
        store.save(uuid, session(40L));
        store.flushAsync();

        store.shutdown();

        assertTrue(Files.exists(file()), "written before shutdown returned");
        assertEquals(40L, StorageFixture.restart(file()).load(uuid).lifetimeSeconds());
        assertTrue(scheduler.scheduled.get(0).isCancelled());

        Files.delete(file());
        store.save(uuid, session(50L));
        store.setCooldown(uuid, "Ada", 1L);
        scheduler.runOnce();
        scheduler.scheduled.get(0).body.run();
        store.shutdown();
        assertFalse(Files.exists(file()), "a closed store writes nothing, from any path");
    }

    @Test
    @DisplayName("rescheduling after shutdown schedules nothing")
    void rescheduleAfterShutdownSchedulesNothing() {
        YamlDataStorage store = store();
        store.startFlushing(Map::of);
        store.shutdown();

        store.rescheduleFlush();
        store.startFlushing(Map::of);

        assertEquals(1, scheduler.scheduled.size(),
                "a reload racing a disable must not leave a live periodic task behind");
        assertTrue(scheduler.scheduled.get(0).isCancelled());
    }

    @Test
    @DisplayName("shutdown writes even when nothing is dirty, so the file exists after a clean stop")
    void shutdownWritesAnEmptyStore() {
        store().shutdown();

        assertTrue(Files.exists(file()));
        assertEquals(1, StorageFixture.parse(file()).getInt("schema-version"));
    }

    @Test
    @DisplayName("a throwing heartbeat still lets pending changes reach the disk")
    void aThrowingHeartbeatDoesNotKillTheRepeatingTask() {
        YamlDataStorage store = store();
        store.save(uuid, session(60L));
        store.startFlushing(() -> {
            throw new IllegalStateException("heartbeat failed");
        });

        scheduler.tick();

        assertEquals(1, writes.get(), "the write is a separate step from the heartbeat");
        assertTrue(log.has(Level.SEVERE, "online players"));
        assertEquals(60L, StorageFixture.restart(file()).load(uuid).lifetimeSeconds());
    }

    @Test
    @DisplayName("a scheduler that refuses the one-shot does not wedge later flush requests")
    void aRefusedOneShotDoesNotWedgeLaterRequests() {
        RecordingScheduler refusing = new RecordingScheduler();
        AtomicInteger offered = new AtomicInteger();
        Scheduler firstRefused = new Scheduler() {
            @Override
            public Task globalRepeating(Runnable task, long delayTicks, long periodTicks) {
                return refusing.globalRepeating(task, delayTicks, periodTicks);
            }

            @Override
            public void entity(Entity entity, Runnable task, Runnable retired) {
                refusing.entity(entity, task, retired);
            }

            @Override
            public Task async(Runnable task, long delayTicks, long periodTicks) {
                return refusing.async(task, delayTicks, periodTicks);
            }

            @Override
            public void asyncOnce(Runnable task) {
                if (offered.getAndIncrement() == 0) {
                    throw new IllegalStateException("plugin disabled");
                }
                refusing.asyncOnce(task);
            }
        };
        YamlDataStorage store = new YamlDataStorage(file(), firstRefused,
                StorageFixture::defaults, log.logger);
        store.loadFromDisk();

        store.flushAsync();
        store.flushAsync();

        assertEquals(1, refusing.once.size(), "the refusal cleared the pending flag");
        assertTrue(log.has(Level.SEVERE, "could not schedule"));
    }
}
