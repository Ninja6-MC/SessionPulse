package com.ninja6.sessionpulse.notify;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

import com.ninja6.sessionpulse.config.Milestone;
import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Which Adventure call each delivery method makes, and in what order a milestone makes them.
 *
 * <p>The audience is a recording one handed in through the package-private constructor.
 * {@link Audience}'s delivery methods are all default methods, so it needs no server and no
 * player: the function ignores the player it is given, which is {@code null} here.
 */
class NotifierDeliveryTest {

    /** Records each call as {@code kind:legacy text}. */
    private static final class RecordingAudience implements Audience {
        final List<String> calls = new ArrayList<>();
        final List<Title> titles = new ArrayList<>();

        @Override
        public void sendMessage(Component message) {
            calls.add("chat:" + Notifier.LEGACY.serialize(message));
        }

        @Override
        public void sendActionBar(Component message) {
            calls.add("actionBar:" + Notifier.LEGACY.serialize(message));
        }

        @Override
        public void showTitle(Title title) {
            titles.add(title);
            calls.add("title:" + Notifier.LEGACY.serialize(title.title())
                    + "/" + Notifier.LEGACY.serialize(title.subtitle()));
        }

        @Override
        public void playSound(Sound sound) {
            calls.add("sound:" + sound.name().asString());
        }

        /** Never called: {@link Notifier} plays every sound with no emitter. See #64. */
        @Override
        public void playSound(Sound sound, Sound.Emitter emitter) {
            fail("a sound played with an emitter, which no 26.x facet backs");
        }
    }

    private final RecordingAudience audience = new RecordingAudience();
    private final Notifier notifier = new Notifier(() -> NotifyFixture.parse(
            "reminders:\n  prefix: \"<gray>P</gray> \"\n"), player -> audience);

    @Test
    @DisplayName("each delivery method makes exactly its own call")
    void eachDeliveryMethodMakesItsOwnCall() {
        Placeholders none = Placeholders.none();

        notifier.chat(null, "<red>c</red>", none);
        assertEquals(List.of("chat:§7P§r §cc"), audience.calls);
        audience.calls.clear();

        notifier.actionBar(null, "<red>a</red>", none);
        assertEquals(List.of("actionBar:§ca"), audience.calls);
        audience.calls.clear();

        notifier.title(null, "<red>t</red>", "<red>s</red>", none);
        assertEquals(List.of("title:§ct/§cs"), audience.calls);
    }

    /**
     * Issue #64: the emitter-taking overload reaches no facet on 26.x, so a sound sent with
     * one is silently dropped. The assertion below only proves a sound arrives; what pins the
     * overload is {@link RecordingAudience#playSound(Sound, Sound.Emitter)}, which fails the
     * run if anything in {@link Notifier} goes back to passing an emitter.
     */
    @Test
    @DisplayName("a sound is played with no emitter, because 26.x backs no other overload")
    void aSoundIsPlayedWithNoEmitter() {
        notifier.sound(null, org.bukkit.Sound.BLOCK_NOTE_BLOCK_CHIME);
        assertEquals(List.of("sound:minecraft:block.note_block.chime"), audience.calls);
    }

    @Test
    @DisplayName("a null payload sends nothing")
    void aNullPayloadSendsNothing() {
        Placeholders none = Placeholders.none();
        notifier.chat(null, null, none);
        notifier.actionBar(null, null, none);
        notifier.title(null, null, null, none);
        notifier.sound(null, null);

        assertEquals(List.of(), audience.calls);
    }

    @Test
    @DisplayName("a milestone sends chat, action bar, title and sound, in that order")
    void aMilestoneSendsEverythingInOrder() {
        notifier.milestone(null, new Milestone(60, "<red>c</red>", "<red>a</red>", "<red>t</red>",
                "<red>s</red>", org.bukkit.Sound.BLOCK_NOTE_BLOCK_CHIME), Placeholders.none());

        assertEquals(List.of("chat:§7P§r §cc", "actionBar:§ca", "title:§ct/§cs",
                "sound:minecraft:block.note_block.chime"), audience.calls);
    }

    @Test
    @DisplayName("a milestone skips the fields it does not set")
    void aMilestoneSkipsNullFields() {
        notifier.milestone(null, new Milestone(60, null, "<red>a</red>", null, null,
                org.bukkit.Sound.BLOCK_NOTE_BLOCK_CHIME), Placeholders.none());

        assertEquals(List.of("actionBar:§ca", "sound:minecraft:block.note_block.chime"), audience.calls);
    }

    @Test
    @DisplayName("a subtitle-only milestone shows its subtitle under an empty title")
    void aSubtitleOnlyMilestoneShowsAnEmptyTitle() {
        notifier.milestone(null, new Milestone(60, null, null, null, "<red>s</red>", null),
                Placeholders.none());

        assertEquals(List.of("title:/§cs"), audience.calls);
        assertEquals(Component.empty(), audience.titles.get(0).title());
    }

    @Test
    @DisplayName("placeholders reach every text field of a milestone")
    void placeholdersReachEveryField() {
        notifier.milestone(null, new Milestone(60, "<player>", "<player>", "<player>", "<player>", null),
                Placeholders.none().player("Steve"));

        assertEquals(List.of("chat:§7P§r Steve", "actionBar:Steve", "title:Steve/Steve"), audience.calls);
    }
}
