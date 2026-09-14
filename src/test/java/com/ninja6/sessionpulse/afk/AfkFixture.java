package com.ninja6.sessionpulse.afk;

import com.ninja6.sessionpulse.config.PluginConfig;
import com.ninja6.sessionpulse.platform.RecordingScheduler;
import com.ninja6.sessionpulse.session.SessionClock;
import java.io.StringReader;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.bukkit.Server;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

/**
 * What the AFK tests share: a hand-driven clock, a configuration, a player, and a log that
 * can be read back.
 *
 * <p>{@code public} so the activity listener's tests in their own package can use it.
 * Test-only visibility, the same reasoning as {@code RecordingScheduler}.
 */
public final class AfkFixture {

    private AfkFixture() {
    }

    /** A {@link SessionClock} moved by hand, starting away from zero. */
    public static final class Clock implements SessionClock {

        private long nanos = 7_000_000_000L;

        @Override
        public long nanoTime() {
            return nanos;
        }

        @Override
        public long wallMillis() {
            return 1_700_000_000_000L + nanos / 1_000_000L;
        }

        public void advance(Duration duration) {
            nanos += duration.toNanos();
        }

        public void advanceSeconds(long seconds) {
            advance(Duration.ofSeconds(seconds));
        }
    }

    /** A logger whose every record is kept, in order. */
    public static final class Log extends Handler {

        public final List<LogRecord> records = new ArrayList<>();
        public final Logger logger;

        public Log() {
            logger = Logger.getAnonymousLogger();
            logger.setUseParentHandlers(false);
            logger.setLevel(Level.ALL);
            logger.addHandler(this);
        }

        @Override
        public synchronized void publish(LogRecord record) {
            records.add(record);
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }

        public synchronized List<LogRecord> at(Level level) {
            return records.stream().filter(r -> r.getLevel() == level).toList();
        }

        public synchronized void clear() {
            records.clear();
        }
    }

    /**
     * A configuration with the given mode and idle threshold. The mode is quoted: YAML reads a
     * bare {@code OFF} as the boolean {@code false}.
     */
    public static PluginConfig config(String mode, int idleSeconds) {
        return new PluginConfig(YamlConfiguration.loadConfiguration(new StringReader(
                "tracking:\n  afk:\n    mode: \"" + mode + "\"\n    idle-seconds: " + idleSeconds
                        + "\n")));
    }

    /**
     * A player answering only what the AFK code and the events built around it ask.
     *
     * <p>A permission read outside an entity task is counted in {@code violations} and throws,
     * because permission plugins are not obliged to be thread-safe and the tick is not the
     * player's region. Anything else it is asked throws.
     *
     * @param hasAutoAfk what {@code essentials.afk.auto} answers
     */
    public static Player player(UUID uuid, String name, boolean hasAutoAfk,
                                RecordingScheduler scheduler, int[] violations) {
        return player(uuid, name, hasAutoAfk, scheduler, violations, new boolean[] {true});
    }

    /**
     * The same player, whose {@code isValid} answers {@code valid[0]}, so a test can make them
     * leave while a refresh is queued.
     */
    public static Player player(UUID uuid, String name, boolean hasAutoAfk,
                                RecordingScheduler scheduler, int[] violations, boolean[] valid) {
        Server server = (Server) Proxy.newProxyInstance(Server.class.getClassLoader(),
                new Class<?>[] {Server.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "getOnlinePlayers" -> List.of();
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    case "toString" -> "Server";
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        return (Player) Proxy.newProxyInstance(Player.class.getClassLoader(),
                new Class<?>[] {Player.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "getUniqueId" -> uuid;
                    case "getName" -> name;
                    case "getServer" -> server;
                    case "isValid" -> valid[0];
                    case "hasPermission" -> {
                        if (!scheduler.inEntity) {
                            violations[0]++;
                            throw new AssertionError("permission read off the player's region");
                        }
                        yield EssentialsAfkDetector.AUTO_AFK_PERMISSION.equals(args[0]) && hasAutoAfk;
                    }
                    case "hashCode" -> uuid.hashCode();
                    case "equals" -> proxy == args[0];
                    case "toString" -> "Player[" + name + "]";
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }
}
