package com.ninja6.sessionpulse.storage;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ninja6.sessionpulse.platform.RecordingScheduler;
import com.ninja6.sessionpulse.session.SessionSnapshot;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.logging.Level;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Loading never destroys a file it could not understand.
 *
 * <p>The failure these guard against is quiet and permanent: a store that treats an
 * unparseable file as empty, and then flushes an empty document over it five minutes later.
 */
class DataFileLoadTest {

    @TempDir
    Path dir;

    private final UUID uuid = UUID.fromString("6ba7b810-9dad-41d1-80b4-00c04fd430c8");
    private final StorageFixture.Log log = new StorageFixture.Log();

    private Path file() {
        return dir.resolve("data.yml");
    }

    private YamlDataStorage loaded() {
        YamlDataStorage store = StorageFixture.store(file(), new RecordingScheduler(), log);
        store.loadFromDisk();
        return store;
    }

    private SessionSnapshot someSession() {
        return new SessionSnapshot("Ada", 60L, StorageFixture.WALL, 60L, StorageFixture.WALL);
    }

    @Test
    @DisplayName("a missing file starts empty, says so in the loaded line, and creates nothing")
    void missingFileStartsEmptyAndCreatesNothing() throws IOException {
        YamlDataStorage store = loaded();

        assertTrue(store.records().isEmpty());
        assertTrue(log.has(Level.INFO, "Storage loaded: 0 player record(s) from data.yml."),
                "the boot legs grep for this line, missing file or not");
        try (var listing = Files.list(dir)) {
            assertEquals(0L, listing.count(), "loading writes nothing");
        }
    }

    @Test
    @DisplayName("an unparseable file is copied aside byte for byte, never overwritten")
    void unreadableFileIsPreservedNotOverwritten() throws IOException {
        byte[] garbage = "players: [unclosed\n  - : :\n".getBytes();
        Files.write(file(), garbage);

        YamlDataStorage store = loaded();
        Path aside = dir.resolve("data.yml.unreadable");
        assertArrayEquals(garbage, Files.readAllBytes(aside), "the original is preserved exactly");
        assertTrue(log.has(Level.SEVERE, file().toString(), aside.toString()),
                "the SEVERE line names both the file and where it went");
        assertTrue(store.records().isEmpty());

        // Storage stays writable: the original is safe, so the next flush may replace it.
        store.save(uuid, someSession());
        store.writeNow();
        assertEquals(someSession(), StorageFixture.restart(file()).load(uuid));

        // A second bad file never replaces the first copy.
        byte[] second = "{ not: [yaml".getBytes();
        Files.write(file(), second);
        loaded();
        assertArrayEquals(garbage, Files.readAllBytes(aside));
        assertArrayEquals(second, Files.readAllBytes(dir.resolve("data.yml.unreadable-1")));
    }

    @Test
    @DisplayName("bytes that are not UTF-8 are set aside like a parse failure")
    void nonUtf8FileIsSetAside() throws IOException {
        byte[] binary = {(byte) 0xC3, (byte) 0x28, (byte) 0xFF, (byte) 0xFE};
        Files.write(file(), binary);

        loaded();

        assertArrayEquals(binary, Files.readAllBytes(dir.resolve("data.yml.unreadable")));
    }

    @Test
    @DisplayName("a root that is not a map is treated as unparseable")
    void nonMapRootIsTreatedAsUnreadable() throws IOException {
        StorageFixture.write(file(), "hello\n");

        loaded();

        assertEquals("hello\n", StorageFixture.read(dir.resolve("data.yml.unreadable")));
        assertTrue(log.has(Level.SEVERE, file().toString()));
    }

    @Test
    @DisplayName("a file that cannot be read at all is left untouched and nothing is written")
    void unreadableFileRefusesWrites() throws IOException {
        // A directory where the file should be: it exists, and reading it fails with an
        // IOException that is not a parse failure, on every platform.
        Files.createDirectory(file());

        YamlDataStorage store = loaded();
        store.save(uuid, someSession());
        store.writeNow();
        store.shutdown();

        assertTrue(Files.isDirectory(file()), "nothing replaced what was there");
        assertFalse(Files.exists(dir.resolve("data.yml.unreadable")),
                "a read failure is not a parse failure; nothing is copied");
        assertTrue(log.has(Level.SEVERE, "will not write", file().toString()));
    }

    @Test
    @DisplayName("a blank file is set aside at SEVERE, since this plugin never writes one")
    void blankFileIsSetAside() throws IOException {
        StorageFixture.write(file(), "  \n\n");

        YamlDataStorage store = loaded();

        assertTrue(store.records().isEmpty());
        Path aside = dir.resolve("data.yml.unreadable");
        assertTrue(log.has(Level.SEVERE, file().toString(), "blank", aside.toString()),
                "a blank data.yml is what a write lost to a power cut leaves behind, so it is "
                        + "reported as damage, not read quietly as an empty store");
        assertEquals("  \n\n", StorageFixture.read(aside));

        store.save(uuid, someSession());
        store.writeNow();
        assertEquals(someSession(), StorageFixture.restart(file()).load(uuid),
                "with the original copied aside, storage stays writable");
    }

    /**
     * Loads a file that parses but has the wrong shape, then writes over it, and checks the
     * original survived the write byte for byte in exactly one copy.
     */
    private YamlDataStorage assertMisshapenFileSurvivesAWrite(String original) throws IOException {
        StorageFixture.write(file(), original);
        byte[] before = Files.readAllBytes(file());

        YamlDataStorage store = loaded();
        Path aside = dir.resolve("data.yml.unreadable");
        assertTrue(log.has(Level.SEVERE, file().toString(), aside.toString()),
                "a shape failure is reported at SEVERE, naming both paths: " + log.at(Level.SEVERE));

        store.save(UUID.fromString("0f8fad5b-d9cb-469f-a165-70867728950e"), someSession());
        store.writeNow();

        assertArrayEquals(before, Files.readAllBytes(aside),
                "the first save replaces the misshapen node in data.yml; without the copy the "
                        + "original would be gone");
        assertFalse(Files.exists(dir.resolve("data.yml.unreadable-1")),
                "set aside at most once per load");
        return store;
    }

    @Test
    @DisplayName("players as a list is set aside before a save can replace it")
    void playersThatIsNotASectionIsSetAside() throws IOException {
        assertMisshapenFileSurvivesAWrite("""
                schema-version: 1
                players:
                  - 6ba7b810-9dad-41d1-80b4-00c04fd430c8:
                      lifetime-seconds: 999999
                      cooldown-expires: 1900000000000
                """);
    }

    @Test
    @DisplayName("an entry that is not a section is set aside, and the readable entries are kept")
    void anEntryThatIsNotASectionIsSetAside() throws IOException {
        YamlDataStorage store = assertMisshapenFileSurvivesAWrite("""
                schema-version: 1
                players:
                  6ba7b810-9dad-41d1-80b4-00c04fd430c8: [999999, 1900000000000]
                  6ba7b811-9dad-41d1-80b4-00c04fd430c8:
                    lifetime-seconds: 12
                    last-seen: 1700000000000
                """);

        assertEquals(12L, store.load(UUID.fromString("6ba7b811-9dad-41d1-80b4-00c04fd430c8"))
                .lifetimeSeconds());
    }

    @Test
    @DisplayName("keys that are not canonical UUIDs are set aside, uppercase included")
    void aNonUuidKeyIsSetAside() throws IOException {
        assertMisshapenFileSurvivesAWrite("""
                schema-version: 1
                players:
                  not-a-uuid:
                    lifetime-seconds: 1
                  6BA7B810-9DAD-41D1-80B4-00C04FD430C8:
                    cooldown-expires: 1900000000000
                """);
        assertTrue(log.has(Level.WARNING, "6BA7B810-9DAD-41D1-80B4-00C04FD430C8"));
    }

    @Test
    @DisplayName("a file with no valid schema-version warns naming the file, and is still read")
    void unstampedNonEmptyFileWarnsNamingTheFile() {
        StorageFixture.write(file(), """
                players:
                  6ba7b810-9dad-41d1-80b4-00c04fd430c8:
                    lifetime-seconds: 42
                """);
        YamlDataStorage store = loaded();
        assertTrue(log.has(Level.WARNING, file().toString(), "schema-version"));
        assertEquals(42L, store.load(uuid).lifetimeSeconds());

        StorageFixture.Log second = new StorageFixture.Log();
        StorageFixture.write(file(), "schema-version: abc\nplayers: {}\n");
        StorageFixture.store(file(), new RecordingScheduler(), second).loadFromDisk();
        assertTrue(second.has(Level.WARNING, file().toString(), "schema-version"),
                "a non-numeric stamp is treated the same as a missing one");
    }

    @Test
    @DisplayName("a malformed UUID key is skipped with a warning and the rest are read")
    void malformedUuidKeyIsSkippedWithAWarning() {
        StorageFixture.write(file(), """
                schema-version: 1
                players:
                  not-a-uuid:
                    lifetime-seconds: 1
                  6ba7b810-9dad-41d1-80b4-00c04fd430c8:
                    lifetime-seconds: 2
                """);

        YamlDataStorage store = loaded();

        assertEquals(1, store.records().size());
        assertEquals(2L, store.load(uuid).lifetimeSeconds());
        assertTrue(log.has(Level.WARNING, "not-a-uuid"));
    }

    @Test
    @DisplayName("non-numeric and negative values warn and read as zero")
    void badNumbersWarnAndReadAsZero() {
        StorageFixture.write(file(), """
                schema-version: 1
                players:
                  6ba7b810-9dad-41d1-80b4-00c04fd430c8:
                    lifetime-seconds: lots
                    window-seconds: -5
                    last-seen: 1700000000000
                """);

        SessionSnapshot read = loaded().load(uuid);

        assertEquals(0L, read.lifetimeSeconds());
        assertEquals(0L, read.windowSeconds());
        assertEquals(1_700_000_000_000L, read.lastSeenMillis());
        assertTrue(log.has(Level.WARNING, uuid.toString(), "lifetime-seconds"));
        assertTrue(log.has(Level.WARNING, uuid.toString(), "window-seconds"));
    }

    @Test
    @DisplayName("a quoted whole number is read as the number, without a warning")
    void quotedNumberIsRead() {
        StorageFixture.write(file(), """
                schema-version: 1
                players:
                  6ba7b810-9dad-41d1-80b4-00c04fd430c8:
                    last-seen: '1700000000000'
                    cooldown-expires: " 1900000000000 "
                """);

        YamlDataStorage store = loaded();

        assertEquals(1_900_000_000_000L, store.cooldownExpiresMillis(uuid),
                "save never rewrites another writer's key, so a cooldown read as 0 here would "
                        + "stay unenforced for good");
        assertEquals(1_700_000_000_000L, store.load(uuid).lastSeenMillis());
        assertTrue(log.at(Level.WARNING).isEmpty(), "" + log.at(Level.WARNING));
    }

    @Test
    @DisplayName("a string that is not a whole number still warns and reads as zero")
    void nonNumericStringWarnsAndReadsAsZero() {
        StorageFixture.write(file(), """
                schema-version: 1
                players:
                  6ba7b810-9dad-41d1-80b4-00c04fd430c8:
                    cooldown-expires: '19e11'
                """);

        assertEquals(0L, loaded().cooldownExpiresMillis(uuid));
        assertTrue(log.has(Level.WARNING, uuid.toString(), "cooldown-expires"));
    }

    @Test
    @DisplayName("small values read back exactly and without a warning")
    void smallValuesReadBackWithoutWarning() {
        StorageFixture.write(file(), """
                schema-version: 1
                players:
                  6ba7b810-9dad-41d1-80b4-00c04fd430c8:
                    name: Ada
                    lifetime-seconds: 7
                    window-start: 1700000000000
                    window-seconds: 3600
                    last-seen: 1700000000000
                    cooldown-expires: 0
                """);

        YamlDataStorage store = loaded();

        assertEquals(3_600L, store.load(uuid).windowSeconds());
        assertEquals(7L, store.load(uuid).lifetimeSeconds());
        assertTrue(log.at(Level.WARNING).isEmpty(),
                "YAML reads these as Integer; a Long-only check would warn on every one: "
                        + log.at(Level.WARNING));
    }

    @Test
    @DisplayName("a newer schema-version is read but never written over")
    void newerSchemaRefusesToWrite() throws IOException {
        String newer = """
                schema-version: 2
                players:
                  6ba7b810-9dad-41d1-80b4-00c04fd430c8:
                    lifetime-seconds: 99
                    something-new: true
                """;
        StorageFixture.write(file(), newer);
        byte[] before = Files.readAllBytes(file());

        YamlDataStorage store = loaded();
        assertEquals(99L, store.load(uuid).lifetimeSeconds(), "read as far as possible");
        assertTrue(log.has(Level.SEVERE, file().toString(), "schema-version"));

        store.save(uuid, someSession());
        store.writeNow();
        int severeBeforeShutdown = log.at(Level.SEVERE).size();
        store.shutdown();

        assertArrayEquals(before, Files.readAllBytes(file()), "the newer file is untouched");
        assertEquals(severeBeforeShutdown + 1, log.at(Level.SEVERE).size(),
                "shutdown says again that nothing was saved");
    }

    @Test
    @DisplayName("load is an in-memory read: the file can vanish and lookups still answer")
    void loadIsAnInMemoryRead() throws IOException {
        StorageFixture.write(file(), """
                schema-version: 1
                players:
                  6ba7b810-9dad-41d1-80b4-00c04fd430c8:
                    lifetime-seconds: 5
                    last-seen: 1700000000000
                    cooldown-expires: 1700000009000
                """);
        YamlDataStorage store = loaded();

        Files.delete(file());

        assertEquals(5L, store.load(uuid).lifetimeSeconds());
        assertEquals(1_700_000_009_000L, store.cooldownExpiresMillis(uuid));
    }
}
