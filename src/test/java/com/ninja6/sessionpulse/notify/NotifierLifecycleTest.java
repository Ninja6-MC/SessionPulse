package com.ninja6.sessionpulse.notify;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.ninja6.sessionpulse.config.Milestone;
import java.lang.reflect.Proxy;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A notifier before it is opened and after it is closed.
 *
 * <p>The player here is a {@link Proxy} over {@link Player} that fails on any call at all.
 * It can be built because spigot-api is on the test classpath, and it is used only in this
 * class, where "touched nothing" is the property under test.
 */
class NotifierLifecycleTest {

    private static final Player UNTOUCHABLE = (Player) Proxy.newProxyInstance(
            Player.class.getClassLoader(), new Class<?>[] {Player.class},
            (proxy, method, args) -> {
                throw new AssertionError("a closed notifier touched the player: " + method.getName());
            });

    private static final Milestone EVERYTHING = new Milestone(60, "<aqua>chat</aqua>",
            "<aqua>bar</aqua>", "<aqua>title</aqua>", "<aqua>sub</aqua>", Sound.BLOCK_NOTE_BLOCK_CHIME);

    @Test
    @DisplayName("constructing a notifier touches no server")
    void constructingTouchesNoServer() {
        assertDoesNotThrow(() -> new Notifier(null, () -> NotifyFixture.parse("")));
    }

    @Test
    @DisplayName("rendering needs no audience provider")
    void renderingNeedsNoAudience() {
        Notifier notifier = NotifyFixture.notifier("");
        Placeholders none = Placeholders.none();

        assertEquals("§7[§bSessionPulse§7]§r §chi",
                Notifier.LEGACY.serialize(notifier.renderChat("<red>hi</red>", none)));
        assertEquals("§chi", Notifier.LEGACY.serialize(notifier.renderLine("<red>hi</red>", none)));
        assertEquals("§chi",
                Notifier.LEGACY.serialize(notifier.renderTitle("<red>hi</red>", null, none).title()));
        assertEquals("§chi", notifier.legacy("<red>hi</red>", none));
    }

    @Test
    @DisplayName("a closed notifier delivers nothing and touches no player")
    void aClosedNotifierDeliversNothingAndTouchesNoPlayer() {
        Notifier notifier = NotifyFixture.notifier("");
        assertDoesNotThrow(() -> deliverEverything(notifier), "never opened");

        notifier.close();
        notifier.close();
        assertDoesNotThrow(() -> deliverEverything(notifier), "closed twice");
    }

    private static void deliverEverything(Notifier notifier) {
        Placeholders none = Placeholders.none();
        notifier.chat(UNTOUCHABLE, "<red>hi</red>", none);
        notifier.actionBar(UNTOUCHABLE, "<red>hi</red>", none);
        notifier.title(UNTOUCHABLE, "<red>hi</red>", "<red>hi</red>", none);
        notifier.sound(UNTOUCHABLE, Sound.BLOCK_NOTE_BLOCK_CHIME);
        notifier.milestone(UNTOUCHABLE, EVERYTHING, none);
        notifier.console("<red>hi</red>", "<red>hi</red>", "<red>hi</red>", "<red>hi</red>");
    }
}
