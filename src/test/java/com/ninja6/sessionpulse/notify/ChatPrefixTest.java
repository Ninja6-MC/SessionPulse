package com.ninja6.sessionpulse.notify;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ninja6.sessionpulse.config.PluginConfig;
import java.util.concurrent.atomic.AtomicReference;
import net.kyori.adventure.title.Title;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The configured prefix: in front of chat, read on every call, and nowhere else.
 *
 * <p>There is no test that the prefix's style stays out of the body. For a prefix that
 * passes validation, every way of joining the two renders identically, so such a test could
 * not fail; {@code Notifier#renderChat} records why it joins them as siblings anyway.
 */
class ChatPrefixTest {

    private static String legacy(net.kyori.adventure.text.Component component) {
        return Notifier.LEGACY.serialize(component);
    }

    @Test
    @DisplayName("the shipped default prefix leads chat")
    void theShippedDefaultPrefixLeadsChat() {
        Notifier notifier = NotifyFixture.notifier("");
        assertEquals("§7[§bSessionPulse§7]§r §chi",
                legacy(notifier.renderChat("<red>hi</red>", Placeholders.none())));
    }

    @Test
    @DisplayName("an empty prefix adds nothing")
    void anEmptyPrefixAddsNothing() {
        Notifier notifier = NotifyFixture.notifier("reminders:\n  prefix: \"\"\n");
        assertEquals("§chi", legacy(notifier.renderChat("<red>hi</red>", Placeholders.none())));
    }

    @Test
    @DisplayName("the prefix is read on every call, so a reload reaches it")
    void thePrefixIsReadPerCall() {
        AtomicReference<PluginConfig> current = new AtomicReference<>(NotifyFixture.parse(""));
        Notifier notifier = new Notifier(null, current::get);

        assertTrue(legacy(notifier.renderChat("<red>hi</red>", Placeholders.none())).startsWith("§7["));
        current.set(NotifyFixture.parse("reminders:\n  prefix: \"<green>P</green> \"\n"));
        String after = legacy(notifier.renderChat("<red>hi</red>", Placeholders.none()));
        assertTrue(after.startsWith("§aP"), "the notifier kept the old prefix: " + after);
    }

    @Test
    @DisplayName("only chat carries the prefix")
    void onlyChatCarriesThePrefix() {
        Notifier notifier = NotifyFixture.notifier("");
        Placeholders none = Placeholders.none();
        Title title = notifier.renderTitle("<red>hi</red>", "<red>hi</red>", none);

        assertEquals("§chi", legacy(notifier.renderLine("<red>hi</red>", none)), "action bar");
        assertEquals("§chi", legacy(title.title()), "title");
        assertEquals("§chi", legacy(title.subtitle()), "subtitle");
        assertEquals("§chi", notifier.legacy("<red>hi</red>", none), "legacy");
    }

    @Test
    @DisplayName("placeholders work in the prefix")
    void placeholdersWorkInThePrefix() {
        Notifier notifier = NotifyFixture.notifier("reminders:\n  prefix: \"<gray><player></gray> \"\n");
        assertEquals("§7Steve§r §chi",
                legacy(notifier.renderChat("<red>hi</red>", Placeholders.none().player("Steve"))));
    }

    @Test
    @DisplayName("the overtime default renders with its placeholder behind the prefix")
    void theOvertimeDefaultRendersWithItsPlaceholder() {
        Notifier notifier = NotifyFixture.notifier("");
        assertEquals("§7[§bSessionPulse§7]§r §cYou have been playing for §f3.0§c hours.",
                legacy(notifier.renderChat(NotifyFixture.OVERTIME_DEFAULT,
                        Placeholders.none().hours(10800))));
    }
}
