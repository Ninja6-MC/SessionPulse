package com.ninja6.sessionpulse.config;

import org.bukkit.configuration.file.YamlConfiguration;

import java.io.IOException;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The one way these tests build a {@link PluginConfig}.
 *
 * <p>A standalone {@link YamlConfiguration} needs no server, no plugin instance and no
 * MockBukkit, which is why the whole suite for this issue can be plain JUnit. Gradle sets
 * the test task's working directory to the project directory - the same assumption
 * {@code SourceTree} in the scheduler tests already relies on - so the shipped resource is
 * readable by relative path.
 */
final class ConfigFixture {

    /** The shipped default, as it sits in the source tree and as it is packaged. */
    static final Path SHIPPED = Path.of("src", "main", "resources", "config.yml");

    private ConfigFixture() {
    }

    /** Parses a YAML document exactly as written. */
    static PluginConfig parse(String yaml) {
        return new PluginConfig(YamlConfiguration.loadConfiguration(new StringReader(yaml)));
    }

    /** The shipped {@code config.yml} as text. */
    static String shippedText() {
        try {
            return Files.readString(SHIPPED, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(
                    "Could not read " + SHIPPED.toAbsolutePath()
                            + ". The test task's working directory must be the project "
                            + "directory.", e);
        }
    }

    /** The shipped {@code config.yml}, loaded. */
    static YamlConfiguration shipped() {
        return YamlConfiguration.loadConfiguration(new StringReader(shippedText()));
    }
}
