package com.ninja6.sessionpulse.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ninja6.sessionpulse.session.SessionSnapshot;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@code /spulse top}: the longest lifetimes, stored and live, in a stable order. */
class TopCommandTest {

    private static final long WALL = 1_700_000_000_000L;

    @TempDir
    Path dir;

    private static void store(CommandFixture fx, String name, long lifetimeSeconds) {
        fx.storage.save(UUID.randomUUID(), new SessionSnapshot(name, lifetimeSeconds, WALL, 0L, WALL));
    }

    @Test
    @DisplayName("rows are ordered by lifetime, then name ignoring case")
    void orderAndTies() {
        CommandFixture fx = new CommandFixture(dir);
        store(fx, "bob", 3600);
        store(fx, "Alice", 3600);
        store(fx, "Carol", 7200);

        assertEquals(List.of("chat:Top playtime", "chat:1. Carol 2.0h", "chat:2. Alice 1.0h",
                "chat:3. bob 1.0h"), fx.run(CommandFixture.console(), "top"));
    }

    @Test
    @DisplayName("only the top ten are shown")
    void limitedToTen() {
        CommandFixture fx = new CommandFixture(dir);
        for (int i = 1; i <= 12; i++) {
            store(fx, "P" + (char) ('a' + i), i * 360L);
        }

        List<String> lines = fx.run(CommandFixture.console(), "top");
        assertEquals(1 + SessionPulseCommand.TOP_SIZE, lines.size());
        assertEquals("chat:1. Pm 1.2h", lines.get(1));
        assertEquals("chat:10. Pd 0.3h", lines.get(10));
    }

    @Test
    @DisplayName("zero lifetimes and nameless records are left off")
    void zeroAndNamelessSkipped() {
        CommandFixture fx = new CommandFixture(dir);
        store(fx, "Zero", 0);
        store(fx, null, 9000);
        fx.storage.setCooldown(UUID.randomUUID(), "CooldownOnly", WALL + 1);
        store(fx, "Real", 360);

        assertEquals(List.of("chat:Top playtime", "chat:1. Real 0.1h"),
                fx.run(CommandFixture.console(), "top"));
    }

    @Test
    @DisplayName("an online player with no stored record is ranked, and live lifetime wins")
    void onlinePlayersAreIncluded() {
        CommandFixture fx = new CommandFixture(dir);
        store(fx, "Offline", 1800);
        Player fresh = fx.join(CommandFixture.player("Fresh", Set.of()));
        fx.tick(Duration.ofMinutes(60));

        assertTrue(fx.storage.records().get(fresh.getUniqueId()) == null,
                "precondition: the online player has not been flushed");
        assertEquals(List.of("chat:Top playtime", "chat:1. Fresh 1.0h", "chat:2. Offline 0.5h"),
                fx.run(CommandFixture.console(), "top"));
    }

    @Test
    @DisplayName("a name that looks like markup is shown as text")
    void markupNameIsText() {
        CommandFixture fx = new CommandFixture(dir);
        store(fx, "<red>x", 360);

        List<String> calls = fx.audience.calls();
        fx.run(CommandFixture.console(), "top");
        String row = fx.audience.calls().get(calls.size() + 1);
        assertTrue(row.contains("<red>x"), row);
    }

    @Test
    @DisplayName("an empty leaderboard says so; extra arguments print usage; a player gets output")
    void emptyUsageAndPlayer() {
        CommandFixture fx = new CommandFixture(dir);

        assertEquals(List.of("chat:No playtime recorded yet."),
                fx.run(CommandFixture.console(), "top"));
        assertEquals(List.of("chat:Usage: /spulse top"),
                fx.run(CommandFixture.console(), "top", "5"));
        store(fx, "Ada", 360);
        Player ada = CommandFixture.player("Ada", Set.of(SessionPulseCommand.USE));
        assertEquals(List.of("chat:Top playtime", "chat:1. Ada 0.1h"), fx.run(ada, "top"));
    }
}
