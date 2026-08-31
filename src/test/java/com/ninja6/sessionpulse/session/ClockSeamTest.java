package com.ninja6.sessionpulse.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ninja6.sessionpulse.platform.SourceTree;
import java.lang.reflect.RecordComponent;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The clock seam is real, not decorative.
 *
 * <p>A tracker that takes a {@code SessionClock} in its constructor and then calls
 * {@code System.nanoTime()} somewhere down the line looks injected, tests green against a
 * fixed clock, and behaves differently in production. The only way to rule that out is to
 * read the source, which is what these do - the same technique the scheduler seam already
 * uses for "only one class names the scheduling library".
 *
 * <p>Comments and string literals are blanked first, so this file and the seam's own
 * javadoc can name the methods they are confining without failing the build for it.
 */
class ClockSeamTest {

    private static final String OWNER = "SessionClock.java";

    @Test
    @DisplayName("SessionClock is the only source file that calls System.nanoTime")
    void onlyTheClockSeamCallsSystemNanoTime() {
        assertConfinedToTheSeam("System.nanoTime");
    }

    @Test
    @DisplayName("SessionClock is the only source file that calls System.currentTimeMillis")
    void onlyTheClockSeamCallsSystemCurrentTimeMillis() {
        assertConfinedToTheSeam("System.currentTimeMillis");
    }

    private static void assertConfinedToTheSeam(String call) {
        List<String> offenders = new ArrayList<>();
        int inTheSeam = 0;

        for (Path source : SourceTree.mainSources()) {
            String code = SourceTree.stripCommentsAndStrings(SourceTree.read(source));
            int at = code.indexOf(call);
            while (at >= 0) {
                if (source.getFileName().toString().equals(OWNER)) {
                    inTheSeam++;
                } else {
                    long line = code.substring(0, at).chars().filter(c -> c == '\n').count() + 1;
                    offenders.add("  - " + source + " line " + line);
                }
                at = code.indexOf(call, at + call.length());
            }
        }

        assertTrue(offenders.isEmpty(),
                call + "() belongs in " + OWNER + " and nowhere else. Everything downstream "
                        + "takes the clock as a constructor argument, which is what makes the "
                        + "counted window testable at all. Called from:"
                        + System.lineSeparator() + String.join(System.lineSeparator(), offenders));

        // Without this the assertion above is also satisfied by a tree that has no seam at
        // all, which is what deleting SessionClock would produce.
        assertTrue(inTheSeam > 0,
                OWNER + " does not call " + call + "() at all, so this test proves nothing. "
                        + "Either the file moved or the seam has no implementation.");
    }

    @Test
    @DisplayName("no persisted field is named for a nanosecond value")
    void noPersistedFieldIsNamedForANanosecondValue() {
        List<String> wrong = new ArrayList<>();
        for (RecordComponent component : SessionSnapshot.class.getRecordComponents()) {
            if (component.getType() != long.class) {
                continue;
            }
            String name = component.getName();
            if (name.toLowerCase(java.util.Locale.ROOT).contains("nano")
                    || !(name.endsWith("Millis") || name.endsWith("Seconds"))) {
                wrong.add("  - " + name);
            }
        }

        assertTrue(wrong.isEmpty(),
                "Every numeric field on the type that crosses to storage must be calendar "
                        + "milliseconds or whole seconds. A monotonic reading is meaningless "
                        + "in the next run of the JVM. Offending components:"
                        + System.lineSeparator() + String.join(System.lineSeparator(), wrong));

        // A naming rule catches a component added carelessly; it cannot catch a correctly
        // named component filled from the wrong value. That is covered at the value level
        // by SessionTrackerTest#quitReturnsAWallClockSnapshotInWholeSeconds.
        assertEquals(5, SessionSnapshot.class.getRecordComponents().length,
                "SessionSnapshot gained or lost a component; check it is still storage-safe");
    }
}
