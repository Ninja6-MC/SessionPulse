package com.ninja6.sessionpulse.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ninja6.sessionpulse.platform.SourceTree;
import com.ninja6.sessionpulse.session.SessionStore;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What storage lets the rest of the plugin reach, and that the plugin actually wires it.
 *
 * <p>The behavioural tests in this package pass just as well against a plugin that still
 * runs on a store remembering nothing, or that never starts the flush. Those failures are
 * silent on a server - every restart forgets everyone - so they are checked here by reading
 * the source, the way the session tick and the blanket cancel already are.
 */
class StorageSurfaceTest {

    private static final Path PLUGIN = SourceTree.MAIN_JAVA
            .resolve(Path.of("com", "ninja6", "sessionpulse", "SessionPulsePlugin.java"));

    @Test
    @DisplayName("DataStorage exposes no disk write and no lifecycle, inherited or declared")
    void dataStorageExposesNoDiskWrite() throws NoSuchMethodException {
        // Any arity for the lifecycle; zero-arg only for save, whose two-argument form is the
        // inherited in-memory SessionStore write and belongs here.
        Set<String> lifecycle = Set.of("writeNow", "loadFromDisk", "shutdown", "startFlushing");
        List<String> reachable = new ArrayList<>();
        for (Method method : DataStorage.class.getMethods()) {
            boolean diskSave = method.getName().equals("save") && method.getParameterCount() == 0;
            if (diskSave || lifecycle.contains(method.getName())) {
                reachable.add(method.toString());
            }
        }
        assertTrue(reachable.isEmpty(),
                "DataStorage is what the plugin hands out. Anything here can be called from the "
                        + "main thread by any later issue: " + reachable);

        Method writeNow = YamlDataStorage.class.getDeclaredMethod("writeNow");
        assertFalse(Modifier.isPublic(writeNow.getModifiers()),
                "writeNow is the synchronous write; it stays package-private");
    }

    @Test
    @DisplayName("DataStorage is a SessionStore, so the tracker takes it directly")
    void dataStorageIsASessionStore() {
        assertTrue(SessionStore.class.isAssignableFrom(DataStorage.class));
    }

    @Test
    @DisplayName("the plugin wires the real store into the tracker, the listener and the lifecycle")
    void thePluginWiresTheRealStore() {
        String code = SourceTree.stripCommentsAndStrings(SourceTree.read(PLUGIN));

        assertTrue(code.contains("new YamlDataStorage("), "the plugin never builds the store");
        assertTrue(code.contains(".loadFromDisk()"), "the store is never loaded");
        assertTrue(Pattern.compile("new SessionTracker\\([^;]*\\bstorage\\)").matcher(code).find(),
                "the tracker is not constructed over the store; every restart forgets everyone");
        assertTrue(code.contains("storage::flushAsync"), "the listener is not asked to flush on quit");
        assertTrue(code.contains(".startFlushing("), "the periodic flush is never started");

        int start = code.indexOf(" onDisable(");
        assertTrue(start >= 0, "no onDisable");
        int end = code.indexOf("\n    }", start);
        String disable = code.substring(start, end);
        assertTrue(disable.contains(".shutdown()"),
                "onDisable never writes the store; a clean stop loses everything since the last "
                        + "periodic flush");
        int unregister = disable.indexOf("HandlerList.unregisterAll(this)");
        assertTrue(unregister >= 0 && unregister < disable.indexOf(".shutdown()"),
                "listeners must be unregistered before the store closes. It stops further quits "
                        + "being dispatched, which narrows - not closes - the window in which "
                        + "one lands in a closed store and loses its save");
    }

    /**
     * Spellings of a file write. {@code .save(} cannot be scoped to {@code YamlConfiguration}
     * by text alone, and a bare token would match {@code SessionStore#save}; the pattern
     * instead matches the shapes a configuration save takes - a {@code File}, a variable
     * named for a file, a string literal (blanked to spaces by the stripper), or nothing.
     * {@code saveDefaultConfig()} in onEnable is Bukkit's own and is not matched.
     */
    private static final List<Pattern> FILE_WRITES = List.of(
            Pattern.compile("Files\\.write"),
            Pattern.compile("Files\\.move"),
            Pattern.compile("Files\\.copy"),
            Pattern.compile("Files\\.newBufferedWriter"),
            Pattern.compile("Files\\.newOutputStream"),
            Pattern.compile("FileChannel\\.open"),
            Pattern.compile("new\\s+FileWriter\\b"),
            Pattern.compile("new\\s+FileOutputStream\\b"),
            Pattern.compile("saveToString\\("),
            Pattern.compile("\\.save\\(\\s*(new\\s+File\\b|[A-Za-z_]*[Ff]ile\\s*\\)|\\))"));

    private static List<String> fileWritesIn(String code) {
        List<String> found = new ArrayList<>();
        for (Pattern pattern : FILE_WRITES) {
            if (pattern.matcher(code).find()) {
                found.add(pattern.pattern());
            }
        }
        return found;
    }

    @Test
    @DisplayName("the file-write patterns match the spellings they name, and not SessionStore#save")
    void fileWritePatternsMatchWhatTheyName() {
        String sample = SourceTree.stripCommentsAndStrings(String.join("\n",
                "Files.newBufferedWriter(p);", "new FileOutputStream(f);", "new FileWriter(f);",
                "yaml.save(new File(dir, \"data.yml\"));", "yaml.save(dataFile);",
                "yaml.save(\"data.yml\");"));
        // Four distinct patterns: the three save spellings share one.
        assertEquals(4, fileWritesIn(sample).size(), "a pattern went dead: " + fileWritesIn(sample));
        assertEquals(1, fileWritesIn("yaml.save(new File(dir, name));").size());
        assertEquals(1, fileWritesIn("yaml.save(dataFile);").size());
        assertEquals(1, fileWritesIn(
                SourceTree.stripCommentsAndStrings("yaml.save(\"x.yml\");")).size());
        assertTrue(fileWritesIn("store.save(uuid, snapshot);").isEmpty(),
                "the in-memory SessionStore write is not a file write");
    }

    @Test
    @DisplayName("file writes happen under storage/ and nowhere else")
    void fileWritesAreConfinedToStorage() {
        List<String> offenders = new ArrayList<>();
        boolean seenInStorage = false;
        for (Path source : SourceTree.mainSources()) {
            String code = SourceTree.stripCommentsAndStrings(SourceTree.read(source));
            boolean inStorage = source.getParent().getFileName().toString().equals("storage");
            for (String call : fileWritesIn(code)) {
                if (inStorage) {
                    seenInStorage = true;
                } else {
                    offenders.add("  - " + source + " matches " + call);
                }
            }
        }
        assertTrue(offenders.isEmpty(), "a file write outside storage/ is a disk write nothing "
                + "schedules off the main thread:" + System.lineSeparator()
                + String.join(System.lineSeparator(), offenders));
        assertTrue(seenInStorage, "storage/ writes no file at all, so this test proves nothing");
    }
}
