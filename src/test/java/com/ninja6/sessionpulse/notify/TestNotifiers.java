package com.ninja6.sessionpulse.notify;

import com.ninja6.sessionpulse.config.PluginConfig;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Supplier;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;

/**
 * A {@link Notifier} that records what it delivers, for tests outside this package.
 *
 * <p>The recording constructor is package-private so no main code can reach it. This factory
 * is its one public door, and it lives in the test tree. {@code public} for the same reason
 * {@code RecordingScheduler} is.
 */
public final class TestNotifiers {

    private TestNotifiers() {
    }

    /** Records each call as {@code kind:legacy text}, in order. Delivered from any thread. */
    public static final class RecordingAudience implements Audience {

        private final List<String> calls = Collections.synchronizedList(new ArrayList<>());

        /** Every call so far, as a copy. */
        public List<String> calls() {
            synchronized (calls) {
                return List.copyOf(calls);
            }
        }

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
            calls.add("title:" + Notifier.LEGACY.serialize(title.title())
                    + "/" + Notifier.LEGACY.serialize(title.subtitle()));
        }

        @Override
        public void playSound(Sound sound, Sound.Emitter emitter) {
            calls.add("sound:" + sound.name().asString());
        }
    }

    /**
     * A notifier that delivers every player's output to {@code audience}.
     *
     * @param config   the configuration in force, read per render
     * @param audience where everything lands
     * @return the notifier; open and close do nothing on it
     */
    public static Notifier recording(Supplier<PluginConfig> config, RecordingAudience audience) {
        return new Notifier(config, player -> audience);
    }
}
