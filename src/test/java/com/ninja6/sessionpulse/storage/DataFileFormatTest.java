package com.ninja6.sessionpulse.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ninja6.sessionpulse.platform.RecordingScheduler;
import com.ninja6.sessionpulse.session.SessionSnapshot;
import java.lang.reflect.RecordComponent;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code data.yml} has the documented layout, in both directions.
 *
 * <p>The file is read back here with a plain {@link YamlConfiguration}, not through the
 * store, so a layout the store writes and reads consistently but wrongly still fails.
 */
class DataFileFormatTest {

    private static final Set<String> SIX_KEYS = Set.of("name", "lifetime-seconds",
            "window-start", "window-seconds", "last-seen", "cooldown-expires");

    @TempDir
    Path dir;

    private final UUID counted = UUID.fromString("6ba7b810-9dad-41d1-80b4-00c04fd430c8");
    private final UUID cooledOnly = UUID.fromString("6ba7b811-9dad-41d1-80b4-00c04fd430c8");

    private Path file() {
        return dir.resolve("data.yml");
    }

    private YamlDataStorage loaded() {
        YamlDataStorage store = StorageFixture.store(file(), new RecordingScheduler(),
                new StorageFixture.Log());
        store.loadFromDisk();
        return store;
    }

    @Test
    @DisplayName("writes the documented layout: a schema version and six numeric-or-name keys per player")
    void writesTheDocumentedLayout() {
        YamlDataStorage store = loaded();
        store.save(counted, new SessionSnapshot("Ada", 7_200L, 1_700_000_000_000L, 3_600L,
                1_700_003_600_000L));
        store.setCooldown(counted, "Ada", 1_700_010_000_000L);
        store.setCooldown(cooledOnly, "Grace", 1_700_020_000_000L);
        store.writeNow();

        YamlConfiguration raw = StorageFixture.parse(file());
        assertEquals(1, raw.getInt("schema-version"));

        ConfigurationSection ada = raw.getConfigurationSection("players." + counted);
        assertEquals(SIX_KEYS, ada.getKeys(false));
        assertEquals("Ada", ada.getString("name"));
        assertLong(7_200L, ada, "lifetime-seconds");
        assertLong(1_700_000_000_000L, ada, "window-start");
        assertLong(3_600L, ada, "window-seconds");
        assertLong(1_700_003_600_000L, ada, "last-seen");
        assertLong(1_700_010_000_000L, ada, "cooldown-expires");

        ConfigurationSection grace = raw.getConfigurationSection("players." + cooledOnly);
        assertEquals(SIX_KEYS, grace.getKeys(false),
                "a record created by a cooldown alone still carries every key");
        assertLong(0L, grace, "last-seen");
        assertLong(1_700_020_000_000L, grace, "cooldown-expires");
    }

    private static void assertLong(long expected, ConfigurationSection section, String key) {
        Object value = section.get(key);
        // instanceof Number, not Long: a small value comes back from YAML as an Integer.
        assertInstanceOf(Number.class, value, key + " must be written as a number");
        assertEquals(expected, ((Number) value).longValue(), key);
    }

    @Test
    @DisplayName("reads a hand-written version 1 file")
    void readsAHandWrittenVersion1File() {
        StorageFixture.write(file(), """
                schema-version: 1
                players:
                  6ba7b810-9dad-41d1-80b4-00c04fd430c8:
                    name: Ada
                    lifetime-seconds: 123456
                    window-start: 1700000000000
                    window-seconds: 3600
                    last-seen: 1700003600000
                    cooldown-expires: 1700007200000
                """);

        YamlDataStorage store = loaded();

        assertEquals(new SessionSnapshot("Ada", 123_456L, 1_700_000_000_000L, 3_600L,
                1_700_003_600_000L), store.load(counted));
        assertEquals(1_700_007_200_000L, store.cooldownExpiresMillis(counted));
        assertEquals(Set.of(counted), store.records().keySet());
    }

    @Test
    @DisplayName("an absent last-seen reads as never recorded, not as the epoch")
    void absentLastSeenReadsAsNeverRecorded() {
        StorageFixture.write(file(), """
                schema-version: 1
                players:
                  6ba7b810-9dad-41d1-80b4-00c04fd430c8:
                    name: Ada
                    lifetime-seconds: 500
                """);

        SessionSnapshot read = loaded().load(counted);

        assertEquals(0L, read.lastSeenMillis());
        assertTrue(read.isUnknown(), "0 means unknown, so the tracker starts a fresh window");
        assertEquals(500L, read.lifetimeSeconds(), "the keys that are present are still read");
    }

    @Test
    @DisplayName("keys this build does not know survive a save")
    void keysThisBuildDoesNotKnowSurviveASave() {
        StorageFixture.write(file(), """
                schema-version: 1
                future-section:
                  flag: true
                players:
                  6ba7b810-9dad-41d1-80b4-00c04fd430c8:
                    name: Ada
                    lifetime-seconds: 10
                    window-start: 1700000000000
                    window-seconds: 10
                    last-seen: 1700000000000
                    cooldown-expires: 0
                    future-key: kept
                """);
        YamlDataStorage store = loaded();

        store.save(counted, new SessionSnapshot("Ada", 20L, 1_700_000_000_000L, 20L,
                1_700_000_060_000L));
        store.writeNow();

        YamlConfiguration raw = StorageFixture.parse(file());
        assertTrue(raw.getBoolean("future-section.flag"), "an unknown top-level key survives");
        assertEquals("kept", raw.getString("players." + counted + ".future-key"),
                "an unknown per-player key survives");
        assertEquals(20L, raw.getLong("players." + counted + ".lifetime-seconds"));
    }

    @Test
    @DisplayName("StoredPlayer is storage-safe: six components, all calendar millis or whole seconds")
    void storedPlayerIsStorageSafe() {
        List<String> wrong = new ArrayList<>();
        for (RecordComponent component : StoredPlayer.class.getRecordComponents()) {
            if (component.getType() != long.class) {
                continue;
            }
            String name = component.getName();
            if (name.toLowerCase(Locale.ROOT).contains("nano")
                    || !(name.endsWith("Millis") || name.endsWith("Seconds"))) {
                wrong.add(name);
            }
        }

        assertTrue(wrong.isEmpty(), "a persisted value named for neither calendar milliseconds "
                + "nor whole seconds: " + wrong);
        assertEquals(6, StoredPlayer.class.getRecordComponents().length,
                "StoredPlayer gained or lost a component; the file layout, the load path and "
                        + "this test all change with it");
    }
}
