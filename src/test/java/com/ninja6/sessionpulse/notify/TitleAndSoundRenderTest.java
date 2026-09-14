package com.ninja6.sessionpulse.notify;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Titles with a half missing, and Bukkit sounds mapped to Adventure ones. */
class TitleAndSoundRenderTest {

    private final Notifier notifier = NotifyFixture.notifier("");

    @Test
    @DisplayName("a title with no subtitle")
    void aTitleWithNoSubtitle() {
        Title title = notifier.renderTitle("<yellow>Eye break</yellow>", null, Placeholders.none());

        assertEquals("§eEye break", Notifier.LEGACY.serialize(title.title()));
        assertEquals(Component.empty(), title.subtitle());
        assertEquals(Title.DEFAULT_TIMES, title.times());
    }

    @Test
    @DisplayName("a subtitle with no title is shown under an empty title")
    void aSubtitleWithNoTitleIsShownUnderAnEmptyTitle() {
        Title title = notifier.renderTitle(null, "<gray>20 seconds, 20 feet</gray>", Placeholders.none());

        assertNotNull(title, "a subtitle-only title was dropped");
        assertEquals(Component.empty(), title.title());
        assertEquals("§720 seconds, 20 feet", Notifier.LEGACY.serialize(title.subtitle()));
    }

    @Test
    @DisplayName("no title at all renders nothing")
    void noTitleAtAll() {
        assertNull(notifier.renderTitle(null, null, Placeholders.none()));
    }

    @Test
    @DisplayName("a Bukkit sound maps by its key, not its name")
    void aBukkitSoundMapsByItsKeyNotItsName() {
        Sound sound = Notifier.renderSound(org.bukkit.Sound.BLOCK_NOTE_BLOCK_CHIME);

        assertEquals("minecraft:block.note_block.chime", sound.name().asString());
        assertEquals(Sound.Source.MASTER, sound.source());
        assertEquals(1f, sound.volume());
        assertEquals(1f, sound.pitch());
    }
}
