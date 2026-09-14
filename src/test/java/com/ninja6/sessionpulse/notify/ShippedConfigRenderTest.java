package com.ninja6.sessionpulse.notify;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ninja6.sessionpulse.config.Milestone;
import com.ninja6.sessionpulse.config.PluginConfig;
import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Every text the shipped {@code config.yml} carries renders to its words, through the
 * channel it is configured for.
 *
 * <p>Validation cannot see this. A misspelt tag such as {@code <yelow>} is an unknown tag,
 * which MiniMessage accepts and then shows on screen as the characters typed, so
 * {@code DefaultConfigResourceTest} passes while the player reads markup. The payloads are
 * read through {@link PluginConfig}'s accessors, the way the plugin reads them.
 */
class ShippedConfigRenderTest {

    private static final String HOURS = "3.0";
    private static final String COOLDOWN = "30";

    private record Rendered(String channel, String source, String legacy) {
    }

    @Test
    @DisplayName("every shipped payload renders to its text, with no markup left over")
    void everyShippedPayloadRendersToItsText() {
        PluginConfig config = new PluginConfig(
                YamlConfiguration.loadConfiguration(NotifyFixture.SHIPPED.toFile()));
        Notifier notifier = new Notifier(null, () -> config);
        Placeholders values = Placeholders.none().hours(10800).cooldown(1800);
        String prefix = config.reminderPrefix();

        List<Rendered> rendered = new ArrayList<>();
        for (Milestone milestone : config.milestones()) {
            if (milestone.message() != null) {
                rendered.add(new Rendered("chat", prefix + milestone.message(),
                        legacy(notifier.renderChat(milestone.message(), values))));
            }
            if (milestone.actionBar() != null) {
                rendered.add(new Rendered("action bar", milestone.actionBar(),
                        legacy(notifier.renderLine(milestone.actionBar(), values))));
            }
            Title title = notifier.renderTitle(milestone.title(), milestone.subtitle(), values);
            if (milestone.title() != null) {
                rendered.add(new Rendered("title", milestone.title(), legacy(title.title())));
            }
            if (milestone.subtitle() != null) {
                rendered.add(new Rendered("subtitle", milestone.subtitle(), legacy(title.subtitle())));
            }
        }
        String overtime = config.overtime().message();
        rendered.add(new Rendered("overtime", prefix + overtime, legacy(notifier.renderChat(overtime, values))));
        String kick = config.enforcement().kickMessage();
        rendered.add(new Rendered("kick", kick, notifier.legacy(kick, values)));

        // Without these the comparison below could pass on a file with nothing in it.
        assertTrue(config.milestones().size() > 0, "the shipped file has no milestones");
        assertTrue(rendered.size() > 2, "no milestone payloads were read: " + rendered);

        for (Rendered r : rendered) {
            assertEquals(count(r.source(), '<'), count(r.source(), '>'),
                    r.channel() + " source is not balanced, so the strip below proves nothing: " + r.source());
            String expected = r.source()
                    .replace("<hours>", HOURS)
                    .replace("<cooldown>", COOLDOWN)
                    .replaceAll("<[^>]+>", "");
            assertEquals(expected, r.legacy().replaceAll("§.", ""), r.channel() + ": " + r.source());
            assertFalse(r.legacy().contains("<"), r.channel() + " still carries markup: " + r.legacy());
        }
    }

    private static String legacy(Component component) {
        return Notifier.LEGACY.serialize(component);
    }

    private static long count(String text, char c) {
        return text.chars().filter(ch -> ch == c).count();
    }
}
