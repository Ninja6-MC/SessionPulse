package com.ninja6.sessionpulse.platform;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The blanket cancel is called from {@code onDisable} and from nowhere else.
 *
 * <p>Keeping {@code cancelAll} off the {@code Scheduler} interface already makes the
 * common mistake impossible: a collaborator holds the interface and cannot reach the
 * method at all. This closes the remaining door, which is {@code SessionPulsePlugin}
 * itself - it holds the concrete type, so it is the one class that could call the blanket
 * cancel from a reload path.
 *
 * <p>The failure being prevented is silent. A {@code /spulse reload} that cancels
 * everything also cancels the session tick; the command reports success, the plugin stops
 * counting, and nothing appears in the log. A brace-depth scan is crude but honest, and
 * the alternative is an ArchUnit dependency for one rule.
 */
class CancelAllIsDisableOnlyTest {

    private static final String CALL = "cancelAll(";

    @Test
    @DisplayName("cancelAll is called only from SessionPulsePlugin#onDisable")
    void blanketCancelIsDisableOnly() {
        List<String> offenders = new ArrayList<>();
        int callSites = 0;

        for (Path source : SourceTree.mainSources()) {
            // platform/ declares the method; it is call sites elsewhere that are the risk.
            if (source.getParent().getFileName().toString().equals("platform")) {
                continue;
            }
            String code = SourceTree.stripCommentsAndStrings(SourceTree.read(source));
            int[] disable = methodBody(code, "onDisable");

            int at = code.indexOf(CALL);
            while (at >= 0) {
                callSites++;
                if (disable == null || at < disable[0] || at > disable[1]) {
                    long line = code.substring(0, at).chars().filter(c -> c == '\n').count() + 1;
                    offenders.add("  - " + source + " line " + line);
                }
                at = code.indexOf(CALL, at + CALL.length());
            }
        }

        assertTrue(offenders.isEmpty(),
            "cancelAll() is disable-only. Reload must cancel and reschedule its two tasks "
                + "through the Task handles it holds; a blanket cancel there stops the session "
                + "tick and the plugin silently stops counting. Called from:"
                + System.lineSeparator() + String.join(System.lineSeparator(), offenders));

        // A tree that never calls it would satisfy the assertion above, and that tree is a
        // plugin leaking its tasks past disable. The one call has to exist.
        assertTrue(callSites == 1,
            "Expected exactly one cancelAll() call site outside platform/, in "
                + "SessionPulsePlugin#onDisable, but found " + callSites + ".");
    }

    /**
     * The character range of the named method's body, or {@code null} if it is not declared
     * in this file.
     *
     * @param code   source with comments and string literals already blanked out
     * @param method the method name to locate
     * @return {@code {openBrace, closeBrace}} offsets, or {@code null}
     */
    private static int[] methodBody(String code, String method) {
        int signature = code.indexOf(" " + method + "(");
        if (signature < 0) {
            return null;
        }
        int open = code.indexOf('{', signature);
        if (open < 0) {
            return null;
        }
        int depth = 0;
        for (int i = open; i < code.length(); i++) {
            char c = code.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return new int[] {open, i};
                }
            }
        }
        return null;
    }
}
