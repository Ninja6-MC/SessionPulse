package com.ninja6.sessionpulse.afk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ninja6.sessionpulse.config.PluginConfig;
import com.ninja6.sessionpulse.listeners.PlayerActivityListener;
import com.ninja6.sessionpulse.platform.RecordingScheduler;
import com.ninja6.sessionpulse.session.PlayerSession;
import com.ninja6.sessionpulse.session.SessionSnapshot;
import com.ninja6.sessionpulse.session.SessionStore;
import com.ninja6.sessionpulse.session.SessionTracker;
import java.util.List;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Death and respawn events reach the same AFK gate and accumulator as the session tick. */
class DeathScreenAccrualTest {

    @ParameterizedTest(name = "{0}, Essentials={1}, auto permission={2}")
    @CsvSource({
        "BUILT_IN,false,false", "AUTO,false,false", "AUTO,true,false", "AUTO,true,true",
        "ESSENTIALS,true,false", "ESSENTIALS,true,true", "ESSENTIALS,false,false",
        "OFF,false,false", "OFF,true,true"
    })
    void deathPausesUntilRespawnAndQuitClearsIt(String mode, boolean installed, boolean permission) {
        RecordingScheduler scheduler = new RecordingScheduler();
        scheduler.deferEntity = true;
        AfkFixture.Clock clock = new AfkFixture.Clock();
        PluginConfig config = AfkFixture.config(mode, 300);
        BuiltInAfkDetector idle = new BuiltInAfkDetector(clock, () -> config);
        FakeEssentials essentials = new FakeEssentials(scheduler, 300);
        AfkService service = new AfkService(() -> installed ? essentials : null, idle, scheduler,
                new AfkFixture.Log().logger, () -> config);
        service.resolve();
        PlayerActivityListener listener = new PlayerActivityListener(service);
        int[] violations = new int[1];
        UUID uuid = UUID.randomUUID();
        // This player throws on isDead/getHealth and off-region permission reads.
        Player player = AfkFixture.player(uuid, "Ada", permission, scheduler, violations);
        SessionTracker tracker = new SessionTracker(() -> config, clock, new SessionStore() {
            @Override
            public SessionSnapshot load(UUID id) {
                return SessionSnapshot.UNKNOWN;
            }

            @Override
            public void save(UUID id, SessionSnapshot snapshot) {
            }
        });
        PlayerSession session = tracker.onJoin(uuid, "Ada");
        listener.onPlayerJoin(new PlayerJoinEvent(player, "joined"));
        tick(clock, tracker, service, scheduler, player, 10);
        assertEquals(10, session.windowSeconds());

        listener.onPlayerDeath(new PlayerDeathEvent(player, List.of(), 0, "died"));
        boolean off = mode.equals("OFF");
        assertEquals(!off, service.isAfk(player), "death applies before any idle threshold");
        idle.markActive(uuid);
        assertEquals(!off, service.isAfk(player), "input cannot clear a death record");
        tick(clock, tracker, service, scheduler, player, 1);
        assertEquals(off ? 11 : 10, session.windowSeconds(), "the first dead tick must pause");
        tick(clock, tracker, service, scheduler, player, 600);
        assertEquals(off ? 611 : 10, session.windowSeconds());
        assertEquals(session.windowSeconds(), session.lifetimeSeconds());

        listener.onPlayerRespawn(new PlayerRespawnEvent(player,
                new Location(null, 0, 64, 0), false));
        assertFalse(service.isAfk(player), "respawn starts a fresh idle interval");
        assertFalse(idle.isAfk(player));
        tick(clock, tracker, service, scheduler, player, 30);
        assertEquals(off ? 641 : 40, session.windowSeconds(), "no dead time is back-dated");

        listener.onPlayerDeath(new PlayerDeathEvent(player, List.of(), 0, "died again"));
        listener.onPlayerQuit(new PlayerQuitEvent(player, "bye"));
        assertFalse(service.isAfk(player), "quit must remove the death record");
        listener.onPlayerJoin(new PlayerJoinEvent(player, "joined again"));
        assertFalse(service.isAfk(player), "a previous death cannot survive rejoining");
        assertEquals(0, violations[0]);
        assertEquals(0, essentials.offRegionCalls);
    }

    private static void tick(AfkFixture.Clock clock, SessionTracker tracker, AfkService service,
                             RecordingScheduler scheduler, Player player, int seconds) {
        for (int i = 0; i < seconds; i++) {
            clock.advanceSeconds(1);
            tracker.accrue(player.getUniqueId(), service.isAfk(player));
            scheduler.runEntity();
        }
    }

    @Test
    void lifecycleHandlersAreRegisteredObservers() throws ReflectiveOperationException {
        for (Class<?> event : List.of(PlayerDeathEvent.class, PlayerRespawnEvent.class)) {
            String method = event == PlayerDeathEvent.class ? "onPlayerDeath" : "onPlayerRespawn";
            EventHandler handler = PlayerActivityListener.class.getMethod(method, event)
                    .getAnnotation(EventHandler.class);
            assertTrue(handler != null, "Bukkit must discover the lifecycle handler");
            assertEquals(EventPriority.MONITOR, handler.priority());
        }
    }
}
