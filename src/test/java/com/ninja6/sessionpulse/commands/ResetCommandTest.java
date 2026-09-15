package com.ninja6.sessionpulse.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ninja6.sessionpulse.session.PlayerSession;
import com.ninja6.sessionpulse.storage.StoredPlayer;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@code /spulse reset}: the counted window and the cooldown go, lifetime and last-seen stay. */
class ResetCommandTest {

    @TempDir
    Path dir;

    @Test
    @DisplayName("an online reset zeroes the live window, keeps lifetime, and clears the cooldown")
    void onlineReset() {
        CommandFixture fx = new CommandFixture(dir);
        Player ada = fx.join(CommandFixture.player("Ada", Set.of()));
        fx.tick(Duration.ofMinutes(90));
        PlayerSession before = fx.tracker.session(ada.getUniqueId());
        assertEquals(List.of(60), fx.tracker.claimDue(before).stream().map(m -> m.minute()).toList());
        fx.storage.setCooldown(ada.getUniqueId(), "Ada", fx.clock.wallMillis() + 60_000L);

        assertEquals(List.of("chat:Reset Ada's counted window and cooldown."),
                fx.run(CommandFixture.console(), "reset", "ada"));

        PlayerSession after = fx.tracker.session(ada.getUniqueId());
        assertNotSame(before, after, "a fresh session is what releases every claim");
        assertEquals(0L, after.windowSeconds());
        assertEquals(5400L, after.lifetimeSeconds());
        StoredPlayer stored = fx.storage.records().get(ada.getUniqueId());
        assertEquals(0L, stored.windowSeconds(), "the reset must reach the store, not only memory");
        assertEquals(5400L, stored.lifetimeSeconds());
        assertEquals(0L, stored.cooldownExpiresMillis(), "the login gate would still refuse them");

        fx.tick(Duration.ofSeconds(30));
        assertEquals(List.of(), fx.tracker.claimDue(fx.tracker.session(ada.getUniqueId())),
                "a milestone the new window has not reached fired again");
        fx.tick(Duration.ofMinutes(60));
        assertEquals(1, fx.tracker.claimDue(fx.tracker.session(ada.getUniqueId())).size(),
                "the milestone re-arms and fires once as the new window crosses it");
    }

    @Test
    @DisplayName("an offline reset zeroes the stored window from now and keeps lifetime and last-seen")
    void offlineReset() {
        CommandFixture fx = new CommandFixture(dir);
        Player ada = fx.join(CommandFixture.player("Ada", Set.of()));
        fx.tick(Duration.ofMinutes(90));
        fx.quit(ada);
        StoredPlayer quit = fx.storage.records().get(ada.getUniqueId());
        fx.storage.setCooldown(ada.getUniqueId(), "Ada", fx.clock.wallMillis() + 3_600_000L);
        fx.clock.advanceWallOnly(Duration.ofMinutes(10));

        assertEquals(List.of("chat:Reset Ada's counted window and cooldown."),
                fx.run(CommandFixture.console(), "reset", "ADA"));

        StoredPlayer stored = fx.storage.records().get(ada.getUniqueId());
        assertEquals(0L, stored.windowSeconds());
        assertEquals(fx.clock.wallMillis(), stored.windowStartMillis());
        assertEquals(quit.lifetimeSeconds(), stored.lifetimeSeconds());
        assertEquals(quit.lastSeenMillis(), stored.lastSeenMillis(),
                "last-seen is when they left, not when staff reset them");
        assertEquals("Ada", stored.name());
        assertEquals(0L, stored.cooldownExpiresMillis());

        fx.clock.advanceWallOnly(Duration.ofMinutes(5));
        fx.join(ada);
        PlayerSession rejoined = fx.tracker.session(ada.getUniqueId());
        assertEquals(0L, rejoined.windowSeconds());
        assertEquals(5400L, rejoined.lifetimeSeconds());
    }

    @Test
    @DisplayName("a reset with no cooldown to clear says only the window, and still asks for a flush")
    void resetWithoutACooldown() {
        CommandFixture fx = new CommandFixture(dir);
        Player ada = fx.join(CommandFixture.player("Ada", Set.of()));
        fx.tick(Duration.ofMinutes(5));
        fx.scheduler.once.clear();

        assertEquals(List.of("chat:Reset Ada's counted window."),
                fx.run(CommandFixture.console(), "reset", "Ada"));
        assertFalse(fx.scheduler.once.isEmpty(), "a reset left for the periodic flush");
        assertEquals(0L, fx.storage.records().get(ada.getUniqueId()).windowSeconds());
    }

    @Test
    @DisplayName("an unknown name is an error and creates no record")
    void unknownName() {
        CommandFixture fx = new CommandFixture(dir);

        assertEquals(List.of("chat:No playtime recorded for Nobody."),
                fx.run(CommandFixture.console(), "reset", "Nobody"));
        assertTrue(fx.storage.records().isEmpty());
        assertNull(fx.storage.findByName("Nobody"));
        assertEquals(List.of("chat:Usage: /spulse reset <player>"),
                fx.run(CommandFixture.console(), "reset"));
    }
}
