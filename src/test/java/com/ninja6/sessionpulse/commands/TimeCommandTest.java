package com.ninja6.sessionpulse.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@code /spulse time}: live figures for the online, next-join figures for the offline. */
class TimeCommandTest {

    private static final Set<String> ADMIN =
            Set.of(SessionPulseCommand.USE, SessionPulseCommand.ADMIN);

    @TempDir
    Path dir;

    @Test
    @DisplayName("a player sees their own live window and lifetime")
    void ownLiveTime() {
        CommandFixture fx = new CommandFixture(dir);
        Player ada = fx.join(CommandFixture.player("Ada", Set.of(SessionPulseCommand.USE)));
        fx.tick(Duration.ofMinutes(90));

        assertEquals(List.of("chat:Your counted window: 1.5h (90 min). Lifetime: 1.5h."),
                fx.run(ada, "time"));
    }

    @Test
    @DisplayName("an offline player's time comes from their record")
    void offlineOtherFromTheRecord() {
        CommandFixture fx = new CommandFixture(dir);
        Player grace = fx.join(CommandFixture.player("Grace", Set.of()));
        fx.tick(Duration.ofMinutes(30));
        fx.quit(grace);
        fx.clock.advanceWallOnly(Duration.ofHours(1));
        Player admin = fx.join(CommandFixture.player("Admin", ADMIN));

        assertEquals(List.of("chat:Grace's counted window: 0.5h (30 min). Lifetime: 0.5h."),
                fx.run(admin, "time", "grace"));
    }

    @Test
    @DisplayName("an offline window the next join would reset is shown as zero, lifetime kept")
    void expiredOfflineWindowIsZero() {
        CommandFixture fx = new CommandFixture(dir);
        Player grace = fx.join(CommandFixture.player("Grace", Set.of()));
        fx.tick(Duration.ofMinutes(30));
        fx.quit(grace);
        fx.clock.advanceWallOnly(Duration.ofHours(9));

        assertEquals(List.of("chat:Grace's counted window: 0.0h (0 min). Lifetime: 0.5h."),
                fx.run(CommandFixture.console(), "time", "Grace"));
    }

    @Test
    @DisplayName("the console must name a player, and can")
    void consoleNeedsAName() {
        CommandFixture fx = new CommandFixture(dir);
        fx.join(CommandFixture.player("Ada", Set.of()));
        fx.tick(Duration.ofMinutes(6));

        assertEquals(List.of("chat:From the console, name a player: /spulse time <player>"),
                fx.run(CommandFixture.console(), "time"));
        assertEquals(List.of("chat:Ada's counted window: 0.1h (6 min). Lifetime: 0.1h."),
                fx.run(CommandFixture.console(), "time", "ADA"));
    }

    @Test
    @DisplayName("a name with no playtime, or only a cooldown, has no record")
    void noRecord() {
        CommandFixture fx = new CommandFixture(dir);
        fx.storage.setCooldown(java.util.UUID.randomUUID(), "Kicked", fx.clock.wallMillis() + 1L);

        assertEquals(List.of("chat:No playtime recorded for Nobody."),
                fx.run(CommandFixture.console(), "time", "Nobody"));
        assertEquals(List.of("chat:No playtime recorded for Kicked."),
                fx.run(CommandFixture.console(), "time", "Kicked"));
    }
}
