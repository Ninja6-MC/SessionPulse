package com.ninja6.sessionpulse.afk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ninja6.sessionpulse.config.PluginConfig;
import com.ninja6.sessionpulse.platform.RecordingScheduler;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link AfkService#resolve()}: one row per line of the resolution table, each asserting the
 * detector it publishes and the exact line it logs.
 *
 * <p>The rows that matter most are the fallbacks. AUTO with EssentialsX present but its
 * auto-afk off must use the idle timer; ESSENTIALS with EssentialsX absent must pause nobody,
 * not quietly use the timer; and a lookup that throws a raw {@link Error} must still resolve,
 * which a resolver catching only {@link Exception} does not.
 */
class AfkResolutionTest {

    private final RecordingScheduler scheduler = new RecordingScheduler();
    private final AfkFixture.Clock clock = new AfkFixture.Clock();
    private final AfkFixture.Log log = new AfkFixture.Log();
    private PluginConfig config;
    private final BuiltInAfkDetector builtIn = new BuiltInAfkDetector(clock, () -> config);
    private EssentialsLookup lookup = () -> null;
    private final AfkService service =
            new AfkService(() -> lookup.enabledPlugin(), builtIn, scheduler, log.logger, () -> config);

    private AfkDetector resolve(String mode) {
        config = AfkFixture.config(mode, 300);
        service.resolve();
        assertNotNull(service.current(), "resolve never publishes null");
        return service.current();
    }

    private void assertOnlyLine(Level level, String message) {
        assertEquals(1, log.records.size(), () -> "expected one line, got " + messages());
        assertLine(0, level, message);
    }

    private void assertLine(int index, Level level, String message) {
        LogRecord record = log.records.get(index);
        assertEquals(level, record.getLevel(), record.getMessage());
        assertEquals(message, record.getMessage());
    }

    private List<String> messages() {
        return log.records.stream().map(LogRecord::getMessage).toList();
    }

    private void essentials(long autoAfk) {
        FakeEssentials fake = new FakeEssentials(scheduler, autoAfk);
        lookup = () -> fake;
    }

    @Test
    @DisplayName("OFF pauses nobody, whatever is installed")
    void off() {
        essentials(300);

        assertSame(NoOpAfkDetector.INSTANCE, resolve("OFF"));
        assertOnlyLine(Level.INFO, "AFK detection: off (tracking.afk.mode OFF).");
    }

    @Test
    @DisplayName("BUILT_IN uses the idle timer even where EssentialsX is installed")
    void builtIn() {
        essentials(300);

        assertSame(builtIn, resolve("BUILT_IN"));
        assertOnlyLine(Level.INFO, "AFK detection: built-in idle timer, 300s.");
    }

    @Test
    @DisplayName("ESSENTIALS binds to an installed EssentialsX")
    void essentialsBound() {
        essentials(300);

        assertInstanceOf(EssentialsAfkDetector.class, resolve("ESSENTIALS"));
        assertOnlyLine(Level.INFO, "AFK detection: EssentialsX (auto-afk 300s; only players with "
                + "essentials.afk.auto are marked automatically, others only by /afk).");
    }

    @Test
    @DisplayName("ESSENTIALS with auto-afk off still binds, and warns that only /afk pauses")
    void essentialsBoundWithAutoAfkOff() {
        essentials(0);

        assertInstanceOf(EssentialsAfkDetector.class, resolve("ESSENTIALS"));
        assertOnlyLine(Level.WARNING,
                "AFK detection: EssentialsX, but its auto-afk is disabled; only /afk will pause the clock.");
    }

    @Test
    @DisplayName("ESSENTIALS without EssentialsX pauses nobody and says so, rather than using the timer")
    void essentialsMissing() {
        assertSame(NoOpAfkDetector.INSTANCE, resolve("ESSENTIALS"));
        assertOnlyLine(Level.WARNING, "AFK detection: tracking.afk.mode is ESSENTIALS but EssentialsX "
                + "is not installed or not enabled; nobody will be treated as AFK.");
    }

    @Test
    @DisplayName("ESSENTIALS with a bind that fails pauses nobody, and logs the cause")
    void essentialsBindFails() {
        FakeEssentials fake = new FakeEssentials(scheduler, 300);
        fake.settingsError = new NoClassDefFoundError("com/earth2me/essentials/ISettings");
        lookup = () -> fake;

        assertSame(NoOpAfkDetector.INSTANCE, resolve("ESSENTIALS"));
        assertOnlyLine(Level.WARNING, "AFK detection: tracking.afk.mode is ESSENTIALS and EssentialsX "
                + "is installed but could not be bound; nobody will be treated as AFK.");
        assertNotNull(log.records.get(0).getThrown(), "the cause is logged with the line");
    }

    @Test
    @DisplayName("AUTO binds to EssentialsX when its auto-afk is on, and names the permission fallback")
    void autoBound() {
        essentials(300);

        assertInstanceOf(EssentialsAfkDetector.class, resolve("AUTO"));
        assertOnlyLine(Level.INFO, "AFK detection: EssentialsX (AUTO; auto-afk 300s; players without "
                + "essentials.afk.auto use the built-in 300s timer).");
    }

    @Test
    @DisplayName("AUTO with EssentialsX present but auto-afk 0 uses the idle timer")
    void autoWithAutoAfkZero() {
        essentials(0);

        assertSame(builtIn, resolve("AUTO"),
                "preferring EssentialsX whenever it is installed would pause almost nobody");
        assertOnlyLine(Level.INFO, "AFK detection: built-in idle timer, 300s "
                + "(AUTO; EssentialsX present but auto-afk is disabled).");
    }

    @Test
    @DisplayName("AUTO with EssentialsX auto-afk negative uses the idle timer")
    void autoWithAutoAfkNegative() {
        essentials(-1);

        assertSame(builtIn, resolve("AUTO"));
    }

    @Test
    @DisplayName("AUTO without EssentialsX uses the idle timer")
    void autoMissing() {
        assertSame(builtIn, resolve("AUTO"));
        assertOnlyLine(Level.INFO,
                "AFK detection: built-in idle timer, 300s (AUTO; EssentialsX not installed).");
    }

    @Test
    @DisplayName("AUTO with a bind that throws NoClassDefFoundError uses the idle timer and logs why")
    void autoBindFails() {
        FakeEssentials fake = new FakeEssentials(scheduler, 300);
        fake.settingsError = new NoClassDefFoundError("com/earth2me/essentials/ISettings");
        lookup = () -> fake;

        assertSame(builtIn, resolve("AUTO"));
        assertEquals(2, log.records.size(), () -> messages().toString());
        assertLine(0, Level.WARNING, "AFK detection: EssentialsX is installed but could not be bound; "
                + "falling back to the built-in idle timer.");
        assertNotNull(log.records.get(0).getThrown());
        assertLine(1, Level.INFO,
                "AFK detection: built-in idle timer, 300s (AUTO; EssentialsX could not be bound).");
    }

    @Test
    @DisplayName("AUTO with an EssentialsX that has no getUser(Player) uses the idle timer")
    void autoWithoutGetUser() {
        FakeEssentials.WithoutGetUser fake = new FakeEssentials.WithoutGetUser();
        lookup = () -> fake;

        assertSame(builtIn, resolve("AUTO"));
        assertEquals(Level.WARNING, log.records.get(0).getLevel());
    }

    @Test
    @DisplayName("AUTO with a lookup that throws a raw Error still resolves, to the idle timer")
    void autoLookupThrowsError() {
        lookup = () -> {
            throw new NoClassDefFoundError("org/bukkit/plugin/Plugin");
        };

        assertSame(builtIn, resolve("AUTO"));
        assertEquals(Level.WARNING, log.records.get(0).getLevel());
    }

    @Test
    @DisplayName("ESSENTIALS with a lookup that throws a raw Error still resolves, to nobody")
    void essentialsLookupThrowsError() {
        lookup = () -> {
            throw new NoClassDefFoundError("org/bukkit/plugin/Plugin");
        };

        assertSame(NoOpAfkDetector.INSTANCE, resolve("ESSENTIALS"));
        assertEquals(Level.WARNING, log.records.get(0).getLevel());
    }

    @Test
    @DisplayName("resolving without EssentialsX ignores a plugin that still reports itself enabled")
    void withoutEssentialsIgnoresTheLookup() {
        essentials(300);
        config = AfkFixture.config("AUTO", 300);

        service.resolveWithoutEssentials();

        assertSame(builtIn, service.current(),
                "Bukkit fires the disable event before the plugin stops reporting itself enabled");
    }

    @Test
    @DisplayName("a resolve after a reload picks up the new mode")
    void aResolveAfterAReloadPicksUpTheNewMode() {
        essentials(300);
        assertInstanceOf(EssentialsAfkDetector.class, resolve("AUTO"));

        assertSame(NoOpAfkDetector.INSTANCE, resolve("OFF"));
    }

    @Test
    @DisplayName("nobody is AFK before the first resolve, and after retire")
    void noOpBeforeTheFirstResolveAndAfterRetire() {
        assertSame(NoOpAfkDetector.INSTANCE, service.current());

        resolve("BUILT_IN");
        service.retire();

        assertSame(NoOpAfkDetector.INSTANCE, service.current());
        assertTrue(scheduler.scheduled.isEmpty(),
                "resolving schedules no repeating task; the session tick is the only one");
    }
}
