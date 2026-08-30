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

    @ParameterizedTest(name = "a prefix that is not text ({0}) falls back and is named")
    @ValueSource(strings = {"[]", "5", "[1, 2]", "true"})
    void aPrefixThatIsNotTextFallsBackAndIsNamed(String written) {
        // getString stringifies whatever it finds, so `prefix: []` used to become the
        // literal prefix "[]" and `prefix: 5` the literal prefix "5", both in silence.
        PluginConfig config = parse("reminders:\n  prefix: " + written + "\n");

        assertEquals("<gray>[<aqua>SessionPulse</aqua>]</gray> ", config.reminderPrefix());
        assertEquals(1, config.warnings().size(), () -> "warnings: " + config.warnings());
        String warning = config.warnings().get(0);
        assertTrue(warning.startsWith("reminders.prefix"), warning);
        assertTrue(warning.contains("not text"), warning);
    }

    @Test
    @DisplayName("an overtime message that is not text falls back without switching overtime off")
    void anOvertimeMessageThatIsNotTextFallsBack() {
        PluginConfig config = parse(
                "reminders:\n  overtime:\n    enabled: true\n    message: []\n");

        assertTrue(config.overtime().enabled());
        assertEquals("<red>You have been playing for <white><hours></white> hours.</red>",
                config.overtime().message());
        assertTrue(config.warnings().get(0).contains("not text"), config.warnings().toString());
    }

    @Test
    @DisplayName("a kick message that is not text falls back without switching enforcement off")
    void aKickMessageThatIsNotTextFallsBack() {
        PluginConfig config = parse("enforcement:\n  enabled: true\n  kick-message: 5\n");

        assertTrue(config.enforcement().enabled());
        assertEquals("<yellow>Time for a break.</yellow>", config.enforcement().kickMessage());
        assertTrue(config.warnings().get(0).contains("not text"), config.warnings().toString());
    }

    @Test
    @DisplayName("a quoted number is a usable message - quoting is not the mistake being caught")
    void aQuotedNumberIsAUsableMessage() {
        PluginConfig config = parse("reminders:\n  prefix: \"5\"\n");
        assertEquals("5", config.reminderPrefix());
        assertEquals(List.of(), config.warnings());
    }

    @Test
    @DisplayName("an enabled flag that is neither true nor false is named, not read as off")
    void anUnreadableEnabledFlagIsNamed() {
        PluginConfig config = parse("enforcement:\n  enabled: \"maybe\"\n");

        assertFalse(config.enforcement().enabled());
        String warning = config.warnings().get(0);
        assertTrue(warning.startsWith("enforcement.enabled"), warning);
        assertTrue(warning.contains("not true or false"), warning);
    }

    @ParameterizedTest(name = "enabled: {0} is read as written, silently")
    @ValueSource(strings = {"true", "false", "\"true\"", "\"false\"", "yes", "no"})
    void aReadableEnabledFlagIsSilent(String written) {
        PluginConfig config = parse("enforcement:\n  enabled: " + written + "\n");
        assertEquals(written.replace("\"", "").matches("true|yes"),
                config.enforcement().enabled(), written);
        assertEquals(List.of(), config.warnings());
    }

    @Test
    @DisplayName("every warning opens with the key it is about")
    void everyWarningOpensWithTheKeyItIsAbout() {
        // .github/scripts/boot-test.sh greps the server log for exactly this shape to catch
        // a shipped config.yml that produced any warning at all. An alternation of warning
        // texts there could not be kept in step with this class - it silently fell behind
        // and caught half of them - so the boot leg matches on the key instead, and this
        // test is what makes that safe to rely on.
        List<String> malformed = List.of(
                "tracking:\n  window-reset-hours: abc",
                "tracking:\n  window-reset-hours: 8.7",
                "tracking:\n  window-reset-hours: 99999999999999",
                "tracking:\n  window-reset-hours: 0",
                "tracking:\n  afk:\n    mode: ESENTIALS",
                "tracking:\n  afk:\n    idle-seconds: []",
                "tracking:\n  flush-interval-minutes: 0",
                "reminders:\n  prefix: \"<red>unclosed\"",
                "reminders:\n  prefix: []",
                "reminders:\n  milestones: 60",
                "reminders:\n  milestones:\n    - 60",
                "reminders:\n  milestones:\n    - message: \"hi\"",
                "reminders:\n  milestones:\n    - minute: sixty\n      message: \"hi\"",
                "reminders:\n  milestones:\n    - minute: 0\n      message: \"hi\"",
                "reminders:\n  milestones:\n    - minute: 60\n      message: \"<red>oops\"",
                "reminders:\n  milestones:\n    - minute: 60",
                "reminders:\n  milestones:\n    - minute: 60\n      message: \"hi\"\n"
                        + "      sound: NOT_A_SOUND",
                "reminders:\n  milestones:\n    - minute: 60\n      message: \"a\"\n"
                        + "    - minute: 60\n      message: \"b\"",
                "reminders:\n  overtime:\n    enabled: \"maybe\"",
                "reminders:\n  overtime:\n    every-minutes: 99999",
                "reminders:\n  overtime:\n    message: 5",
                "enforcement:\n  enabled: \"maybe\"",
                "enforcement:\n  at-minutes: -1",
                "enforcement:\n  kick-message: []",
                "enforcement:\n  cooldown-minutes: 9999999999");

        int seen = 0;
        for (String yaml : malformed) {
            List<String> warnings = parse(yaml).warnings();
            assertFalse(warnings.isEmpty(), () -> "produced no warning at all:\n" + yaml);
            for (String warning : warnings) {
                seen++;
                assertTrue(warning.matches("^(tracking|reminders|enforcement)\\.[a-z-]+.*"),
                        "Every warning must open with the config key it is about, because "
                                + "boot-test.sh matches on that shape: " + warning);
            }
        }
        assertTrue(seen >= malformed.size(), "expected at least one warning per case");
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
