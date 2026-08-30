package com.ninja6.sessionpulse.config;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.ToIntFunction;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every clamped scalar, at both ends of its range and one step past each.
 *
 * <p>The table below is the contract, and {@link #everyClampConstantIsCovered()} is what
 * keeps it honest: a new {@code MIN_}/{@code MAX_} pair added to {@link PluginConfig}
 * without a row here fails the build rather than quietly shipping unverified.
 */
class ClampBoundaryTest {

    /**
     * One clamped key.
     *
     * @param constant the shared suffix of its {@code MIN_}/{@code MAX_} constants, which
     *                 is how the meta-test below matches a row to a pair
     */
    record Clamp(String constant, String key, int min, int def, int max,
                 ToIntFunction<PluginConfig> read) {
    }

    static Stream<Clamp> clamps() {
        return Stream.of(
                new Clamp("WINDOW_RESET_HOURS", "tracking.window-reset-hours",
                        1, 8, 168, PluginConfig::windowResetHours),
                new Clamp("AFK_IDLE_SECONDS", "tracking.afk.idle-seconds",
                        30, 300, 3600, PluginConfig::afkIdleSeconds),
                new Clamp("FLUSH_INTERVAL_MINUTES", "tracking.flush-interval-minutes",
                        1, 5, 60, PluginConfig::flushIntervalMinutes),
                new Clamp("OVERTIME_AFTER_MINUTES", "reminders.overtime.after-minutes",
                        1, 180, 10_080, c -> c.overtime().afterMinutes()),
                new Clamp("OVERTIME_EVERY_MINUTES", "reminders.overtime.every-minutes",
                        1, 30, 1440, c -> c.overtime().everyMinutes()),
                new Clamp("ENFORCEMENT_AT_MINUTES", "enforcement.at-minutes",
                        1, 240, 10_080, c -> c.enforcement().atMinutes()),
                new Clamp("ENFORCEMENT_COOLDOWN_MINUTES", "enforcement.cooldown-minutes",
                        1, 30, 1440, c -> c.enforcement().cooldownMinutes()));
    }

    /**
     * The milestone minute has a {@code MIN_}/{@code MAX_} pair and no row above, because
     * it is the one boundary that <b>skips the entry</b> instead of clamping the value.
     * Its four boundary cases live in {@code MilestoneValidationTest}.
     */
    private static final Set<String> COVERED_ELSEWHERE = Set.of("MILESTONE_MINUTE");

    private static PluginConfig with(String key, Object value) {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set(key, value);
        return new PluginConfig(yaml);
    }

    @ParameterizedTest(name = "{0}: below the minimum is raised to it")
    @MethodSource("clamps")
    void belowTheMinimumIsRaisedAndNamed(Clamp clamp) {
        int written = clamp.min() - 1;
        PluginConfig config = with(clamp.key(), written);

        assertEquals(clamp.min(), clamp.read().applyAsInt(config));
        assertEquals(1, config.warnings().size(), () -> "warnings: " + config.warnings());
        String warning = config.warnings().get(0);
        assertTrue(warning.contains(clamp.key()), warning);
        assertTrue(warning.contains(String.valueOf(written)), warning);
        assertTrue(warning.contains(clamp.min() + "-" + clamp.max()), warning);
        assertTrue(warning.contains("Using " + clamp.min() + " instead"), warning);
    }

    @ParameterizedTest(name = "{0}: the minimum itself is silent")
    @MethodSource("clamps")
    void theMinimumIsAccepted(Clamp clamp) {
        PluginConfig config = with(clamp.key(), clamp.min());
        assertEquals(clamp.min(), clamp.read().applyAsInt(config));
        assertEquals(List.of(), config.warnings());
    }

    @ParameterizedTest(name = "{0}: the maximum itself is silent")
    @MethodSource("clamps")
    void theMaximumIsAccepted(Clamp clamp) {
        PluginConfig config = with(clamp.key(), clamp.max());
        assertEquals(clamp.max(), clamp.read().applyAsInt(config));
        assertEquals(List.of(), config.warnings());
    }

    @ParameterizedTest(name = "{0}: above the maximum is lowered to it")
    @MethodSource("clamps")
    void aboveTheMaximumIsLoweredAndNamed(Clamp clamp) {
        int written = clamp.max() + 1;
        PluginConfig config = with(clamp.key(), written);

        assertEquals(clamp.max(), clamp.read().applyAsInt(config));
        assertEquals(1, config.warnings().size(), () -> "warnings: " + config.warnings());
        String warning = config.warnings().get(0);
        assertTrue(warning.contains(clamp.key()), warning);
        assertTrue(warning.contains(String.valueOf(written)), warning);
        assertTrue(warning.contains("Using " + clamp.max() + " instead"), warning);
    }

    @ParameterizedTest(name = "{0}: an absent key uses the documented default")
    @MethodSource("clamps")
    void anAbsentKeyUsesTheDocumentedDefault(Clamp clamp) {
        PluginConfig config = ConfigFixture.parse("");
        assertEquals(clamp.def(), clamp.read().applyAsInt(config));
        assertEquals(List.of(), config.warnings());
    }

    // ------------------------------------------------------- values that are not numbers

    /**
     * The four shapes a scalar can take that {@code getInt} used to swallow.
     *
     * <p>{@code getInt} returns the fallback for anything that is not a {@link Number} and
     * truncates a {@link Double}, so each of these read as the default with an empty
     * warning list - a value the operator never wrote, used in silence, which is exactly
     * what the class javadoc and the shipped config.yml header both promise cannot happen.
     */
    static Stream<Object> notWholeNumbers() {
        return Stream.of("abc", Boolean.TRUE, List.of(1, 2), 8.7d);
    }

    @ParameterizedTest(name = "{0}: a value that is not a whole number falls back and is named")
    @MethodSource("clamps")
    void aValueThatIsNotAWholeNumberFallsBackAndIsNamed(Clamp clamp) {
        notWholeNumbers().forEach(written -> {
            PluginConfig config = with(clamp.key(), written);

            assertEquals(clamp.def(), clamp.read().applyAsInt(config),
                    () -> clamp.key() + " read " + written + " as something usable");
            assertEquals(1, config.warnings().size(), () -> "warnings: " + config.warnings());
            String warning = config.warnings().get(0);
            assertTrue(warning.contains(clamp.key()), warning);
            assertTrue(warning.contains("'" + written + "'"), warning);
            assertTrue(warning.contains("not a whole number"), warning);
            assertTrue(warning.contains("Using " + clamp.def() + " instead"), warning);
        });
    }

    @ParameterizedTest(name = "{0}: a quoted number is still a number")
    @MethodSource("clamps")
    void aQuotedNumberIsStillANumber(Clamp clamp) {
        PluginConfig config = with(clamp.key(), String.valueOf(clamp.def()));
        assertEquals(clamp.def(), clamp.read().applyAsInt(config));
        assertEquals(List.of(), config.warnings());
    }

    @ParameterizedTest(name = "{0}: a value too large for an int is clamped, not wrapped")
    @MethodSource("clamps")
    void aValueThatOverflowsIntIsClampedAndQuotedAsWritten(Clamp clamp) {
        long written = 99_999_999_999_999L;
        PluginConfig config = with(clamp.key(), written);

        assertEquals(clamp.max(), clamp.read().applyAsInt(config));
        String warning = config.warnings().get(0);
        assertTrue(warning.contains(String.valueOf(written)),
                () -> "the warning must quote what is in the file: " + warning);
        assertTrue(warning.contains("Using " + clamp.max() + " instead"), warning);
    }

    @ParameterizedTest(name = "{0}: a value too small for an int is clamped, not wrapped")
    @MethodSource("clamps")
    void aValueThatUnderflowsIntIsClampedAndQuotedAsWritten(Clamp clamp) {
        long written = -99_999_999_999_999L;
        PluginConfig config = with(clamp.key(), written);

        assertEquals(clamp.min(), clamp.read().applyAsInt(config));
        String warning = config.warnings().get(0);
        assertTrue(warning.contains(String.valueOf(written)), warning);
        assertTrue(warning.contains("Using " + clamp.min() + " instead"), warning);
    }

    @Test
    @DisplayName("the clamp warning never names the int the value wrapped round to")
    void theClampWarningNeverNamesTheWrappedValue() {
        // 99999999999999 narrowed with intValue() is 276447231, and that is the number the
        // log used to print - one the operator would go looking for in their own file and
        // never find.
        PluginConfig config = with("tracking.window-reset-hours", 99_999_999_999_999L);

        assertEquals(168, config.windowResetHours());
        String warning = config.warnings().get(0);
        assertTrue(warning.contains("99999999999999"), warning);
        assertFalse(warning.contains("276447231"), warning);
    }

    @Test
    @DisplayName("a value already in range is never warned about")
    void aValueInRangeIsNeverWarnedAbout() {
        assertEquals(List.of(), ConfigFixture.parse("").warnings());
    }

    @Test
    @DisplayName("every MIN_/MAX_ pair in PluginConfig has a boundary case")
    void everyClampConstantIsCovered() throws Exception {
        Set<String> tabled = new LinkedHashSet<>();
        clamps().forEach(c -> tabled.add(c.constant()));

        Set<String> declared = new LinkedHashSet<>();
        for (Field field : PluginConfig.class.getDeclaredFields()) {
            if (!Modifier.isStatic(field.getModifiers())) {
                continue;
            }
            String name = field.getName();
            if (name.startsWith("MIN_")) {
                declared.add(name.substring("MIN_".length()));
            } else if (name.startsWith("MAX_")) {
                declared.add(name.substring("MAX_".length()));
            }
        }

        assertTrue(declared.containsAll(COVERED_ELSEWHERE),
                "COVERED_ELSEWHERE names a clamp that no longer exists: " + COVERED_ELSEWHERE
                        + " against " + declared);

        for (String constant : declared) {
            if (COVERED_ELSEWHERE.contains(constant)) {
                continue;
            }
            assertTrue(tabled.contains(constant),
                    "PluginConfig declares MIN_" + constant + "/MAX_" + constant
                            + " but ClampBoundaryTest has no row for it. Add one to clamps(), "
                            + "or - if the boundary skips rather than clamps - add it to "
                            + "COVERED_ELSEWHERE and say where its cases live.");
            // Both halves of the pair must exist; a lone MIN_ is a half-written clamp.
            PluginConfig.class.getDeclaredField("MIN_" + constant);
            PluginConfig.class.getDeclaredField("MAX_" + constant);
        }
    }
}
