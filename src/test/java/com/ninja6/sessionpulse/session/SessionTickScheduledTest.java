package com.ninja6.sessionpulse.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ninja6.sessionpulse.platform.SourceTree;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The session tick is actually scheduled, once, through the seam.
 *
 * <p>Every other test in this issue passes just as well against a plugin that never runs
 * the tick at all: the tracker's arithmetic is exercised directly, and a tracker nobody
 * calls is still correct. The failure that leaves - a plugin that boots clean, logs
 * nothing and counts nothing - is invisible to all of them, so it is checked here by
 * reading the source, the way the blanket-cancel rule already is.
 */
class SessionTickScheduledTest {

    private static final String CALL = "globalRepeating(";

    @Test
    @DisplayName("the session tick is scheduled exactly once, through Scheduler#globalRepeating")
    void theSessionTickIsScheduledExactlyOnceThroughGlobalRepeating() {
        int callSites = 0;
        StringBuilder where = new StringBuilder();

        for (Path source : SourceTree.mainSources()) {
            // platform/ declares the method and implements it; call sites are what matter.
            if (source.getParent().getFileName().toString().equals("platform")) {
                continue;
            }
            String code = SourceTree.stripCommentsAndStrings(SourceTree.read(source));
            int at = code.indexOf(CALL);
            while (at >= 0) {
                callSites++;
                long line = code.substring(0, at).chars().filter(c -> c == '\n').count() + 1;
                where.append(System.lineSeparator()).append("  - ").append(source)
                        .append(" line ").append(line);
                at = code.indexOf(CALL, at + CALL.length());
            }
        }

        assertEquals(1, callSites,
                "Expected exactly one globalRepeating() call site outside platform/, the "
                        + "session tick in SessionPulsePlugin#onEnable. Zero means a plugin "
                        + "that never counts, which every other test in this issue would "
                        + "still pass. Found:" + where);
    }

    @Test
    @DisplayName("the tick runs once a second, after a delay the library will accept")
    void theTickRunsEverySecond() {
        assertEquals(20L, SessionTickTask.PERIOD_TICKS,
                "twenty ticks is one second. The counted window is consumed at minute "
                        + "granularity, so a longer period makes a milestone late by up to "
                        + "that period.");
        assertTrue(SessionTickTask.DELAY_TICKS >= 1L,
                "the scheduling library warns about, and silently promotes, any delay below "
                        + "one tick. Do not make it warn.");
    }
}
