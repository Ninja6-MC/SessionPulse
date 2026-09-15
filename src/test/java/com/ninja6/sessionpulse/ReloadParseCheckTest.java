package com.ninja6.sessionpulse;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ninja6.sessionpulse.platform.SourceTree;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.bukkit.configuration.InvalidConfigurationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A config.yml that does not parse fails the reload instead of replacing the configuration.
 *
 * <p>{@code JavaPlugin#reloadConfig} swallows a syntax error and hands back the jar's defaults,
 * so without the check the reload would report success over enforcement switched off. The
 * plugin cannot be constructed without a server, so the check is tested directly and its
 * position in {@code reload()} is read from the source, the way the wiring tests read it.
 */
class ReloadParseCheckTest {

    private static final Path PLUGIN = Path.of("src", "main", "java")
            .resolve(Path.of("com", "ninja6", "sessionpulse", "SessionPulsePlugin.java"));

    @TempDir
    Path dir;

    @Test
    @DisplayName("a file that does not parse throws, carrying the parser's error")
    void unparseableThrows() throws IOException {
        Path file = dir.resolve("config.yml");
        Files.writeString(file, "enforcement:\n  enabled: true\n   at-minutes: [\n",
                StandardCharsets.UTF_8);

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> SessionPulsePlugin.requireParses(file.toFile()));
        assertInstanceOf(InvalidConfigurationException.class, thrown.getCause());
    }

    @Test
    @DisplayName("a file that parses, and a missing file, pass")
    void parseableAndMissingPass() throws IOException {
        Path file = dir.resolve("config.yml");
        assertDoesNotThrow(() -> SessionPulsePlugin.requireParses(file.toFile()));
        Files.writeString(file, "enforcement:\n  enabled: true\n", StandardCharsets.UTF_8);
        assertDoesNotThrow(() -> SessionPulsePlugin.requireParses(file.toFile()));
    }

    @Test
    @DisplayName("reload checks the file before it reloads or publishes anything")
    void reloadChecksFirst() {
        String code = SourceTree.stripCommentsAndStrings(SourceTree.read(PLUGIN));
        int at = code.indexOf("public void reload()");
        assertTrue(at >= 0, "reload() not found");
        int check = code.indexOf("requireParses(", at);
        int reload = code.indexOf("reloadConfig()", at);
        int publish = code.indexOf("loadConfiguration()", at);
        assertTrue(check > at && check < reload && reload < publish,
                "a config.yml that does not parse would be replaced by the jar's defaults");
    }
}
