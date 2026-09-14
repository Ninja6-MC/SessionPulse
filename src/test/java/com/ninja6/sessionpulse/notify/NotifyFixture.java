package com.ninja6.sessionpulse.notify;

import com.ninja6.sessionpulse.config.PluginConfig;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.StringReader;
import java.nio.file.Path;

/**
 * Builds configurations and notifiers for these tests without a server.
 *
 * <p>The configuration and session packages each have a fixture of their own, package-private
 * there. A {@link Notifier} built over {@code null} for its plugin is safe to render with,
 * because nothing reads the plugin until {@link Notifier#open()}.
 */
final class NotifyFixture {

    /**
     * The shipped default, by relative path: Gradle runs tests from the project directory,
     * the same assumption {@code ConfigFixture} makes.
     */
    static final Path SHIPPED = Path.of("src", "main", "resources", "config.yml");

    /** {@code PluginConfig.DEFAULT_OVERTIME_MESSAGE}, which is package-private there. */
    static final String OVERTIME_DEFAULT =
            "<red>You have been playing for <white><hours></white> hours.</red>";

    /** {@code PluginConfig.DEFAULT_KICK_MESSAGE}, which is package-private there. */
    static final String KICK_DEFAULT = "<yellow>Time for a break.</yellow>";

    private NotifyFixture() {
    }

    /** Parses a YAML document exactly as written. */
    static PluginConfig parse(String yaml) {
        return new PluginConfig(YamlConfiguration.loadConfiguration(new StringReader(yaml)));
    }

    /** A never-opened notifier over a configuration parsed from {@code yaml}. */
    static Notifier notifier(String yaml) {
        PluginConfig config = parse(yaml);
        return new Notifier(null, () -> config);
    }
}
