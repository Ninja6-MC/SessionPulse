package com.ninja6.sessionpulse.config;

import org.bukkit.Sound;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static com.ninja6.sessionpulse.config.ConfigFixture.parse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The milestone list: what is kept, what is skipped, and how a skip is named.
 *
 * <p>This class also owns the milestone-minute boundary, which is deliberately absent from
 * {@code ClampBoundaryTest}'s table because it is the one range in the plugin that
 * <b>skips the entry</b> rather than correcting the value.
 */
class MilestoneValidationTest {

    private static String list(String... entries) {
        StringBuilder yaml = new StringBuilder("reminders:\n  milestones:\n");
        for (String entry : entries) {
            yaml.append(entry).append('\n');
        }
        return yaml.toString();
    }

    private static String entry(String minute, String body) {
        return "    - minute: " + minute + "\n" + body;
    }

    private static String only(List<String> warnings) {
        assertEquals(1, warnings.size(), () -> "warnings: " + warnings);
        return warnings.get(0);
    }

    // ---------------------------------------------------------------- the minute boundary

    @Test
    @DisplayName("a negative minute is skipped and named, never clamped")
    void negativeMinuteIsSkippedAndNamed() {
        PluginConfig config = parse(list(entry("-5", "      message: \"<red>hi</red>\"")));

        assertEquals(List.of(), config.milestones());
        String warning = only(config.warnings());
        assertTrue(warning.contains("entry 1"), warning);
        assertTrue(warning.contains("-5"), warning);
        assertTrue(warning.contains("Skipping"), warning);
        assertTrue(warning.contains("not clamped"), warning);
    }

    @Test
    @DisplayName("minute 0 is skipped - it would fire before the player has played")
    void zeroMinuteIsSkipped() {
        PluginConfig config = parse(list(entry("0", "      message: \"<red>hi</red>\"")));
        assertEquals(List.of(), config.milestones());
        assertTrue(only(config.warnings()).contains("outside 1-10080"));
    }

    @ParameterizedTest(name = "minute {0} is kept")
    @ValueSource(ints = {1, 10_080})
    void theMinuteBoundaryIsKept(int minute) {
        PluginConfig config = parse(
                list(entry(String.valueOf(minute), "      message: \"<red>hi</red>\"")));

        assertEquals(1, config.milestones().size());
        assertEquals(minute, config.milestones().get(0).minute());
        assertEquals(List.of(), config.warnings());
    }

    @Test
    @DisplayName("minute 10081 is skipped - nothing above a week of play can ever fire")
    void minute10081IsSkipped() {
        PluginConfig config = parse(list(entry("10081", "      message: \"<red>hi</red>\"")));
        assertEquals(List.of(), config.milestones());
        assertTrue(only(config.warnings()).contains("10081"));
    }

    // ---------------------------------------------------------------- malformed minutes

    @Test
    @DisplayName("an entry with no minute is skipped")
    void anEntryWithNoMinuteIsSkipped() {
        PluginConfig config = parse("reminders:\n  milestones:\n"
                + "    - message: \"<red>hi</red>\"\n");

        assertEquals(List.of(), config.milestones());
        String warning = only(config.warnings());
        assertTrue(warning.contains("entry 1"), warning);
        assertTrue(warning.contains("no 'minute'"), warning);
    }

    @Test
    @DisplayName("a non-numeric minute is skipped")
    void aNonNumericMinuteIsSkipped() {
        PluginConfig config = parse(list(entry("sixty", "      message: \"<red>hi</red>\"")));
        assertEquals(List.of(), config.milestones());
        assertTrue(only(config.warnings()).contains("not a whole number of minutes"));
    }

    @Test
    @DisplayName("a decimal minute is skipped rather than truncated")
    void decimalMinuteIsSkipped() {
        PluginConfig config = parse(list(entry("60.9", "      message: \"<red>hi</red>\"")));

        assertEquals(List.of(), config.milestones(),
                "60.9 must not silently become 60 - that invents a value nobody wrote.");
        String warning = only(config.warnings());
        assertTrue(warning.contains("60.9"), warning);
        assertTrue(warning.contains("not a whole number of minutes"), warning);
    }

    @Test
    @DisplayName("a quoted number is a number - quoting is not the mistake being caught")
    void aQuotedMinuteIsAccepted() {
        PluginConfig config = parse(list(entry("\"60\"", "      message: \"<red>hi</red>\"")));
        assertEquals(1, config.milestones().size());
        assertEquals(60, config.milestones().get(0).minute());
        assertEquals(List.of(), config.warnings());
    }

    @Test
    @DisplayName("a list element that is not a block of settings is skipped, not dropped")
    void aNonMapEntryIsSkipped() {
        PluginConfig config = parse("reminders:\n  milestones:\n    - 60\n");
        assertEquals(List.of(), config.milestones());
        assertTrue(only(config.warnings()).contains("is not a block of settings"));
    }

    // ---------------------------------------------------------------- duplicates

    @Test
    @DisplayName("a duplicate minute keeps the first entry and names both")
    void duplicateMinuteKeepsTheFirst() {
        PluginConfig config = parse(list(
                entry("60", "      message: \"<red>first</red>\""),
                entry("60", "      message: \"<red>second</red>\"")));

        assertEquals(1, config.milestones().size());
        assertEquals("<red>first</red>", config.milestones().get(0).message());
        String warning = only(config.warnings());
        assertTrue(warning.contains("entry 2"), warning);
        assertTrue(warning.contains("entry 1"), warning);
    }

    @Test
    @DisplayName("a skipped entry does not reserve its minute")
    void duplicateOfASkippedEntryIsNotADuplicate() {
        PluginConfig config = parse(list(
                entry("-5", "      message: \"<red>bad</red>\""),
                entry("60", "      message: \"<red>good</red>\"")));

        assertEquals(1, config.milestones().size());
        assertEquals(60, config.milestones().get(0).minute());
        assertEquals(1, config.warnings().size(), () -> "warnings: " + config.warnings());
    }

    // ---------------------------------------------------------------- MiniMessage

    @ParameterizedTest(name = "an unclosed tag in ''{0}'' skips the whole entry")
    @ValueSource(strings = {"message", "action-bar", "title", "subtitle"})
    void eachOfTheFourTextFieldsIsValidated(String field) {
        PluginConfig config = parse(list(entry("60", "      " + field + ": \"<yellow>oops\"")));

        assertEquals(List.of(), config.milestones());
        String warning = only(config.warnings());
        assertTrue(warning.contains("entry 1"), warning);
        assertTrue(warning.contains("'" + field + "'"), warning);
        assertTrue(warning.contains("not valid MiniMessage"), warning);
        assertTrue(warning.contains("Skipping the whole entry"), warning);
    }

    @Test
    @DisplayName("tags closed out of order are rejected too, not only unclosed ones")
    void crossedTagsAreRejected() {
        PluginConfig config = parse(
                list(entry("60", "      message: \"<red>a<blue>b</red></blue>\"")));

        assertEquals(List.of(), config.milestones());
        assertTrue(only(config.warnings()).contains("not valid MiniMessage"));
    }

    @Test
    @DisplayName("an unknown tag is text, not an error - MiniMessage renders it literally")
    void anUnknownTagIsNotAnError() {
        PluginConfig config = parse(
                list(entry("60", "      message: \"<bogustag>hi</bogustag>\"")));

        assertEquals(1, config.milestones().size());
        assertEquals(List.of(), config.warnings());
    }

    // ---------------------------------------------------------------- sounds

    @Test
    @DisplayName("a known sound is resolved")
    void aKnownSoundIsResolved() {
        PluginConfig config = parse(list(entry("60",
                "      message: \"<red>hi</red>\"\n      sound: BLOCK_NOTE_BLOCK_CHIME")));

        assertEquals(Sound.BLOCK_NOTE_BLOCK_CHIME, config.milestones().get(0).sound());
        assertEquals(List.of(), config.warnings());
    }

    @Test
    @DisplayName("an unknown sound costs the sound, not the milestone")
    void anUnknownSoundKeepsTheMilestoneAndSilencesIt() {
        PluginConfig config = parse(list(entry("60",
                "      message: \"<red>hi</red>\"\n      sound: DEFINITELY_NOT_A_SOUND")));

        assertEquals(1, config.milestones().size());
        assertNull(config.milestones().get(0).sound());
        assertEquals("<red>hi</red>", config.milestones().get(0).message());
        assertTrue(only(config.warnings()).contains("DEFINITELY_NOT_A_SOUND"));
    }

    @Test
    @DisplayName("a sound with no text is a legitimate milestone")
    void aSoundAloneIsEnoughToShow() {
        PluginConfig config = parse(
                list(entry("60", "      sound: BLOCK_NOTE_BLOCK_CHIME")));

        assertEquals(1, config.milestones().size());
        assertNotNull(config.milestones().get(0).sound());
        assertEquals(List.of(), config.warnings());
    }

    // ---------------------------------------------------------------- shape of the list

    @Test
    @DisplayName("an entry that would show nothing is skipped")
    void anEntryWithNothingToShowIsSkipped() {
        PluginConfig config = parse(list(entry("90", "")));

        assertEquals(List.of(), config.milestones());
        assertTrue(only(config.warnings()).contains("would fire and do nothing"));
    }

    @Test
    @DisplayName("an explicitly empty list means no milestones, and no complaint")
    void anExplicitlyEmptyListMeansNoMilestones() {
        PluginConfig config = parse("reminders:\n  milestones: []\n");
        assertEquals(List.of(), config.milestones());
        assertEquals(List.of(), config.warnings());
    }

    @Test
    @DisplayName("an absent milestones key restores the two shipped milestones")
    void anAbsentMilestonesKeyRestoresTheShippedTwo() {
        PluginConfig config = parse("");
        assertEquals(List.of(60, 120), config.milestones().stream().map(Milestone::minute).toList());
        assertEquals(List.of(), config.warnings());
    }

    @Test
    @DisplayName("a milestones key that is not a list falls back to the built-in two")
    void aScalarMilestonesKeyFallsBack() {
        PluginConfig config = parse("reminders:\n  milestones: 60\n");
        assertEquals(2, config.milestones().size());
        assertTrue(only(config.warnings()).contains("is not a list"));
    }

    @Test
    @DisplayName("milestones are sorted by minute whatever order they were written in")
    void milestonesAreSortedByMinute() {
        PluginConfig config = parse(list(
                entry("120", "      message: \"c\""),
                entry("30", "      message: \"a\""),
                entry("60", "      message: \"b\"")));

        assertEquals(List.of(30, 60, 120),
                config.milestones().stream().map(Milestone::minute).toList());
        assertEquals(List.of(), config.warnings());
    }

    @Test
    @DisplayName("the milestone list cannot be modified by a caller")
    void theListIsUnmodifiable() {
        List<Milestone> milestones = parse(list(entry("60", "      message: \"a\""))).milestones();
        assertThrows(UnsupportedOperationException.class,
                () -> milestones.add(new Milestone(1, "x", null, null, null, null)));
    }

    @Test
    @DisplayName("entry numbers count positions in the file, not survivors")
    void entryNumbersCountFilePositionsNotSurvivors() {
        PluginConfig config = parse(list(
                entry("60", "      message: \"ok\""),
                entry("-5", "      message: \"bad\""),
                entry("sixty", "      message: \"bad\""),
                entry("0", "      message: \"bad\"")));

        assertEquals(1, config.milestones().size());
        assertEquals(3, config.warnings().size(), () -> "warnings: " + config.warnings());
        String last = config.warnings().get(config.warnings().size() - 1);
        assertTrue(last.contains("entry 4"), last);
    }

    @Test
    @DisplayName("one bad entry does not cost the good ones")
    void oneBadEntryDoesNotCostTheGoodOnes() {
        PluginConfig config = parse(list(
                entry("30", "      message: \"<green>a</green>\""),
                entry("60", "      message: \"<red>unclosed\""),
                entry("90", "      message: \"<green>c</green>\"")));

        assertEquals(List.of(30, 90),
                config.milestones().stream().map(Milestone::minute).toList());
        assertEquals(1, config.warnings().size(), () -> "warnings: " + config.warnings());
    }
}
