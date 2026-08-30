package com.ninja6.sessionpulse.platform;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Reads {@code src/main/java} so the tests below can make assertions about the source
 * itself rather than only about the classes it compiles to.
 *
 * <p>Two of the rules this issue introduces are properties of the source text and of
 * nothing else - "only one class names the scheduling library" and "the blanket cancel is
 * called from exactly one method" - so they are checked by reading it. Gradle sets the
 * test task's working directory to the project directory, which is what makes the
 * relative path below correct.
 */
final class SourceTree {

    private SourceTree() {
    }

    static final Path MAIN_JAVA = Path.of("src", "main", "java");

    /** Every {@code .java} file under {@code src/main/java}. */
    static List<Path> mainSources() {
        try (Stream<Path> walk = Files.walk(MAIN_JAVA)) {
            return walk.filter(Files::isRegularFile)
                .filter(p -> p.getFileName().toString().endsWith(".java"))
                .sorted()
                .collect(Collectors.toList());
        } catch (IOException e) {
            throw new UncheckedIOException(
                "Could not walk " + MAIN_JAVA.toAbsolutePath()
                    + ". The test task's working directory must be the project directory.", e);
        }
    }

    static String read(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read " + path, e);
        }
    }

    /**
     * The source with comments and string literals removed.
     *
     * <p>Necessary rather than fussy: the javadoc on {@code SessionPulsePlugin} legitimately
     * writes {@code cancelAll()} in a {@code @link}, and {@code Scheduler}'s javadoc names
     * the library in prose. A scan over the raw text would read both as call sites and
     * fail the build for documenting the rule it is enforcing.
     *
     * <p>Characters are replaced by spaces rather than deleted so that offsets into the
     * result still line up with the original, which is what lets the brace-depth scan
     * below report a usable position.
     */
    static String stripCommentsAndStrings(String source) {
        char[] out = source.toCharArray();
        int i = 0;
        int n = out.length;
        while (i < n) {
            char c = out[i];
            if (c == '/' && i + 1 < n && out[i + 1] == '/') {
                while (i < n && out[i] != '\n') {
                    out[i++] = ' ';
                }
            } else if (c == '/' && i + 1 < n && out[i + 1] == '*') {
                int end = source.indexOf("*/", i + 2);
                int stop = end < 0 ? n : end + 2;
                while (i < stop) {
                    if (out[i] != '\n') {
                        out[i] = ' ';
                    }
                    i++;
                }
            } else if (c == '"' || c == '\'') {
                char quote = c;
                out[i++] = ' ';
                while (i < n && out[i] != quote) {
                    if (out[i] == '\\' && i + 1 < n) {
                        out[i++] = ' ';
                    }
                    if (i < n && out[i] != '\n') {
                        out[i] = ' ';
                    }
                    i++;
                }
                if (i < n) {
                    out[i++] = ' ';
                }
            } else {
                i++;
            }
        }
        return new String(out);
    }
}
