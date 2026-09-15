package com.ninja6.sessionpulse.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.logging.Level;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code /spulse reload} runs the plugin's reload once and says whether it worked.
 *
 * <p>The flush's own reschedule, a new period and the old handle cancelled, is already proven
 * by {@code FlushSchedulingTest}; the tick's by {@code SessionTickScheduleTest}.
 */
class ReloadCommandTest {

    @TempDir
    Path dir;

    @Test
    @DisplayName("a reload that works runs once and says so")
    void aSuccessfulReloadRunsOnceAndSaysSo() {
        CommandFixture fx = new CommandFixture(dir);

        assertEquals(List.of("chat:Configuration reloaded. Any warnings are in the console."),
                fx.run(CommandFixture.console(), "reload"));
        assertEquals(1, fx.reloads);
    }

    @Test
    @DisplayName("a reload that throws is reported, logged in full, and does not escape")
    void aFailedReloadIsReportedAndLogged() {
        CommandFixture fx = new CommandFixture(dir);
        IllegalStateException failure = new IllegalStateException("config.yml could not be parsed");
        fx.reloadFailure = failure;

        assertEquals(List.of("chat:Reload failed. The error is in the console."),
                fx.run(CommandFixture.console(), "reload"));
        assertEquals(1, fx.reloads);
        assertTrue(fx.logged.stream().anyMatch(record -> record.getLevel() == Level.SEVERE
                && record.getThrown() == failure), "the cause must reach the log");
        assertSame(failure, fx.logged.get(fx.logged.size() - 1).getThrown());
    }

    @Test
    @DisplayName("reload with extra arguments prints its usage and reloads nothing")
    void extraArgumentsPrintUsage() {
        CommandFixture fx = new CommandFixture(dir);

        assertEquals(List.of("chat:Usage: /spulse reload"),
                fx.run(CommandFixture.console(), "reload", "now"));
        assertEquals(0, fx.reloads);
    }
}
