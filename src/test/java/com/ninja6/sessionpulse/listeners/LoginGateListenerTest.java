package com.ninja6.sessionpulse.listeners;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.ninja6.sessionpulse.config.PluginConfig;
import com.ninja6.sessionpulse.notify.TestNotifiers;
import com.ninja6.sessionpulse.platform.RecordingScheduler;
import com.ninja6.sessionpulse.session.SessionClock;
import com.ninja6.sessionpulse.storage.YamlDataStorage;
import java.io.StringReader;
import java.net.InetAddress;
import java.nio.file.Path;
import java.util.UUID;
import java.util.logging.Logger;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent.Result;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * {@link LoginGateListener}: refuses while a cooldown runs, rounds the time left up, and
 * leaves every other connection exactly as it found it.
 *
 * <p>Storage is the real {@link YamlDataStorage} over a temporary file, loaded empty; the
 * gate only reads it.
 */
class LoginGateListenerTest {

    private static final String ENABLED = """
            enforcement:
              enabled: true
              kick-message: "<yellow>Back in <cooldown> <player></yellow>"
            """;

    private static final long NOW = 1_700_000_000_000L;

    @TempDir
    Path dir;

    private PluginConfig config = parse(ENABLED);
    private long wallMillis = NOW;
    private final SessionClock clock = new SessionClock() {
        @Override
        public long nanoTime() {
            return 0L;
        }

        @Override
        public long wallMillis() {
            return wallMillis;
        }
    };
    private final UUID uuid = UUID.randomUUID();
    private YamlDataStorage storage;
    private LoginGateListener gate;

    private static PluginConfig parse(String yaml) {
        return new PluginConfig(YamlConfiguration.loadConfiguration(new StringReader(yaml)));
    }

    @BeforeEach
    void setUp() {
        Logger logger = Logger.getAnonymousLogger();
        logger.setUseParentHandlers(false);
        storage = new YamlDataStorage(dir.resolve("data.yml"), new RecordingScheduler(),
                () -> config, logger);
        storage.loadFromDisk();
        gate = new LoginGateListener(storage, clock, () -> config,
                TestNotifiers.recording(() -> config, new TestNotifiers.RecordingAudience()));
    }

    private AsyncPlayerPreLoginEvent preLogin() {
        AsyncPlayerPreLoginEvent event =
                new AsyncPlayerPreLoginEvent("Ada", InetAddress.getLoopbackAddress(), uuid);
        gate.onPreLogin(event);
        return event;
    }

    @Test
    @DisplayName("no cooldown on record is admitted")
    void noRecordAdmitted() {
        assertEquals(Result.ALLOWED, preLogin().getLoginResult());
    }

    @ParameterizedTest(name = "{0} ms left reads as {1} minute(s)")
    @CsvSource({"1, 1", "60000, 1", "60001, 2", "1800000, 30"})
    @DisplayName("a running cooldown refuses, with the minutes left rounded up")
    void runningCooldownRefusedRoundedUp(long remainingMillis, String minutes) {
        storage.setCooldown(uuid, "Ada", NOW + remainingMillis);

        AsyncPlayerPreLoginEvent event = preLogin();

        assertEquals(Result.KICK_OTHER, event.getLoginResult());
        assertEquals("§eBack in " + minutes + " Ada", event.getKickMessage());
    }

    @Test
    @DisplayName("a cooldown expiring exactly now has lapsed")
    void expiringNowAdmitted() {
        storage.setCooldown(uuid, "Ada", NOW);

        assertEquals(Result.ALLOWED, preLogin().getLoginResult());
    }

    @Test
    @DisplayName("a connection another plugin already refused is left untouched")
    void alreadyDisallowedUntouched() {
        storage.setCooldown(uuid, "Ada", NOW + 60_000L);
        AsyncPlayerPreLoginEvent event =
                new AsyncPlayerPreLoginEvent("Ada", InetAddress.getLoopbackAddress(), uuid);
        event.disallow(Result.KICK_BANNED, "banned");

        gate.onPreLogin(event);

        assertEquals(Result.KICK_BANNED, event.getLoginResult());
        assertEquals("banned", event.getKickMessage());
    }

    @Test
    @DisplayName("with enforcement off, a running cooldown is ignored")
    void disabledAdmitsDespiteCooldown() {
        storage.setCooldown(uuid, "Ada", NOW + 60_000L);
        config = parse(ENABLED.replace("enabled: true", "enabled: false"));

        assertEquals(Result.ALLOWED, preLogin().getLoginResult());
    }
}
