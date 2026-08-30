package com.ninja6.sessionpulse.config;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The shipped {@code src/main/resources/config.yml} against the code that reads it.
 *
 * <p>The two carry the same defaults twice, once as Java and once as commented YAML, and
 * that duplication is deliberate - an operator has to be able to read their own file and
 * see what deleting a key would give them. These tests are what stop the two copies
 * drifting apart.
 */
class DefaultConfigResourceTest {

    @Test
    @DisplayName("the shipped file loads with no warnings at all")
    void theShippedFileLoadsWithNoWarnings() {
        PluginConfig config = new PluginConfig(ConfigFixture.shipped());
        assertEquals(List.of(), config.warnings(),
                "A shipped value is outside its own documented clamp, or a shipped payload "
                        + "does not survive the parser this plugin validates with.");
    }

    @Test
    @DisplayName("the shipped file and the code defaults agree, field for field")
    void theShippedFileAndTheCodeDefaultsAgree() {
        assertSameSettings(new PluginConfig(ConfigFixture.shipped()), ConfigFixture.parse(""));
    }

    /**
     * The production wiring, not just the two files.
     *
     * <p>{@code JavaPlugin#reloadConfig()} calls {@code setDefaults} with the config.yml
     * from the jar, so in production a key the operator deleted falls through to the
     * shipped file rather than to the code default - which means the code default is
     * reached only for a key the shipped file does not carry either. Every other test here
     * parses a bare {@code YamlConfiguration}, which has no defaults, so this is the only
     * one that exercises the shape {@code getConfig()} actually has.
     */
    @Test
    @DisplayName("an operator file with every key deleted falls through to the shipped one")
    void anEmptyOperatorFileFallsThroughToTheShippedDefaults() {
        YamlConfiguration operatorFile = YamlConfiguration.loadConfiguration(new StringReader(""));
        operatorFile.setDefaults(ConfigFixture.shipped());

        PluginConfig throughDefaults = new PluginConfig(operatorFile);

        assertEquals(List.of(), throughDefaults.warnings());
        assertSameSettings(throughDefaults, ConfigFixture.parse(""));
    }

    @Test
    @DisplayName("every shipped MiniMessage payload survives the parser this plugin uses")
    void everyShippedPayloadSurvivesTheStrictParser() {
        PluginConfig config = new PluginConfig(ConfigFixture.shipped());

        List<String> payloads = new ArrayList<>();
        payloads.add(config.reminderPrefix());
        payloads.add(config.overtime().message());
        payloads.add(config.enforcement().kickMessage());
        for (Milestone milestone : config.milestones()) {
            payloads.add(milestone.message());
            payloads.add(milestone.actionBar());
            payloads.add(milestone.title());
            payloads.add(milestone.subtitle());
        }

        for (String payload : payloads) {
            assertNull(MessageCheck.problem(payload),
                    () -> "The shipped config.yml carries a payload this plugin would itself "
                            + "reject: " + payload);
        }
    }

    @Test
    @DisplayName("the shipped file documents every clamp range")
    void theShippedFileDocumentsEveryClampRange() throws Exception {
        String text = ConfigFixture.shippedText();

        Set<String> constants = new LinkedHashSet<>();
        for (Field field : PluginConfig.class.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()) && field.getName().startsWith("MIN_")) {
                constants.add(field.getName().substring("MIN_".length()));
            }
        }

        for (String constant : constants) {
            Field min = PluginConfig.class.getDeclaredField("MIN_" + constant);
            Field max = PluginConfig.class.getDeclaredField("MAX_" + constant);
            min.setAccessible(true);
            max.setAccessible(true);
            String range = min.getInt(null) + "-" + max.getInt(null);
            assertTrue(text.contains(range),
                    "config.yml never states the range " + range + " for " + constant
                            + ". Every value has to carry its clamp range in the file.");
        }
    }

    @Test
    @DisplayName("the shipped config.yml carries no '$'")
    void noDollarSign() {
        assertFalse(ConfigFixture.shippedText().contains("$"),
                "processResources runs expand() - Groovy's SimpleTemplateEngine - and it treats "
                        + "every $ in a matched file as a placeholder. filesMatching(\"plugin.yml\") "
                        + "does not match this file today, so a $ here is safe until somebody "
                        + "widens that pattern, at which point the build fails with a "
                        + "MissingPropertyException naming a property rather than this file. If "
                        + "you need a $ in a payload, narrow filesMatching - do NOT escape it as "
                        + "\\$, because this file is never templated and the backslash would "
                        + "reach a player's screen.");
    }

    /** Compares two configurations by every value they expose. */
    private static void assertSameSettings(PluginConfig actual, PluginConfig expected) {
        assertEquals(expected.windowResetHours(), actual.windowResetHours(),
                "tracking.window-reset-hours");
        assertEquals(expected.afkMode(), actual.afkMode(), "tracking.afk.mode");
        assertEquals(expected.afkIdleSeconds(), actual.afkIdleSeconds(),
                "tracking.afk.idle-seconds");
        assertEquals(expected.flushIntervalMinutes(), actual.flushIntervalMinutes(),
                "tracking.flush-interval-minutes");
        assertEquals(expected.reminderPrefix(), actual.reminderPrefix(), "reminders.prefix");
        assertEquals(expected.milestones(), actual.milestones(), "reminders.milestones");
        assertEquals(expected.overtime(), actual.overtime(), "reminders.overtime");
        assertEquals(expected.enforcement(), actual.enforcement(), "enforcement");
    }
}
