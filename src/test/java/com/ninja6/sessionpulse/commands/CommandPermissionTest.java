package com.ninja6.sessionpulse.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Who may run what, and who is offered what by tab completion. */
class CommandPermissionTest {

    private static final String REFUSED = "chat:You do not have permission to do that.";

    @TempDir
    Path dir;

    @Test
    @DisplayName("without sessionpulse.use every subcommand is refused and nothing completes")
    void withoutUseEverythingIsRefused() {
        CommandFixture fx = new CommandFixture(dir);
        fx.join(CommandFixture.player("Grace", Set.of(SessionPulseCommand.USE)));
        Player nobody = fx.join(CommandFixture.player("Nobody", Set.of()));

        for (String[] args : List.of(new String[0], new String[] {"time"},
                new String[] {"time", "Grace"}, new String[] {"top"},
                new String[] {"reset", "Grace"}, new String[] {"reload"}, new String[] {"x"})) {
            assertEquals(List.of(REFUSED), fx.run(nobody, args), String.join(" ", args));
        }
        assertEquals(0, fx.reloads);
        for (String[] args : List.of(new String[] {""}, new String[] {"t"},
                new String[] {"time", ""}, new String[] {"reset", "G"}, new String[] {"a", "b", "c"})) {
            assertEquals(List.of(), fx.complete(nobody, args), String.join(" ", args));
        }
    }

    @Test
    @DisplayName("with sessionpulse.use only, the staff tools are refused and not offered")
    void useOnlyIsRefusedTheStaffTools() {
        CommandFixture fx = new CommandFixture(dir);
        fx.join(CommandFixture.player("Grace", Set.of(SessionPulseCommand.USE)));
        Player ada = fx.join(CommandFixture.player("Ada", Set.of(SessionPulseCommand.USE)));

        assertEquals(List.of(REFUSED), fx.run(ada, "reset", "Grace"));
        assertEquals(List.of(REFUSED), fx.run(ada, "reload"));
        assertEquals(List.of(REFUSED), fx.run(ada, "time", "Grace"),
                "another player's time needs sessionpulse.admin");
        // The refusal comes before any lookup, so it reads the same for a name with no record
        // and for an offline name with one: the reply cannot be used to probe the store.
        Player grace = fx.online.get("grace");
        fx.tick(java.time.Duration.ofMinutes(5));
        fx.quit(grace);
        assertEquals(List.of(REFUSED), fx.run(ada, "time", "Grace"), "offline, with a record");
        assertEquals(List.of(REFUSED), fx.run(ada, "time", "Nobody"), "no record at all");
        assertEquals(0, fx.reloads);

        List<String> own = fx.run(ada, "time", "ADA");
        assertEquals(1, own.size());
        assertTrue(own.get(0).startsWith("chat:Your counted window"), own.get(0));

        assertEquals(List.of("time", "top"), fx.complete(ada, ""));
        assertEquals(List.of("Ada"), fx.complete(ada, "time", ""),
                "a player without admin is offered only their own name");
        assertEquals(List.of(), fx.complete(ada, "reset", ""));

        List<String> help = fx.run(ada);
        assertTrue(help.stream().noneMatch(line -> line.contains("reset") || line.contains("reload")),
                "the help advertises commands this sender cannot run: " + help);
    }

    @Test
    @DisplayName("an admin is offered every subcommand and the online names they can see")
    void adminCompletesSubcommandsAndVisibleNames() {
        CommandFixture fx = new CommandFixture(dir);
        fx.join(CommandFixture.player("Grace", Set.of()));
        fx.join(CommandFixture.player("gregor", Set.of()));
        fx.join(CommandFixture.player("Hidden", Set.of()));
        Player admin = fx.join(CommandFixture.player("Admin", UUID.randomUUID(),
                Set.of(SessionPulseCommand.USE, SessionPulseCommand.ADMIN), Set.of("Hidden")));

        assertEquals(List.of("time", "top", "reset", "reload"), fx.complete(admin, ""));
        assertEquals(List.of("reset", "reload"), fx.complete(admin, "RE"));
        assertEquals(List.of("Grace", "gregor"), fx.complete(admin, "reset", "gr"));
        assertEquals(List.of("Admin", "Grace", "gregor"), fx.complete(admin, "time", ""),
                "a vanished player must not be revealed by completion");
        assertEquals(List.of("Admin", "Grace", "gregor", "Hidden"),
                fx.complete(CommandFixture.console(), "time", ""));
        assertEquals(List.of(), fx.complete(admin, "top", ""));
    }
}
