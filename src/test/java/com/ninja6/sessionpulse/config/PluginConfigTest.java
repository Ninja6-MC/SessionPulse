package com.ninja6.sessionpulse.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static com.ninja6.sessionpulse.config.ConfigFixture.parse;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Defaults, scalars, and the promise that nothing here ever throws. */
class PluginConfigTest {

    @Test
    @DisplayName("an empty file yields every documented default, silently")
    void emptyConfigYieldsEveryDocumentedDefault() {
        PluginConfig config = parse("");

        assertEquals(8, config.windowResetHours());
        assertEquals(AfkMode.AUTO, config.afkMode());
        assertEquals(300, config.afkIdleSeconds());
        assertEquals(5, config.flushIntervalMinutes());
        assertEquals("<gray>[<aqua>SessionPulse</aqua>]</gray> ", config.reminderPrefix());
        assertEquals(2, config.milestones().size());

        assertFalse(config.overtime().enabled());
        assertEquals(180, config.overtime().afterMinutes());
        assertEquals(30, config.overtime().everyMinutes());
        assertEquals("<red>You have been playing for <white><hours></white> hours.</red>",
                config.overtime().message());

        assertFalse(config.enforcement().enabled());
        assertEquals(240, config.enforcement().atMinutes());
        assertEquals("<yellow>Time for a break.</yellow>", config.enforcement().kickMessage());
        assertEquals(30, config.enforcement().cooldownMinutes());

        assertEquals(List.of(), config.warnings());
    }

    @Test
    @DisplayName("enforcement is off by default - the README's promise")
    void enforcementIsOffByDefault() {
        assertFalse(parse("").enforcement().enabled(),
                "The README promises SessionPulse never kicks anyone by default.");
    }

    @ParameterizedTest(name = "afk mode ''{0}'' is read")
    @ValueSource(strings = {"AUTO", "ESSENTIALS", "BUILT_IN", "OFF", "built_in", " essentials "})
    void afkModeIsRead(String written) {
        PluginConfig config = parse("tracking:\n  afk:\n    mode: \"" + written + "\"\n");
        assertEquals(AfkMode.valueOf(written.trim().toUpperCase(java.util.Locale.ROOT)),
                config.afkMode());
        assertEquals(List.of(), config.warnings());
    }

    @Test
    @DisplayName("a typo in afk mode falls back to AUTO and lists the valid values")
    void afkModeFallsBackOnATypo() {
        PluginConfig config = parse("tracking:\n  afk:\n    mode: ESENTIALS\n");

        assertEquals(AfkMode.AUTO, config.afkMode());
        assertEquals(1, config.warnings().size());
        String warning = config.warnings().get(0);
        assertTrue(warning.contains("ESENTIALS"), warning);
        assertTrue(warning.contains("AUTO"), warning);
        assertTrue(warning.contains("ESSENTIALS"), warning);
        assertTrue(warning.contains("BUILT_IN"), warning);
        assertTrue(warning.contains("OFF"), warning);
    }

    @Test
    @DisplayName("an unusable prefix falls back to the shipped one and says so")
    void anUnusablePrefixFallsBackAndWarns() {
        PluginConfig config = parse("reminders:\n  prefix: \"<gray>unclosed\"\n");

        assertEquals("<gray>[<aqua>SessionPulse</aqua>]</gray> ", config.reminderPrefix());
        assertEquals(1, config.warnings().size());
        assertTrue(config.warnings().get(0).startsWith("reminders.prefix"));
    }

    @Test
    @DisplayName("an empty prefix is honoured - it is how you turn the prefix off")
    void anEmptyPrefixIsHonoured() {
        PluginConfig config = parse("reminders:\n  prefix: \"\"\n");
        assertEquals("", config.reminderPrefix());
        assertEquals(List.of(), config.warnings());
    }

    @Test
    @DisplayName("an unusable overtime message does not switch overtime off")
    void anUnusableOvertimeMessageFallsBackAndDoesNotDisableOvertime() {
        PluginConfig config = parse("reminders:\n  overtime:\n    enabled: true\n"
                + "    message: \"<red>unclosed\"\n");

        assertTrue(config.overtime().enabled(), "A typo must not become a policy change.");
        assertEquals("<red>You have been playing for <white><hours></white> hours.</red>",
                config.overtime().message());
        assertEquals(1, config.warnings().size());
    }

    @Test
    @DisplayName("an unusable kick message does not switch enforcement off")
    void anUnusableKickMessageFallsBackAndDoesNotDisableEnforcement() {
        PluginConfig config = parse("enforcement:\n  enabled: true\n"
                + "  kick-message: \"<red>unclosed\"\n");

        assertTrue(config.enforcement().enabled());
        assertEquals("<yellow>Time for a break.</yellow>", config.enforcement().kickMessage());
        assertEquals(1, config.warnings().size());
    }

    @ParameterizedTest(name = "a section that is a scalar ({0}) does not throw")
    @ValueSource(strings = {"tracking: 5", "reminders: 5", "enforcement: 5",
        "tracking:\n  afk: 5", "reminders:\n  overtime: 5"})
    void aSectionThatIsAScalarDoesNotThrow(String yaml) {
        PluginConfig config = assertDoesNotThrow(() -> parse(yaml));
        assertEquals(8, config.windowResetHours());
        assertEquals(240, config.enforcement().atMinutes());
    }

    @Test
    @DisplayName("the warning list cannot be modified by a caller")
    void warningsAreImmutable() {
        List<String> warnings = parse("").warnings();
        assertThrows(UnsupportedOperationException.class, () -> warnings.add("nope"));
    }

    @Test
    @DisplayName("nothing in this class ever throws, whatever the file says")
    void nothingInThisClassEverThrows() {
        List<String> hostile = List.of(
                "",
                "\n\n\n",
                "tracking: null",
                "tracking:\n  window-reset-hours: \"not a number\"",
                "tracking:\n  window-reset-hours: []",
                "tracking:\n  afk:\n    mode: []",
                "reminders:\n  prefix: 5",
                "reminders:\n  prefix: []",
                "reminders:\n  milestones: {}",
                "reminders:\n  milestones:\n    - null",
                "reminders:\n  milestones:\n    - minute: {}",
                "reminders:\n  milestones:\n    - minute: 60\n      message: []",
                "reminders:\n  overtime: []",
                "enforcement:\n  enabled: \"maybe\"",
                "enforcement:\n  cooldown-minutes: 9999999999");

        for (String yaml : hostile) {
            assertDoesNotThrow(() -> parse(yaml), () -> "threw on:\n" + yaml);
        }

        StringBuilder huge = new StringBuilder("reminders:\n  milestones:\n");
        for (int i = 0; i < 5000; i++) {
            huge.append("    - minute: ").append(i).append("\n      message: \"m\"\n");
        }
        assertDoesNotThrow(() -> parse(huge.toString()));
    }

    @Test
    @DisplayName("a completely garbage file still produces a working configuration")
    void aCompletelyGarbageFileStillProducesAWorkingConfig() {
        String yaml = """
                tracking:
                  window-reset-hours: 999
                  afk:
                    mode: ESENTIALS
                    idle-seconds: 1
                  flush-interval-minutes: 0
                reminders:
                  prefix: "<red>unclosed"
                  milestones:
                    - minute: 60
                      message: "<aqua>ok</aqua>"
                    - minute: 60
                      message: "<aqua>dupe</aqua>"
                    - minute: -5
                      message: "x"
                    - minute: 90
                      message: "<red>oops"
                    - 120
                    - minute: 150
                      message: "ok"
                      sound: DEFINITELY_NOT_A_SOUND
                  overtime:
                    enabled: true
                    after-minutes: 0
                    every-minutes: 99999
                    message: "<red>fine</red>"
                enforcement:
                  enabled: true
                  at-minutes: 0
                  kick-message: "<red>unclosed"
                  cooldown-minutes: 0
                """;

        PluginConfig config = assertDoesNotThrow(() -> parse(yaml));

        // Every scalar is usable, in range, whatever was written.
        assertEquals(168, config.windowResetHours());
        assertEquals(AfkMode.AUTO, config.afkMode());
        assertEquals(30, config.afkIdleSeconds());
        assertEquals(1, config.flushIntervalMinutes());
        assertEquals("<gray>[<aqua>SessionPulse</aqua>]</gray> ", config.reminderPrefix());
        assertEquals(1, config.overtime().afterMinutes());
        assertEquals(1440, config.overtime().everyMinutes());
        assertEquals(1, config.enforcement().atMinutes());
        assertEquals("<yellow>Time for a break.</yellow>", config.enforcement().kickMessage());
        assertEquals(1, config.enforcement().cooldownMinutes());

        // The features the operator switched on are still on.
        assertTrue(config.overtime().enabled());
        assertTrue(config.enforcement().enabled());

        // The two usable milestones survived; the unknown sound cost only the sound.
        assertEquals(List.of(60, 150),
                config.milestones().stream().map(Milestone::minute).toList());
        assertEquals("<aqua>ok</aqua>", config.milestones().get(0).message());

        assertEquals(15, config.warnings().size(), () -> "warnings: " + config.warnings());
        for (String warning : config.warnings()) {
            assertTrue(warning.matches("^(tracking|reminders|enforcement)\\..*"),
                    "every warning must name the key or the entry it is about: " + warning);
        }
    }
}
