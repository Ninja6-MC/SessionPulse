package com.ninja6.sessionpulse.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ninja6.sessionpulse.platform.RecordingScheduler;
import com.ninja6.sessionpulse.session.SessionSnapshot;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The in-memory side: lookups, and each writer leaving the other's fields alone.
 */
class RecordReadersTest {

    @TempDir
    Path dir;

    private final UUID ada = UUID.fromString("6ba7b810-9dad-41d1-80b4-00c04fd430c8");
    private final UUID other = UUID.fromString("6ba7b811-9dad-41d1-80b4-00c04fd430c8");

    private YamlDataStorage store() {
        YamlDataStorage store = StorageFixture.store(dir.resolve("data.yml"),
                new RecordingScheduler(), new StorageFixture.Log());
        store.loadFromDisk();
        return store;
    }

    @Test
    @DisplayName("a player storage has never seen is unknown, with no cooldown")
    void anUnseenPlayerIsUnknown() {
        YamlDataStorage store = store();

        assertEquals(SessionSnapshot.UNKNOWN, store.load(ada));
        assertEquals(0L, store.cooldownExpiresMillis(ada));
        assertNull(store.findByName("Ada"));
    }

    @Test
    @DisplayName("a session save keeps the cooldown, and a cooldown keeps the session")
    void eachWriterLeavesTheOthersFieldsAlone() {
        YamlDataStorage store = store();
        SessionSnapshot session = new SessionSnapshot("Ada", 100L, StorageFixture.WALL, 50L,
                StorageFixture.WALL);

        store.setCooldown(ada, null, StorageFixture.WALL + 9L);
        store.save(ada, session);
        assertEquals(StorageFixture.WALL + 9L, store.cooldownExpiresMillis(ada));

        store.setCooldown(ada, null, StorageFixture.WALL + 10L);
        assertEquals(session, store.load(ada),
                "a null name keeps the one on record, and the counted state is untouched");
    }

    @Test
    @DisplayName("findByName is case-insensitive and prefers the most recently seen match")
    void findByNameIsCaseInsensitiveAndPrefersTheMostRecentlySeen() {
        YamlDataStorage store = store();
        store.save(ada, new SessionSnapshot("Ada", 1L, 1L, 1L, StorageFixture.WALL));
        store.save(other, new SessionSnapshot("ADA", 1L, 1L, 1L, StorageFixture.WALL + 1L));

        assertEquals(other, store.findByName("ada"),
                "both carried the name at some point; the newer holder is the one meant");

        store.save(ada, new SessionSnapshot("Ada", 1L, 1L, 1L, StorageFixture.WALL + 2L));
        assertEquals(ada, store.findByName("aDa"));
    }

    @Test
    @DisplayName("records() is an immutable copy")
    void recordsIsAnImmutableCopy() {
        YamlDataStorage store = store();
        store.setCooldown(ada, "Ada", 5L);

        Map<UUID, StoredPlayer> copy = store.records();
        store.setCooldown(other, "Grace", 6L);

        assertEquals(1, copy.size(), "a later write does not show through");
        assertThrows(UnsupportedOperationException.class, () -> copy.remove(ada));
        assertTrue(copy.get(ada).toSnapshot().isUnknown(),
                "a record made by a cooldown alone has no counted state");
    }
}
