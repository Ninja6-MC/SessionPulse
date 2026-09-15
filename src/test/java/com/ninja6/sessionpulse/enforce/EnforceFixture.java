package com.ninja6.sessionpulse.enforce;

import com.ninja6.sessionpulse.config.PluginConfig;
import com.ninja6.sessionpulse.listeners.LoginGateListener;
import com.ninja6.sessionpulse.notify.Notifier;
import com.ninja6.sessionpulse.notify.TestNotifiers;
import com.ninja6.sessionpulse.platform.RecordingScheduler;
import com.ninja6.sessionpulse.reminder.ReminderObserver;
import com.ninja6.sessionpulse.session.PlayerSession;
import com.ninja6.sessionpulse.session.SessionClock;
import com.ninja6.sessionpulse.session.SessionTracker;
import com.ninja6.sessionpulse.storage.YamlDataStorage;
import java.io.StringReader;
import java.lang.reflect.Proxy;
import java.net.InetAddress;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;

/**
 * One server's worth of enforcement, without a server.
 *
 * <p>The storage is the real {@link YamlDataStorage} over a file in the test's temporary
 * directory, not a fake: the claims these tests make are about what reaches the disk, in
 * which flush, and what the next instance reads back. A {@link #restart} builds the next
 * server over the same file and the same clock.
 *
 * <p>The player is a {@link Proxy} answering only what enforcement and the recording scheduler
 * ask of it. Every permission read and every kick is stamped with whether it ran inside an
 * entity task, and each kick records the cooldown and the live window as they stood at that
 * moment, so the order of the region task's steps is observable.
 */
final class EnforceFixture {

    /**
     * Enforcement on at 240 minutes with a 30-minute cooldown, every placeholder in the text.
     * No milestones, so every flush a test sees was asked for by enforcement.
     */
    static final String ENABLED = """
            reminders:
              prefix: ""
              milestones: []
            enforcement:
              enabled: true
              at-minutes: 240
              cooldown-minutes: 30
              kick-message: "<yellow>Break <player> <hours> <minutes> <cooldown></yellow>"
            """;

    /** {@link #ENABLED} with the switch off. */
    static final String DISABLED = ENABLED.replace("enabled: true", "enabled: false");

    /** What the kick says at a claimed window of exactly 240 minutes. */
    static final String KICK_AT_240 = "§eBreak Ada 4.0 240 30";

    /** A clock the test moves by hand. */
    static final class Clock implements SessionClock {
        long nanos = 9_000_000_000L;
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

    /** One kick, and what was true at the instant it happened. */
    record Kick(String message, boolean inEntity, long cooldownAtKick, long liveWindowAtKick) {
    }

    final Path file;
    final Clock clock;
    PluginConfig config;
    final RecordingScheduler scheduler = new RecordingScheduler();
    final YamlDataStorage storage;
    final SessionTracker tracker;
    final TestNotifiers.RecordingAudience audience = new TestNotifiers.RecordingAudience();
    final Notifier notifier;
    final EnforcementService service;
    final ReminderObserver reminders;
    final LoginGateListener gate;

    final UUID uuid = UUID.randomUUID();
    boolean exempt;
    final List<Boolean> permissionReadInEntity = new ArrayList<>();
    final List<Kick> kicks = new ArrayList<>();
    final Player player;

    EnforceFixture(Path dir, String yaml) {
        this(dir.resolve("data.yml"), new Clock(), parse(yaml), null);
    }

    private EnforceFixture(Path file, Clock clock, PluginConfig config, UUID uuid) {
        this.file = file;
        this.clock = clock;
        this.config = config;
        this.storage = new YamlDataStorage(file, scheduler, () -> this.config, silentLogger());
        storage.loadFromDisk();
        this.tracker = new SessionTracker(() -> this.config, clock, storage);
        this.notifier = TestNotifiers.recording(() -> this.config, audience);
        this.service = new EnforcementService(tracker, scheduler, notifier, storage, clock,
                () -> this.config);
        this.reminders = new ReminderObserver(tracker, scheduler, notifier, storage::flushAsync);
        this.gate = new LoginGateListener(storage, clock, () -> this.config, notifier);
        UUID id = uuid == null ? this.uuid : uuid;
        this.player = (Player) Proxy.newProxyInstance(Player.class.getClassLoader(),
                new Class<?>[] {Player.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "hasPermission" -> {
                        permissionReadInEntity.add(scheduler.inEntity);
                        yield exempt && ReminderObserver.EXEMPT_PERMISSION.equals(args[0]);
                    }
                    case "kickPlayer" -> {
                        PlayerSession live = tracker.session(id);
                        kicks.add(new Kick((String) args[0], scheduler.inEntity,
                                storage.cooldownExpiresMillis(id),
                                live == null ? -1L : live.windowSeconds()));
                        yield null;
                    }
                    case "getUniqueId" -> id;
                    case "equals" -> proxy == args[0];
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "toString" -> "Ada";
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    /** The next server start over the same file, clock and configuration, same player. */
    EnforceFixture restart() {
        return new EnforceFixture(file, clock, config, id());
    }

    static PluginConfig parse(String yaml) {
        return new PluginConfig(YamlConfiguration.loadConfiguration(new StringReader(yaml)));
    }

    private static Logger silentLogger() {
        Logger logger = Logger.getAnonymousLogger();
        logger.setUseParentHandlers(false);
        return logger;
    }

    UUID id() {
        return player.getUniqueId();
    }

    PlayerSession join() {
        return tracker.onJoin(id(), "Ada");
    }

    /** What PlayerConnectionListener does on the quit a kick causes. */
    void quit() {
        tracker.onQuit(id());
        storage.flushAsync();
    }

    /** One session tick after {@code played}, observers in the plugin's order. */
    PlayerSession tick(Duration played) {
        clock.advance(played);
        PlayerSession session = tracker.accrue(id(), false);
        if (session != null) {
            reminders.afterAccrual(player, session);
            service.afterAccrual(player, session);
        }
        return session;
    }

    /** A pre-login for the player through the gate, as the connection thread would run it. */
    AsyncPlayerPreLoginEvent preLogin() {
        AsyncPlayerPreLoginEvent event =
                new AsyncPlayerPreLoginEvent("Ada", InetAddress.getLoopbackAddress(), id());
        gate.onPreLogin(event);
        return event;
    }
}
