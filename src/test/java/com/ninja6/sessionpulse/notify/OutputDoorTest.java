package com.ninja6.sessionpulse.notify;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ninja6.sessionpulse.SessionPulsePlugin;
import com.ninja6.sessionpulse.platform.SourceTree;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Only {@link Notifier} builds a {@code Component} that reaches an audience.
 *
 * <p>Not "only {@link Notifier} names Adventure": {@code config/MessageCheck} has to name
 * MiniMessage to validate a configured string, and throws the result away. So this is a
 * two-entry allowlist, and the second entry is narrower than the first - that file may
 * name {@code net.kyori} and {@code MiniMessage} and nothing else from Adventure, which is
 * what keeps it a validator rather than a second rendering door.
 *
 * <p>Two doors stay open and nothing here closes them, because neither names Adventure:
 * {@code player.spigot().sendMessage(ChatMessageType, BaseComponent...)}, and a raw
 * MiniMessage string passed to {@code player.sendMessage(String)} or {@code sendTitle}.
 *
 * <p>The reflective test works because tests run against the unrelocated jars, so an
 * Adventure type's name here is literally {@code net.kyori...}.
 */
class OutputDoorTest {

    private static final String NOTIFIER = "Notifier.java";
    private static final String VALIDATOR = "MessageCheck.java";

    private static final Pattern ADVENTURE_TYPES = Pattern.compile(
            "\\b(Component|MiniMessage|BukkitAudiences|AudienceProvider|Audience"
                    + "|LegacyComponentSerializer|TagResolver|Placeholder|Title|Key)\\b");

    private static String fileName(Path source) {
        return source.getFileName().toString();
    }

    @Test
    @DisplayName("only the notifier and the validator name Adventure, even in a comment")
    void onlyTheNotifierAndTheValidatorNameAdventure() {
        List<String> offenders = new ArrayList<>();
        boolean notifierNamesIt = false;
        for (Path source : SourceTree.mainSources()) {
            String[] lines = SourceTree.read(source).split("\n", -1);
            for (int i = 0; i < lines.length; i++) {
                if (!lines[i].contains("net.kyori")) {
                    continue;
                }
                if (fileName(source).equals(NOTIFIER)) {
                    notifierNamesIt = true;
                } else if (!fileName(source).equals(VALIDATOR)) {
                    offenders.add("  - " + source + ":" + (i + 1));
                }
            }
        }
        assertTrue(offenders.isEmpty(), "net.kyori named outside Notifier and MessageCheck. Player "
                + "output goes through notify/Notifier:" + System.lineSeparator()
                + String.join(System.lineSeparator(), offenders));
        assertTrue(notifierNamesIt, "Notifier does not name net.kyori, so this test proves nothing");
    }

    @Test
    @DisplayName("Adventure types stay in the notifier, and the validator names only MiniMessage")
    void adventureTypesStayInTheirFiles() {
        List<String> offenders = new ArrayList<>();
        for (Path source : SourceTree.mainSources()) {
            if (fileName(source).equals(NOTIFIER)) {
                continue;
            }
            Set<String> found = new TreeSet<>();
            Matcher matcher = ADVENTURE_TYPES.matcher(SourceTree.stripCommentsAndStrings(SourceTree.read(source)));
            while (matcher.find()) {
                found.add(matcher.group(1));
            }
            if (fileName(source).equals(VALIDATOR)) {
                found.remove("MiniMessage");
            }
            if (!found.isEmpty()) {
                offenders.add("  - " + source + " uses " + found);
            }
        }
        assertTrue(offenders.isEmpty(), "an Adventure type used outside Notifier:"
                + System.lineSeparator() + String.join(System.lineSeparator(), offenders));
    }

    @Test
    @DisplayName("no public signature on the notifier, its placeholders or the plugin names Adventure")
    void theNotifierSurfaceNamesNoAdventureType() {
        List<String> offenders = new ArrayList<>();
        for (Class<?> type : List.of(Notifier.class, Placeholders.class, SessionPulsePlugin.class)) {
            for (Constructor<?> constructor : type.getDeclaredConstructors()) {
                if (Modifier.isPublic(constructor.getModifiers())) {
                    check(constructor.toGenericString(), offenders);
                }
            }
            for (Method method : type.getDeclaredMethods()) {
                if (Modifier.isPublic(method.getModifiers())) {
                    check(method.toGenericString(), offenders);
                }
            }
            for (Field field : type.getDeclaredFields()) {
                if (Modifier.isPublic(field.getModifiers())) {
                    check(field.toGenericString(), offenders);
                }
            }
        }
        assertTrue(offenders.isEmpty(), "a public signature names Adventure, which hands callers a "
                + "door around Notifier:" + System.lineSeparator()
                + String.join(System.lineSeparator(), offenders));
    }

    private static void check(String signature, List<String> offenders) {
        if (signature.contains("kyori")) {
            offenders.add("  - " + signature);
        }
    }
}
