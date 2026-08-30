package com.ninja6.sessionpulse.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Executable;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The seam's shape, checked mechanically.
 *
 * <p>These prove nothing about whether the plugin works. What they protect is the property
 * that makes the rest of the milestone safe to write: that {@code Scheduler} does not leak
 * the scheduling library, and that exactly one class names it. Both are the kind of thing
 * a later issue undoes in one careless import and nobody notices in review.
 */
class SchedulerSeamTest {

    /**
     * Package fragments that may appear in exactly one source file.
     *
     * <p>Deliberately NOT the token {@code FoliaLib}. That one legitimately appears in
     * {@code SessionPulsePlugin}, which declares a {@code FoliaLibScheduler} field, and in
     * {@code Scheduler}'s own javadoc, which explains why the blanket cancel is not on it.
     * Asserting on it would either fail on correct code or have to be weakened until it
     * asserted nothing. These two are genuinely confined to the one file, and they are what
     * an accidental import would actually bring with it.
     */
    private static final List<String> LIBRARY_MARKERS = List.of("com.tcoded", "folialib");

    /**
     * What the seam's own signatures may not name. A superset of the above: the interface
     * must not surface Adventure either. {@code SessionPulsePlugin} does import
     * {@code net.kyori} - it owns the audience provider - so this list is only ever applied
     * to the reflected API of {@code Scheduler}, never to the source tree.
     */
    private static final List<String> SEAM_MARKERS = List.of("com.tcoded", "folialib", "kyori");

    private static final String OWNER = "FoliaLibScheduler.java";

    @Test
    @DisplayName("no type named anywhere in the Scheduler API comes from the scheduling library")
    void seamDoesNotLeakTheLibrary() {
        Set<Class<?>> nested = new LinkedHashSet<>();
        nested.add(Scheduler.class);
        nested.addAll(Arrays.asList(Scheduler.class.getDeclaredClasses()));

        List<String> leaks = new ArrayList<>();
        for (Class<?> type : nested) {
            for (Method method : type.getDeclaredMethods()) {
                check(leaks, type, method, method.getGenericReturnType().getTypeName());
                for (java.lang.reflect.Type parameter : method.getGenericParameterTypes()) {
                    check(leaks, type, method, parameter.getTypeName());
                }
                for (java.lang.reflect.Type thrown : method.getGenericExceptionTypes()) {
                    check(leaks, type, method, thrown.getTypeName());
                }
            }
        }

        assertTrue(leaks.isEmpty(),
            "The Scheduler seam names types from the scheduling library, which defeats it:"
                + System.lineSeparator() + String.join(System.lineSeparator(), leaks));
    }

    private static void check(List<String> leaks, Class<?> owner, Executable method, String typeName) {
        String lower = typeName.toLowerCase(java.util.Locale.ROOT);
        for (String marker : SEAM_MARKERS) {
            if (lower.contains(marker)) {
                leaks.add("  - " + owner.getSimpleName() + "#" + method.getName() + " -> " + typeName);
            }
        }
    }

    @Test
    @DisplayName("FoliaLibScheduler is the only source file that names the scheduling library")
    void onlyOneClassNamesTheLibrary() {
        List<String> offenders = new ArrayList<>();
        for (Path source : SourceTree.mainSources()) {
            if (source.getFileName().toString().equals(OWNER)) {
                continue;
            }
            String text = SourceTree.read(source);
            for (String marker : LIBRARY_MARKERS) {
                if (text.contains(marker)) {
                    offenders.add("  - " + source + " contains \"" + marker + "\"");
                }
            }
        }

        assertTrue(offenders.isEmpty(),
            OWNER + " is meant to be the only class in the plugin that names the scheduling "
                + "library. These name it too:" + System.lineSeparator()
                + String.join(System.lineSeparator(), offenders));
    }

    @Test
    @DisplayName("the library's imports are confined to FoliaLibScheduler")
    void libraryImportsAreConfined() {
        List<String> offenders = new ArrayList<>();
        Path owner = null;
        for (Path source : SourceTree.mainSources()) {
            List<String> imports = SourceTree.read(source).lines()
                .map(String::strip)
                .filter(line -> line.startsWith("import "))
                // Case-SENSITIVE, and that is the whole subtlety. Package names are lower
                // case, so the markers match a real import exactly; folding case first
                // would make `import com.ninja6.sessionpulse.platform.FoliaLibScheduler`
                // in SessionPulsePlugin read as an import of the library itself, and the
                // test would fail on the one wiring the seam requires.
                .filter(line -> LIBRARY_MARKERS.stream().anyMatch(line::contains))
                .collect(Collectors.toList());
            if (imports.isEmpty()) {
                continue;
            }
            if (source.getFileName().toString().equals(OWNER)) {
                owner = source;
                continue;
            }
            imports.forEach(line -> offenders.add("  - " + source + ": " + line));
        }

        assertTrue(offenders.isEmpty(),
            "Only " + OWNER + " may import the scheduling library:" + System.lineSeparator()
                + String.join(System.lineSeparator(), offenders));
        // Without this the test passes just as well against a source tree that imports the
        // library nowhere at all - which is what deleting FoliaLibScheduler would produce.
        assertTrue(owner != null,
            OWNER + " imports the scheduling library nowhere, so this test proves nothing. "
                + "Either the file moved or the seam has no implementation.");
    }

    @Test
    @DisplayName("Scheduler declares exactly the three methods, and no blanket cancel")
    void schedulerDeclaresNoBlanketCancel() {
        Set<String> declared = Arrays.stream(Scheduler.class.getDeclaredMethods())
            .map(Method::getName)
            .collect(Collectors.toCollection(LinkedHashSet::new));

        assertEquals(Set.of("globalRepeating", "entity", "async"), declared,
            "Scheduler's method set changed. cancelAll in particular must stay OFF this "
                + "interface: /spulse reload cancels its two tasks through their handles, and a "
                + "blanket cancel reachable from a collaborator silently stops the plugin "
                + "counting. It lives on FoliaLibScheduler, which only SessionPulsePlugin holds.");
    }
}
