package com.ninja6.sessionpulse.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.fail;

import com.ninja6.sessionpulse.platform.RecordingScheduler;
import com.ninja6.sessionpulse.session.SessionSnapshot;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Flushes reach the disk in the order they serialised.
 *
 * <p>Deterministic rather than probabilistic: the first write parks inside the writer seam,
 * after it has serialised and before it has touched the disk, and the test decides exactly
 * what happens while it is parked. Without that, a test that runs one flush to completion
 * and then another passes under every ordering and proves nothing.
 */
class FlushOrderingTest {

    private static final long TIMEOUT_SECONDS = 10L;
    private static final long COOLDOWN = StorageFixture.WALL + 3_600_000L;

    @TempDir
    Path dir;

    private final UUID uuid = UUID.fromString("6ba7b810-9dad-41d1-80b4-00c04fd430c8");
    private final RecordingScheduler scheduler = new RecordingScheduler();

    /** Parks the first write before it reaches the disk; later writes go straight through. */
    private final class ParkingWriter implements YamlDataStorage.DataFileWriter {

        final CountDownLatch parked = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final AtomicInteger calls = new AtomicInteger();
        volatile boolean timedOut;

        @Override
        public void write(Path target, String text) throws IOException {
            if (calls.getAndIncrement() == 0) {
                parked.countDown();
                try {
                    if (!release.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                        timedOut = true;
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    timedOut = true;
                }
            }
            YamlDataStorage.writeAtomically(target, text);
        }
    }

    private Path file() {
        return dir.resolve("data.yml");
    }

    private YamlDataStorage store(YamlDataStorage.DataFileWriter writer) {
        YamlDataStorage store = new YamlDataStorage(file(), scheduler,
                StorageFixture::defaults, new StorageFixture.Log().logger, writer);
        store.loadFromDisk();
        store.save(uuid, new SessionSnapshot("Ada", 60L, StorageFixture.WALL, 60L,
                StorageFixture.WALL));
        return store;
    }

    private static void awaitOrFail(CountDownLatch latch, String what) throws InterruptedException {
        if (!latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            fail("timed out waiting for " + what);
        }
    }

    /**
     * Waits until {@code second} has either finished or is blocked on a lock {@code first}
     * holds. Checking the lock owner, not merely the BLOCKED state, keeps a thread that is
     * briefly blocked on something unrelated from being taken for one queued behind the
     * disk write.
     */
    private static void awaitQueuedOrDone(Thread second, Thread first) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
        while (System.nanoTime() < deadline) {
            if (second.getState() == Thread.State.TERMINATED) {
                return;
            }
            ThreadInfo info = ManagementFactory.getThreadMXBean().getThreadInfo(second.threadId());
            if (info != null && info.getThreadState() == Thread.State.BLOCKED
                    && info.getLockOwnerId() == first.threadId()) {
                return;
            }
            Thread.sleep(1L);
        }
        fail("the second flush neither finished nor queued behind the first");
    }

    private void runRace(YamlDataStorage store, ParkingWriter writer, Runnable second)
            throws InterruptedException {
        Thread older = new Thread(store::writeNow, "older-flush");
        older.start();
        awaitOrFail(writer.parked, "the first flush to park");

        // Lands after the older flush serialised: only a flush that serialises later has it.
        store.setCooldown(uuid, "Ada", COOLDOWN);

        Thread newer = new Thread(second, "newer-flush");
        newer.start();
        awaitQueuedOrDone(newer, older);

        writer.release.countDown();
        older.join(TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS));
        newer.join(TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS));
        assertFalse(older.isAlive() || newer.isAlive(), "a flush did not finish");
        assertFalse(writer.timedOut, "the parked flush was never released");
    }

    @Test
    @DisplayName("a newer flush cannot reach the disk before an older one")
    void aSecondSaverCannotLandBeforeAnOlderOne() throws InterruptedException {
        ParkingWriter writer = new ParkingWriter();
        YamlDataStorage store = store(writer);

        runRace(store, writer, store::writeNow);

        assertEquals(COOLDOWN, StorageFixture.restart(file()).cooldownExpiresMillis(uuid),
                "the older file landed last and replaced the one carrying the cooldown, and "
                        + "nothing is marked dirty to repair it");
    }

    @Test
    @DisplayName("shutdown's write cannot be overwritten by an async flush already in flight")
    void shutdownCannotBeOverwrittenByAnInFlightAsyncSave() throws InterruptedException {
        ParkingWriter writer = new ParkingWriter();
        YamlDataStorage store = store(writer);

        runRace(store, writer, store::shutdown);

        assertEquals(COOLDOWN, StorageFixture.restart(file()).cooldownExpiresMillis(uuid),
                "cancelling the scheduler does not stop a flush that has begun; if it lands "
                        + "after shutdown's write, a clean stop loses the last change");
    }

    @Test
    @DisplayName("a cooldown written during a flush gets a flush of its own")
    void aCooldownWrittenDuringASaveGetsItsOwnFlush() {
        AtomicInteger calls = new AtomicInteger();
        YamlDataStorage[] holder = new YamlDataStorage[1];
        YamlDataStorage store = store((target, text) -> {
            if (calls.getAndIncrement() == 0) {
                // After serialising, outside the YAML guard: exactly where a real write races.
                holder[0].setCooldown(uuid, "Ada", COOLDOWN);
            }
            YamlDataStorage.writeAtomically(target, text);
        });
        holder[0] = store;

        store.flushAsync();
        assertEquals(1, scheduler.runOnce(), "the first request queued one flush");
        assertEquals(1, scheduler.once.size(),
                "the cooldown landed after the running flush serialised, so it must have "
                        + "queued another; had the pending flag been cleared after the write, "
                        + "the request would have been folded into a write that lacks it");
        assertEquals(0L, StorageFixture.restart(file()).cooldownExpiresMillis(uuid),
                "the first flush serialised before the cooldown existed");

        scheduler.runOnce();
        assertEquals(COOLDOWN, StorageFixture.restart(file()).cooldownExpiresMillis(uuid));
    }

    @Test
    @DisplayName("repeated flush requests coalesce into one queued task")
    void repeatedFlushRequestsCoalesceToOneQueuedTask() {
        YamlDataStorage store = store(YamlDataStorage::writeAtomically);

        for (int i = 0; i < 50; i++) {
            store.flushAsync();
        }
        assertEquals(1, scheduler.once.size());

        scheduler.runOnce();
        store.flushAsync();
        assertEquals(1, scheduler.once.size(),
                "once the queued flush has run, a new request queues again");
    }
}
