package com.ninja6.sessionpulse;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ninja6.sessionpulse.platform.SourceTree;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The reload and disable sequences, read from the source.
 *
 * <p>The plugin cannot be constructed without a server, and every piece these sequences call is
 * tested on its own: the parse check, the flush reschedule, the tick reschedule, the tick's
 * retire. What none of those tests notice is a step dropped from, or moved within, the method
 * that strings them together. A reload that no longer reschedules still reports success, and a
 * retire moved after the blanket cancel reopens the window in which a racing reload schedules
 * a tick nothing will ever cancel.
 */
class ReloadWiringTest {

    private static final Path PLUGIN = Path.of("src", "main", "java")
            .resolve(Path.of("com", "ninja6", "sessionpulse", "SessionPulsePlugin.java"));

    private static String body(String signature) {
        String code = SourceTree.stripCommentsAndStrings(SourceTree.read(PLUGIN));
        int at = code.indexOf(signature);
        assertTrue(at >= 0, signature + " not found");
        int open = code.indexOf('{', at);
        int depth = 0;
        for (int i = open; i < code.length(); i++) {
            char c = code.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}' && --depth == 0) {
                return code.substring(open, i + 1);
            }
        }
        throw new AssertionError("unbalanced braces after " + signature);
    }

    @Test
    @DisplayName("reload parses, publishes, then reschedules the flush and the tick")
    void reloadSequence() {
        String reload = body("public void reload()");
        int check = reload.indexOf("requireParses(");
        int publish = reload.indexOf("loadConfiguration()");
        int flush = reload.indexOf("rescheduleFlush()");
        int tick = reload.indexOf("sessionTick.reschedule()");

        assertTrue(check >= 0, "reload no longer checks that config.yml parses");
        assertTrue(publish > check, "the configuration is published before the file is checked");
        assertTrue(flush > publish,
                "the flush is not rescheduled after publication, so a new interval never applies");
        assertTrue(tick > publish,
                "the tick is not rescheduled after publication, so a new period never applies");
    }

    @Test
    @DisplayName("disable retires the tick before the blanket cancel")
    void disableRetiresBeforeCancelAll() {
        String disable = body("public void onDisable()");
        int retire = disable.indexOf("sessionTick.retire()");
        int cancel = disable.indexOf("cancelAll(");

        assertTrue(retire >= 0, "onDisable no longer retires the tick");
        assertTrue(cancel > retire,
                "a reload racing disable could schedule a tick after the blanket cancel");
    }
}
