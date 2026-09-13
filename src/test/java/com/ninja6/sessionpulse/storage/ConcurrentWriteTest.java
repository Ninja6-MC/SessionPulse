package com.ninja6.sessionpulse.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ninja6.sessionpulse.platform.RecordingScheduler;
import com.ninja6.sessionpulse.session.SessionSnapshot;
import java.nio.file.Path;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A cooldown and a session save on the same player, from two threads, never erase each
 * other.
 *
 * <p>In production the global region writes the cooldown while the player's region thread
 * saves their session. A read-then-put on the record map lets the save read the record
 * before the cooldown lands and put it back without it - and the player the plugin just
 * kicked rejoins. Probabilistic by nature: both threads start on one latch and then run
 * free, so the interleavings come from the scheduler rather than from a rendezvous that
 * would serialise them.
 */
class ConcurrentWriteTest {

    private static final int ITERATIONS = 1_000_000;
    private static final long BASE = StorageFixture.WALL;

    @TempDir
    Path dir;

    @Test
    @DisplayName("cooldown and session writes on one UUID never lose each other")
    void cooldownAndSessionWritesOnOneUuidNeverLoseEachOther() throws InterruptedException {
        Path file = dir.resolve("data.yml");
        YamlDataStorage store = StorageFixture.store(file, new RecordingScheduler(),
                new StorageFixture.Log());
        store.loadFromDisk();
        UUID uuid = UUID.randomUUID();

        CountDownLatch start = new CountDownLatch(1);
        Queue<String> violations = new ConcurrentLinkedQueue<>();

        Thread cooldowns = new Thread(() -> {
            await(start);
            for (int i = 0; i < ITERATIONS && violations.isEmpty(); i++) {
                store.setCooldown(uuid, "Ada", BASE + i);
                long seen = store.cooldownExpiresMillis(uuid);
                if (seen < BASE + i) {
                    violations.add("cooldown " + (BASE + i) + " was overwritten by " + seen);
                }
            }
        }, "cooldown-writer");

        Thread sessions = new Thread(() -> {
            await(start);
            for (int i = 0; i < ITERATIONS && violations.isEmpty(); i++) {
                store.save(uuid, new SessionSnapshot("Ada", i, BASE, i, BASE + i));
                long seen = store.load(uuid).lifetimeSeconds();
                if (seen < i) {
                    violations.add("lifetime " + i + " was overwritten by " + seen);
                }
            }
        }, "session-writer");

        cooldowns.start();
        sessions.start();
        start.countDown();
        cooldowns.join(TimeUnit.SECONDS.toMillis(120));
        sessions.join(TimeUnit.SECONDS.toMillis(120));

        assertFalse(cooldowns.isAlive() || sessions.isAlive(), "a writer did not finish");
        assertTrue(violations.isEmpty(), "a write from one thread erased the other's: "
                + violations.peek());

        long last = ITERATIONS - 1;
        assertEquals(BASE + last, store.cooldownExpiresMillis(uuid));
        assertEquals(last, store.load(uuid).lifetimeSeconds());
        assertEquals(BASE + last, store.load(uuid).lastSeenMillis());

        // The document must agree with the map: each writer owns its own keys in the file too.
        store.writeNow();
        YamlDataStorage restarted = StorageFixture.restart(file);
        assertEquals(BASE + last, restarted.cooldownExpiresMillis(uuid));
        assertEquals(last, restarted.load(uuid).lifetimeSeconds());
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
