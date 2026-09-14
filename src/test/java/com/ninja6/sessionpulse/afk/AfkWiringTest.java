package com.ninja6.sessionpulse.afk;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ninja6.sessionpulse.platform.SourceTree;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The detectors are actually wired in, and EssentialsX is never linked against.
 *
 * <p>Every other AFK test passes against a plugin still running the stub gate, or one that
 * never re-resolves on reload. And a single EssentialsX import compiles clean here, where
 * nothing is on the classpath to miss, then fails class loading on every server without it.
 * Both are properties of the source, so the source is read.
 */
class AfkWiringTest {

    private static final Path PLUGIN =
            SourceTree.MAIN_JAVA.resolve("com/ninja6/sessionpulse/SessionPulsePlugin.java");

    private static String pluginCode() {
        return SourceTree.stripCommentsAndStrings(SourceTree.read(PLUGIN));
    }

    @Test
    @DisplayName("no main source names an EssentialsX package")
    void essentialsIsNeverNamed() {
        List<String> offenders = new ArrayList<>();
        for (Path source : SourceTree.mainSources()) {
            String code = SourceTree.stripCommentsAndStrings(SourceTree.read(source));
            for (String marker : List.of("com.earth2me", "net.ess3")) {
                if (code.contains(marker)) {
                    offenders.add("  - " + source + " names " + marker);
                }
            }
        }

        assertTrue(offenders.isEmpty(), "EssentialsX is a soft dependency and is reached by "
                + "reflection only. Named in:" + System.lineSeparator()
                + String.join(System.lineSeparator(), offenders));
    }

    @Test
    @DisplayName("the plugin no longer runs the tick on AfkGate.NEVER")
    void thePluginDoesNotUseTheStubGate() {
        String code = pluginCode();

        assertFalse(code.contains("AfkGate.NEVER"), "the stub gate would pause nobody in every mode");
        assertTrue(code.contains("new SessionTickTask(tracker, afk,"),
                "the tick is handed the service, so a resolve reaches it without a reschedule");
    }

    @Test
    @DisplayName("the plugin registers the activity listener, resolves at enable and on reload")
    void thePluginRegistersAndResolves() {
        String code = pluginCode();

        assertTrue(code.contains("new PlayerActivityListener(afk)"));
        int reload = code.indexOf("public void reload()");
        assertTrue(reload >= 0);
        assertTrue(code.indexOf("afk.resolve()") < reload, "resolved at enable");
        assertTrue(code.indexOf("afk.resolve()", reload) > reload, "resolved on reload");
    }

    @Test
    @DisplayName("plugin.yml soft-depends on Essentials, so it loads first when installed")
    void pluginYmlSoftDependsOnEssentials() throws IOException {
        try (InputStream in = AfkWiringTest.class.getResourceAsStream("/plugin.yml")) {
            String yml = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(yml.contains("\nsoftdepend: [" + EssentialsLookup.PLUGIN_NAME + "]\n"), yml);
        }
    }
}
