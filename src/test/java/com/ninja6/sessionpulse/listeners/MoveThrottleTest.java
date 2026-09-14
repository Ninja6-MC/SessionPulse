package com.ninja6.sessionpulse.listeners;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ninja6.sessionpulse.afk.AfkFixture;
import com.ninja6.sessionpulse.afk.AfkService;
import com.ninja6.sessionpulse.afk.BuiltInAfkDetector;
import com.ninja6.sessionpulse.config.PluginConfig;
import com.ninja6.sessionpulse.platform.RecordingScheduler;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.entity.Minecart;
import org.bukkit.entity.Player;
import org.bukkit.entity.Vehicle;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.vehicle.VehicleMoveEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Movement counts as input only when it crosses into another block.
 *
 * <p>Each case seeds the player, waits a second short of the threshold, fires one event and
 * waits the last second. A move that counted leaves the player active; one that did not
 * leaves them AFK.
 */
class MoveThrottleTest {

    private final AfkFixture.Clock clock = new AfkFixture.Clock();
    private final RecordingScheduler scheduler = new RecordingScheduler();
    private final PluginConfig config = AfkFixture.config("BUILT_IN", 300);
    private final BuiltInAfkDetector builtIn = new BuiltInAfkDetector(clock, () -> config);
    private final AfkService service = new AfkService(() -> null, builtIn, scheduler,
            new AfkFixture.Log().logger, () -> config);
    private final PlayerActivityListener listener = new PlayerActivityListener(service);
    private final UUID uuid = UUID.randomUUID();
    private final Player player = AfkFixture.player(uuid, "Ada", false, scheduler, new int[1]);

    /** Whether the player is still active a full threshold after the seed, with the event in between. */
    private boolean countedAsInput(Runnable event) {
        builtIn.seed(uuid);
        clock.advanceSeconds(299);
        event.run();
        clock.advanceSeconds(1);
        return !builtIn.isAfk(player);
    }

    private void move(Location from, Location to) {
        listener.onPlayerMove(new PlayerMoveEvent(player, from, to));
    }

    private static Location at(double x, double y, double z, float yaw, float pitch) {
        return new Location(null, x, y, z, yaw, pitch);
    }

    @Test
    @DisplayName("turning the head in place does not count")
    void headRotationDoesNotCount() {
        assertFalse(countedAsInput(() -> move(at(10.5, 64, 10.5, 0f, 0f), at(10.5, 64, 10.5, 90f, 30f))),
                "a player could stay active forever by nudging the mouse");
    }

    @Test
    @DisplayName("moving within the same block does not count")
    void movingWithinABlockDoesNotCount() {
        assertFalse(countedAsInput(() -> move(at(10.1, 64, 10.1, 0f, 0f), at(10.4, 64, 10.4, 0f, 0f))));
    }

    @Test
    @DisplayName("crossing into the next block counts")
    void crossingABlockBoundaryCounts() {
        assertTrue(countedAsInput(() -> move(at(10.9, 64, 10.5, 0f, 0f), at(11.1, 64, 10.5, 0f, 0f))));
    }

    @Test
    @DisplayName("crossing zero counts, where truncation would read -0.5 and 0.2 as the same block")
    void crossingZeroCounts() {
        assertTrue(countedAsInput(() -> move(at(-0.5, 64, 3.5, 0f, 0f), at(0.2, 64, 3.5, 0f, 0f))));
    }

    @Test
    @DisplayName("a change in Y alone counts")
    void aYChangeCounts() {
        assertTrue(countedAsInput(() -> move(at(10.5, 64.0, 10.5, 0f, 0f), at(10.5, 65.0, 10.5, 0f, 0f))));
    }

    @Test
    @DisplayName("a move with no destination does not count, and does not throw")
    void aNullDestinationDoesNotCount() {
        assertFalse(countedAsInput(() -> move(at(10.5, 64, 10.5, 0f, 0f), null)));
    }

    private Vehicle ridden(Class<? extends Vehicle> type) {
        return (Vehicle) Proxy.newProxyInstance(type.getClassLoader(),
                new Class<?>[] {type}, (proxy, method, args) -> switch (method.getName()) {
                    case "getPassengers" -> List.of(player);
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    @Test
    @DisplayName("a minecart crossing a block does not count; rails move it, not the rider")
    void minecartMovesDoNotCount() {
        Vehicle minecart = ridden(Minecart.class);

        assertFalse(countedAsInput(() -> listener.onVehicleMove(
                new VehicleMoveEvent(minecart, at(1.5, 62, 1.5, 0f, 0f), at(2.5, 62, 1.5, 0f, 0f)))),
                "a powered-rail loop would keep its rider active for ever");
    }

    @Test
    @DisplayName("a ridden vehicle crossing a block counts for the rider, and not within a block")
    void vehicleMovesCountForTheRider() {
        Vehicle boat = ridden(Vehicle.class);

        assertTrue(countedAsInput(() -> listener.onVehicleMove(
                new VehicleMoveEvent(boat, at(1.5, 62, 1.5, 0f, 0f), at(2.5, 62, 1.5, 0f, 0f)))));
        assertFalse(countedAsInput(() -> listener.onVehicleMove(
                new VehicleMoveEvent(boat, at(1.1, 62, 1.1, 0f, 0f), at(1.6, 62, 1.6, 45f, 0f)))));
    }
}
