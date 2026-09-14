package com.ninja6.sessionpulse.notify;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The String-only render for the disconnect screen and the pre-login refusal. */
class LegacyFallbackTest {

    private final Notifier notifier = NotifyFixture.notifier("");

    @Test
    @DisplayName("the default kick message is readable and unprefixed")
    void theDefaultKickMessageIsReadable() {
        assertEquals("§eTime for a break.", notifier.legacy(NotifyFixture.KICK_DEFAULT, Placeholders.none()));
    }

    @Test
    @DisplayName("hex colours survive rather than being downsampled")
    void hexColoursSurvive() {
        assertEquals("§x§f§f§8§8§0§0hi", notifier.legacy("<color:#ff8800>hi", Placeholders.none()));
    }

    @Test
    @DisplayName("a gradient degrades to readable text")
    void aGradientDegradesToReadableText() {
        String rendered = notifier.legacy("<gradient:red:blue>Break</gradient>", Placeholders.none());
        assertEquals("Break", rendered.replaceAll("§.", ""));
        assertFalse(rendered.contains("<"), rendered);
    }

    @Test
    @DisplayName("hover and click are dropped and their text kept")
    void hoverAndClickAreDroppedAndTheTextKept() {
        assertEquals("Go", notifier.legacy(
                "<hover:show_text:'tip'><click:open_url:'https://x'>Go</click></hover>",
                Placeholders.none()));
    }

    @Test
    @DisplayName("a cooldown placeholder renders into the kick text")
    void aCooldownPlaceholderRendersIntoTheKickText() {
        assertEquals("§eBack in 30 minutes.", notifier.legacy(
                "<yellow>Back in <cooldown> minutes.</yellow>", Placeholders.none().cooldown(1800)));
    }

    @Test
    @DisplayName("null renders empty")
    void nullRendersEmpty() {
        assertEquals("", notifier.legacy(null, Placeholders.none()));
    }
}
