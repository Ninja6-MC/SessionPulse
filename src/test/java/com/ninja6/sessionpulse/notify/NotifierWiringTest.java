package com.ninja6.sessionpulse.notify;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ninja6.sessionpulse.platform.SourceTree;
import java.nio.file.Path;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * That the plugin builds, opens, probes and closes its notifier.
 *
 * <p>A notifier that is never opened drops every delivery without a word, by design, so the
 * behavioural tests in this package pass just as well against a plugin that forgot to call
 * {@link Notifier#open()}. That is checked here by reading the source, the way storage's
 * wiring is.
 */
class NotifierWiringTest {

    private static final Path PLUGIN = SourceTree.MAIN_JAVA
            .resolve(Path.of("com", "ninja6", "sessionpulse", "SessionPulsePlugin.java"));

    private static String code() {
        return SourceTree.stripCommentsAndStrings(SourceTree.read(PLUGIN));
    }

    private static String method(String code, String name) {
        int start = code.indexOf(" " + name + "(");
        assertTrue(start >= 0, "no " + name);
        return code.substring(start, code.indexOf("\n    }", start));
    }

    @Test
    @DisplayName("the plugin builds one notifier, over the config supplier, and opens it")
    void thePluginOwnsOneNotifierOverTheConfigSupplier() {
        String code = code();
        assertEquals(1, code.split("new Notifier\\(", -1).length - 1, "exactly one notifier");
        assertTrue(Pattern.compile("new Notifier\\(this,\\s*this::config\\)").matcher(code).find(),
                "the notifier is not built over this::config; a reload would never reach the prefix");

        String enable = method(code, "onEnable");
        int open = enable.indexOf("notifier.open()");
        assertTrue(open >= 0, "the notifier is never opened, so every delivery is silently dropped");
        assertTrue(open < enable.indexOf("notifier.console("), "the boot probe runs before open()");
    }

    @Test
    @DisplayName("onDisable closes the notifier after the tasks stop and the store closes")
    void onDisableClosesTheNotifierAfterTheTasksStop() {
        String disable = method(code(), "onDisable");
        int close = disable.indexOf("notifier.close()");
        assertTrue(close >= 0, "the notifier is never closed; its listeners outlive the plugin");
        assertTrue(close > disable.indexOf("cancelAll("),
                "the notifier closes while the tick can still send into it");
        assertTrue(close > disable.indexOf(".shutdown()"), "the notifier closes before the store");
    }

    @Test
    @DisplayName("the boot probes are still there")
    void theBootProbesAreStillThere() {
        String enable = method(code(), "onEnable");
        assertTrue(enable.contains("notifier.console("),
                "the console probe is gone; the boot legs no longer prove the pipeline links");
        assertTrue(Pattern.compile("notifier\\.console\\([^;]*,\\s*Sound\\.[A-Z_]+\\)").matcher(enable).find(),
                "the console probe sends no sound; Sound#getKey() is never linked on a boot leg");
        assertTrue(enable.contains(".legacy("),
                "the legacy probe is gone; nothing proves the relocated legacy serializer resolves");
    }
}
