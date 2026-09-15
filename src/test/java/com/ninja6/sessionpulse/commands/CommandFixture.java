package com.ninja6.sessionpulse.commands;

import com.ninja6.sessionpulse.config.PluginConfig;
import com.ninja6.sessionpulse.notify.Notifier;
import com.ninja6.sessionpulse.notify.TestNotifiers;
import com.ninja6.sessionpulse.platform.RecordingScheduler;
import com.ninja6.sessionpulse.session.SessionClock;
import com.ninja6.sessionpulse.session.SessionTracker;
import com.ninja6.sessionpulse.storage.YamlDataStorage;
import java.io.StringReader;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

/**
 * One server's worth of {@code /spulse}, without a server.
 *
 * <p>The store is the real {@link YamlDataStorage} over a file in the test's temporary
 * directory and the tracker is the real one, because what these tests claim is what a command
 * leaves in both. Players and the console are {@link Proxy} fakes answering only what the
 * command asks of them. The online list and the exact-name lookup are this fixture's own map,
 * handed to the command the way the plugin hands it the server's.
 *
 * <p>The clock is a local fake rather than {@code session.TestClock}, which is package-private
 * there; the few lines are repeated here rather than widened, as {@code StorageFixture} and
 * {@code EnforceFixture} already do.
 */
final class CommandFixture {

    /** Prefix off, one milestone at 60 minutes, windows reset after 8 hours offline. */
    static final String YAML = """
            reminders:
              prefix: ""
              milestones:
                - minute: 60
                  message: "<aqua>An hour</aqua>"
            tracking:
              window-reset-hours: 8
            """;

    /** A clock the test moves by hand. */
    static final class Clock implements SessionClock {
        long nanos = 5_000_000_000L;
        long wallMillis = 1_700_000_000_000L;

        @Override
        public long nanoTime() {
            return nanos;
        }

        @Override
        public long wallMillis() {
            return wallMillis;
        }

        void advance(Duration played) {
            nanos += played.toNanos();
            wallMillis += played.toMillis();
        }

        void advanceWallOnly(Duration offline) {
            wallMillis += offline.toMillis();
        }
    }

    final Path file;
    final Clock clock = new Clock();
    final PluginConfig config;
    final RecordingScheduler scheduler = new RecordingScheduler();
    final YamlDataStorage storage;
    final SessionTracker tracker;
    final TestNotifiers.RecordingAudience audience = new TestNotifiers.RecordingAudience();
    final Notifier notifier;
    final List<LogRecord> logged = Collections.synchronizedList(new ArrayList<>());
    final Map<String, Player> online = new LinkedHashMap<>();
    int reloads;
    RuntimeException reloadFailure;
    final SessionPulseCommand command;

    CommandFixture(Path dir) {
        this.file = dir.resolve("data.yml");
        this.config = new PluginConfig(YamlConfiguration.loadConfiguration(new StringReader(YAML)));
        Logger logger = Logger.getAnonymousLogger();
        logger.setUseParentHandlers(false);
        logger.addHandler(new Handler() {
            @Override
            public void publish(LogRecord record) {
                logged.add(record);
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        });
        this.storage = new YamlDataStorage(file, scheduler, () -> config, logger);
        storage.loadFromDisk();
        this.tracker = new SessionTracker(() -> config, clock, storage);
        this.notifier = TestNotifiers.recording(() -> config, audience);
        this.command = new SessionPulseCommand(tracker, storage, notifier, clock, () -> config,
                () -> {
                    reloads++;
                    if (reloadFailure != null) {
                        throw reloadFailure;
                    }
                },
                logger,
                name -> online.get(name.toLowerCase(Locale.ROOT)),
                () -> (Collection<? extends Player>) online.values());
    }

    /** A player holding exactly {@code permissions}, who can see everyone but {@code hidden}. */
    static Player player(String name, UUID uuid, Set<String> permissions, Set<String> hidden) {
        return (Player) Proxy.newProxyInstance(Player.class.getClassLoader(),
                new Class<?>[] {Player.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "hasPermission" -> permissions.contains((String) args[0]);
                    case "getName" -> name;
                    case "getUniqueId" -> uuid;
                    case "canSee" -> !hidden.contains(((Player) args[0]).getName());
                    case "equals" -> proxy == args[0];
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "toString" -> name;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    static Player player(String name, Set<String> permissions) {
        return player(name, UUID.randomUUID(), permissions, Set.of());
    }

    /** The console: every permission, and not a player. */
    static CommandSender console() {
        return (CommandSender) Proxy.newProxyInstance(ConsoleCommandSender.class.getClassLoader(),
                new Class<?>[] {ConsoleCommandSender.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "hasPermission" -> true;
                    case "getName" -> "CONSOLE";
                    case "equals" -> proxy == args[0];
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "toString" -> "CONSOLE";
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    /** Puts {@code player} online: in the lookup, and joined to the tracker as the listener would. */
    Player join(Player player) {
        online.put(player.getName().toLowerCase(Locale.ROOT), player);
        tracker.onJoin(player.getUniqueId(), player.getName());
        return player;
    }

    /** Takes {@code player} offline the way the quit listener does. */
    void quit(Player player) {
        online.remove(player.getName().toLowerCase(Locale.ROOT));
        tracker.onQuit(player.getUniqueId());
    }

    /** One session tick after {@code played}, for every tracked player. */
    void tick(Duration played) {
        clock.advance(played);
        tracker.sessions().forEach(session -> tracker.accrue(session.uuid(), false));
    }

    /** Runs {@code /spulse args...} as {@code sender} and returns only what it said, as text. */
    List<String> run(CommandSender sender, String... args) {
        int before = audience.calls().size();
        command.onCommand(sender, null, "spulse", args);
        return text(audience.calls().subList(before, audience.calls().size()));
    }

    List<String> complete(CommandSender sender, String... args) {
        return command.onTabComplete(sender, null, "spulse", args);
    }

    /** Recorded calls with the section-sign codes removed. */
    static List<String> text(List<String> calls) {
        List<String> out = new ArrayList<>();
        for (String call : calls) {
            out.add(call.replaceAll("§.", ""));
        }
        return out;
    }
}
