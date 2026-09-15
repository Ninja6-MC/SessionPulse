package com.ninja6.sessionpulse.notify;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Locale;
import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** What each placeholder renders to, and that no value is ever read as markup. */
class PlaceholdersTest {

    private final Notifier notifier = NotifyFixture.notifier("");

    private String render(String miniMessage, Placeholders placeholders) {
        return notifier.legacy(miniMessage, placeholders);
    }

    @Test
    @DisplayName("all four placeholders are filled")
    void allFourPlaceholdersAreFilled() {
        assertEquals("3.0|180|Steve|30", render("<hours>|<minutes>|<player>|<cooldown>",
                Placeholders.none().hours(10800).minutes(10800).player("Steve").cooldown(1800)));
    }

    @Test
    @DisplayName("hours are truncated to tenths and never negative")
    void hoursAreTruncatedToTenths() {
        assertEquals("0.0", render("<hours>", Placeholders.none().hours(0)));
        assertEquals("1.4", render("<hours>", Placeholders.none().hours(5399)));
        assertEquals("1.5", render("<hours>", Placeholders.none().hours(5400)));
        assertEquals("9.9", render("<hours>", Placeholders.none().hours(35999)));
        assertEquals("10.0", render("<hours>", Placeholders.none().hours(36000)));
        assertEquals("0.0", render("<hours>", Placeholders.none().hours(-1)));
    }

    @Test
    @DisplayName("hours ignore the default locale")
    void hoursIgnoreTheDefaultLocale() {
        // Mutates JVM-wide state and restores it; assumes tests in this suite run serially,
        // which is the JUnit default and what this build configures.
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.GERMANY);
            assertEquals("1.5", render("<hours>", Placeholders.none().hours(5400)));
        } finally {
            Locale.setDefault(previous);
        }
    }

    @Test
    @DisplayName("minutes are whole and truncated")
    void minutesAreWholeAndTruncated() {
        assertEquals("179", render("<minutes>", Placeholders.none().minutes(179 * 60 + 59)));
        assertEquals("0", render("<minutes>", Placeholders.none().minutes(-1)));
    }

    @Test
    @DisplayName("cooldown seconds round up to whole minutes")
    void cooldownRoundsUpToWholeMinutes() {
        assertEquals("0", render("<cooldown>", Placeholders.none().cooldown(0)));
        assertEquals("1", render("<cooldown>", Placeholders.none().cooldown(1)));
        assertEquals("1", render("<cooldown>", Placeholders.none().cooldown(60)));
        assertEquals("2", render("<cooldown>", Placeholders.none().cooldown(61)));
        assertEquals("0", render("<cooldown>", Placeholders.none().cooldown(-1)));
    }

    @Test
    @DisplayName("a player name cannot inject tags")
    void aPlayerNameCannotInjectTags() {
        String name = "</bold><click:run_command:'/op me'>boom";
        Component rendered = notifier.renderLine("<bold>Hi <player></bold>",
                Placeholders.none().player(name));

        assertNoInteraction(rendered);
        assertTrue(Notifier.LEGACY.serialize(rendered).contains("<click:run_command:'/op me'>boom"),
                "the name was not kept as text");
    }

    private static void assertNoInteraction(Component component) {
        assertNull(component.clickEvent(), "a click event reached the tree: " + component);
        assertNull(component.hoverEvent(), "a hover event reached the tree: " + component);
        component.children().forEach(PlaceholdersTest::assertNoInteraction);
    }

    @Test
    @DisplayName("markup in a value is text")
    void markupInAValueIsText() {
        assertEquals("Hi <red>Steve", render("Hi <player>", Placeholders.none().player("<red>Steve")));
    }

    @Test
    @DisplayName("withers return a copy and leave the receiver alone")
    void withersDoNotMutate() {
        Placeholders a = Placeholders.none().player("A");
        a.player("B");

        assertEquals("A", render("<player>", a));
        assertEquals("<player>", render("<player>", Placeholders.none()));
    }

    @Test
    @DisplayName("a null value is rejected when it is set, naming the placeholder")
    void aNullValueIsRejectedWhenSet() {
        NullPointerException thrown = assertThrows(NullPointerException.class,
                () -> Placeholders.none().player(null));
        assertEquals("player", thrown.getMessage());
    }

    @Test
    @DisplayName("an unset placeholder renders literally")
    void anUnsetPlaceholderRendersLiterally() {
        assertEquals("Hi <player>!", render("Hi <player>!", Placeholders.none()));
    }

    @Test
    @DisplayName("rank is a plain integer and lifetime truncates to tenths like hours")
    void rankAndLifetime() {
        assertEquals("3|1.4", render("<rank>|<lifetime>",
                Placeholders.none().rank(3).lifetime(5399)));
        assertEquals("0.0", render("<lifetime>", Placeholders.none().lifetime(-1)));
        assertEquals("10.0", render("<lifetime>", Placeholders.none().lifetime(36000)));
        assertEquals("1.5 2.0", render("<hours> <lifetime>",
                Placeholders.none().hours(5400).lifetime(7200)));
    }
}
