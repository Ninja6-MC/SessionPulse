package com.ninja6.sessionpulse.listeners;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.ninja6.sessionpulse.config.PluginConfig;
import com.ninja6.sessionpulse.session.SessionClock;
import com.ninja6.sessionpulse.session.SessionSnapshot;
import com.ninja6.sessionpulse.session.SessionStore;
import com.ninja6.sessionpulse.session.SessionTracker;
import java.io.StringReader;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerQuitEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A quit stores the session first and asks for a flush second.
 *
 * <p>The player is a {@link Proxy} answering only the two calls the listener makes. Anything
 * else it is asked throws, so a listener that starts reaching further into Bukkit fails here
 * rather than passing against a mock that says yes to everything.
 */
class PlayerConnectionListenerTest {

    @Test
    @DisplayName("a quit stores the session before it requests the flush")
    void quitRequestsAFlushAfterTheSessionIsStored() {
        UUID uuid = UUID.randomUUID();
        List<String> order = new ArrayList<>();

        SessionStore store = new SessionStore() {
            @Override
            public SessionSnapshot load(UUID key) {
                return SessionSnapshot.UNKNOWN;
            }

            @Override
            public void save(UUID key, SessionSnapshot snapshot) {
                order.add("save");
            }
        };
        SessionClock clock = new SessionClock() {
            @Override
            public long nanoTime() {
                return 1_000L;
            }

            @Override
            public long wallMillis() {
                return 1_700_000_000_000L;
            }
        };
        PluginConfig config = new PluginConfig(
                YamlConfiguration.loadConfiguration(new StringReader("")));
        SessionTracker tracker = new SessionTracker(() -> config, clock, store);
        PlayerConnectionListener listener =
                new PlayerConnectionListener(tracker, () -> order.add("flush"));

        Player player = (Player) Proxy.newProxyInstance(Player.class.getClassLoader(),
                new Class<?>[] {Player.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "getUniqueId" -> uuid;
                    case "getName" -> "Ada";
                    default -> throw new UnsupportedOperationException(method.getName());
                });

        tracker.onJoin(uuid, "Ada");
        listener.onPlayerQuit(new PlayerQuitEvent(player, "bye"));

        assertEquals(List.of("save", "flush"), order,
                "a flush requested before the save can run and find nothing new to write");
    }
}
