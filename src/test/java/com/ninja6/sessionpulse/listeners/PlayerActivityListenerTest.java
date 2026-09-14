package com.ninja6.sessionpulse.listeners;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ninja6.sessionpulse.afk.AfkFixture;
import com.ninja6.sessionpulse.afk.AfkService;
import com.ninja6.sessionpulse.afk.BuiltInAfkDetector;
import com.ninja6.sessionpulse.afk.EssentialsAfkDetector;
import com.ninja6.sessionpulse.afk.FakeEssentials;
import com.ninja6.sessionpulse.afk.NoOpAfkDetector;
import com.ninja6.sessionpulse.config.PluginConfig;
import com.ninja6.sessionpulse.platform.RecordingScheduler;
import java.lang.reflect.Proxy;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.event.server.PluginEnableEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link PlayerActivityListener}: what counts as input, what a join and a quit do to the
 * detectors, and that EssentialsX going away mid-run re-resolves to something that works.
 */
class PlayerActivityListenerTest {

    private final AfkFixture.Clock clock = new AfkFixture.Clock();
    private final RecordingScheduler scheduler = new RecordingScheduler();
    private final AfkFixture.Log log = new AfkFixture.Log();
    private PluginConfig config = AfkFixture.config("AUTO", 300);
    private final BuiltInAfkDetector builtIn = new BuiltInAfkDetector(clock, () -> config);
    private Object essentials;
    private final AfkService service =
            new AfkService(() -> essentials, builtIn, scheduler, log.logger, () -> config);
    private final PlayerActivityListener listener = new PlayerActivityListener(service);
    private final UUID uuid = UUID.randomUUID();
    private final Player player = AfkFixture.player(uuid, "Ada", false, scheduler, new int[1]);

    private boolean countedAsInput(Runnable event) {
        builtIn.seed(uuid);
        clock.advanceSeconds(299);
        event.run();
        clock.advanceSeconds(1);
        return !builtIn.isAfk(player);
    }

    private static Plugin plugin(String name) {
        return (Plugin) Proxy.newProxyInstance(Plugin.class.getClassLoader(),
                new Class<?>[] {Plugin.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "getName" -> name;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    @Test
    @DisplayName("clicking a block or the air counts")
    void aClickCounts() {
        assertTrue(countedAsInput(() -> listener.onPlayerInteract(
                new PlayerInteractEvent(player, Action.RIGHT_CLICK_AIR, null, null, BlockFace.SELF))));
    }

    @Test
    @DisplayName("a pressure plate underfoot does not count")
    void aPhysicalInteractDoesNotCount() {
        assertFalse(countedAsInput(() -> listener.onPlayerInteract(
                new PlayerInteractEvent(player, Action.PHYSICAL, null, null, BlockFace.SELF))),
                "a player parked on a plate would never be idle");
    }

    @Test
    @DisplayName("an inventory click counts")
    void anInventoryClickCounts() {
        Inventory chest = (Inventory) Proxy.newProxyInstance(Inventory.class.getClassLoader(),
                new Class<?>[] {Inventory.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "getSize" -> 27;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        InventoryView view = new InventoryView() {
            @Override
            public Inventory getTopInventory() {
                return chest;
            }

            @Override
            public Inventory getBottomInventory() {
                return chest;
            }

            @Override
            public HumanEntity getPlayer() {
                return player;
            }

            @Override
            public InventoryType getType() {
                return InventoryType.CHEST;
            }

            @Override
            public String getTitle() {
                return "shop";
            }

            @Override
            public String getOriginalTitle() {
                return "shop";
            }

            @Override
            public void setTitle(String title) {
            }
        };

        assertTrue(countedAsInput(() -> listener.onInventoryClick(new InventoryClickEvent(view,
                InventoryType.SlotType.CONTAINER, 0, ClickType.LEFT, InventoryAction.PICKUP_ALL))));
    }

    @Test
    @DisplayName("a chat line counts")
    void chatCounts() {
        assertTrue(countedAsInput(() -> listener.onPlayerChat(
                new AsyncPlayerChatEvent(true, player, "hi", Set.of()))));
    }

    @Test
    @DisplayName("a typed command counts")
    void aCommandCounts() {
        assertTrue(countedAsInput(() -> listener.onPlayerCommand(
                new PlayerCommandPreprocessEvent(player, "/spawn"))));
    }

    @Test
    @DisplayName("a join starts the idle timer")
    void aJoinSeeds() {
        listener.onPlayerJoin(new PlayerJoinEvent(player, "joined"));
        clock.advanceSeconds(300);

        assertTrue(builtIn.isAfk(player), "a joiner who never moves goes AFK at the threshold");
    }

    @Test
    @DisplayName("chat delivered after the quit does not bring the player back")
    void lateChatAfterQuitDoesNotLeak() {
        listener.onPlayerJoin(new PlayerJoinEvent(player, "joined"));
        listener.onPlayerQuit(new PlayerQuitEvent(player, "bye"));

        listener.onPlayerChat(new AsyncPlayerChatEvent(true, player, "late", Set.of()));
        clock.advanceSeconds(300);

        assertFalse(builtIn.isAfk(player),
                "an entry exists again, and would stay for the rest of the run");
    }

    @Test
    @DisplayName("a quit clears the player from the EssentialsX detector as well as the timer")
    void aQuitForgetsFromBothDetectors() {
        scheduler.deferEntity = true;
        FakeEssentials fake = new FakeEssentials(scheduler, 300);
        essentials = fake;
        service.resolve();
        listener.onPlayerJoin(new PlayerJoinEvent(player, "joined"));
        fake.user(uuid).afk = true;
        service.isAfk(player);
        scheduler.runEntity();
        assertTrue(service.isAfk(player), "cached as AFK by EssentialsX before the quit");

        listener.onPlayerQuit(new PlayerQuitEvent(player, "bye"));
        clock.advanceSeconds(300);

        assertFalse(builtIn.isAfk(player), "the timer forgot them");
        assertFalse(service.isAfk(player), "the EssentialsX detector forgot them");
    }

    @Test
    @DisplayName("EssentialsX disabled mid-run in AUTO: the timer takes over, and nothing throws")
    void essentialsDisabledInAuto() {
        FakeEssentials fake = new FakeEssentials(scheduler, 300);
        essentials = fake;
        service.resolve();
        assertInstanceOf(EssentialsAfkDetector.class, service.current());

        // Bukkit order: the plugin still reports itself enabled while its disable event runs.
        listener.onPluginDisable(new PluginDisableEvent(plugin("Essentials")));
        essentials = null;

        assertSame(builtIn, service.current());
        fake.getUserThrows = new NoClassDefFoundError("com/earth2me/essentials/User");
        assertDoesNotThrow(() -> service.isAfk(player));
    }

    @Test
    @DisplayName("EssentialsX disabled mid-run in ESSENTIALS: nobody is AFK, said with a WARNING")
    void essentialsDisabledInEssentialsMode() {
        config = AfkFixture.config("ESSENTIALS", 300);
        essentials = new FakeEssentials(scheduler, 300);
        service.resolve();
        log.clear();

        essentials = null;
        listener.onPluginDisable(new PluginDisableEvent(plugin("Essentials")));

        assertSame(NoOpAfkDetector.INSTANCE, service.current());
        assertEquals(1, log.at(Level.WARNING).size());
        assertDoesNotThrow(() -> service.isAfk(player));
    }

    @Test
    @DisplayName("another plugin disabling changes nothing")
    void anotherPluginDisablingIsIgnored() {
        essentials = new FakeEssentials(scheduler, 300);
        service.resolve();
        var before = service.current();

        listener.onPluginDisable(new PluginDisableEvent(plugin("WorldEdit")));

        assertSame(before, service.current());
    }

    @Test
    @DisplayName("EssentialsX enabled after SessionPulse is picked up")
    void essentialsEnabledLater() {
        service.resolve();
        assertSame(builtIn, service.current());

        essentials = new FakeEssentials(scheduler, 300);
        listener.onPluginEnable(new PluginEnableEvent(plugin("Essentials")));

        assertInstanceOf(EssentialsAfkDetector.class, service.current());
    }
}
