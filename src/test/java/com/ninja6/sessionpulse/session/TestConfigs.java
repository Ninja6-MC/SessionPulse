package com.ninja6.sessionpulse.session;

import com.ninja6.sessionpulse.config.PluginConfig;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.StringReader;

/**
 * Builds a {@link PluginConfig} for these tests without a server.
 *
 * <p>The configuration package has its own fixture, but it is package-private there and
 * these tests live in a different package. A standalone {@link YamlConfiguration} needs no
 * server, no plugin instance and no mock framework, which is the whole reason this suite
 * can be plain JUnit.
 */
final class TestConfigs {

    private TestConfigs() {
    }

    /** Everything at its default, which is what an empty document means. */
    static PluginConfig defaults() {
        return parse("");
    }

    /** A configuration with only {@code tracking.window-reset-hours} set. */
    static PluginConfig withWindowResetHours(int hours) {
        return parse("tracking:\n  window-reset-hours: " + hours + "\n");
    }

    /** Parses a YAML document exactly as written. */
    static PluginConfig parse(String yaml) {
        return new PluginConfig(YamlConfiguration.loadConfiguration(new StringReader(yaml)));
    }
}
