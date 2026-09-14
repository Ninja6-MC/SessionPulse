package com.ninja6.sessionpulse.listeners;

import com.ninja6.sessionpulse.afk.AfkService;
import com.ninja6.sessionpulse.afk.BuiltInAfkDetector;
import com.ninja6.sessionpulse.afk.EssentialsLookup;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.event.server.PluginEnableEvent;
import org.bukkit.event.vehicle.VehicleMoveEvent;

/**
 * Feeds the idle timer, and re-resolves AFK detection when EssentialsX comes or goes.
 *
 * <p>Registered whatever the configured mode, so the timer is already warm when a reload or
 * a departing EssentialsX switches to it.
 *
 * <h2>What counts as input</h2>
 *
 * <p>Something the player did: walking into another block, riding into one, clicking a block
 * or the air, clicking in an inventory, chatting, typing a command. Turning the head does not
 * count, or a player could stay active by nudging the mouse, and neither does moving within a
 * block. A pressure plate or tripwire underfoot does not count either; it fires an interact
 * with no input at all. Nothing the server does to a player counts.
 *
 * <p>Cancelled events still count, which is why no handler sets {@code ignoreCancelled}. A
 * movement another plugin refused, such as EssentialsX freezing an AFK player, is still the
 * player pressing a key. {@link EventPriority#MONITOR} throughout, because nothing here
 * changes an event.
 *
 * <h2>Threads</h2>
 *
 * <p>Chat arrives on the async chat thread and everything else on the player's region. Every
 * handler only writes a monotonic reading into the timer's concurrent map, so none of them
 * touches anything a region owns.
 */
public final class PlayerActivityListener implements Listener {

    private final AfkService afk;
    private final BuiltInAfkDetector idle;

    /**
     * Creates the listener.
     *
     * @param afk the service whose idle timer this feeds, and which re-resolves on a plugin
     *            change
     */
    public PlayerActivityListener(AfkService afk) {
        this.afk = afk;
        this.idle = afk.builtIn();
    }

    /**
     * Counts a move into another block. Compares block coordinates as integers, which is both
     * the rule and the cheapest check this handler could make on the most frequent event there
     * is.
     *
     * @param event the move
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerMove(PlayerMoveEvent event) {
        if (changedBlock(event.getFrom(), event.getTo())) {
            idle.markActive(event.getPlayer().getUniqueId());
        }
    }

    /**
     * Counts a ridden vehicle moving into another block, for each player riding it. A rider
     * fires no {@link PlayerMoveEvent}, so without this a player steering a boat goes AFK.
     *
     * @param event the vehicle move
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onVehicleMove(VehicleMoveEvent event) {
        if (!changedBlock(event.getFrom(), event.getTo())) {
            return;
        }
        for (Entity passenger : event.getVehicle().getPassengers()) {
            if (passenger instanceof Player) {
                idle.markActive(passenger.getUniqueId());
            }
        }
    }

    /**
     * Counts a click, but not {@link Action#PHYSICAL}: that is a pressure plate, tripwire or
     * farmland, and a player parked on one would otherwise never be idle.
     *
     * @param event the interact
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.PHYSICAL) {
            idle.markActive(event.getPlayer().getUniqueId());
        }
    }

    /**
     * Counts a click in an inventory; a player browsing a shop menu is present.
     *
     * @param event the click
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onInventoryClick(InventoryClickEvent event) {
        idle.markActive(event.getWhoClicked().getUniqueId());
    }

    /**
     * Counts a chat line. Async thread; see the class comment.
     *
     * @param event the chat
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerChat(AsyncPlayerChatEvent event) {
        idle.markActive(event.getPlayer().getUniqueId());
    }

    /**
     * Counts a typed command.
     *
     * @param event the command
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerCommand(PlayerCommandPreprocessEvent event) {
        idle.markActive(event.getPlayer().getUniqueId());
    }

    /**
     * Starts the idle timer, so a player who joins and never moves still goes AFK.
     *
     * @param event the join
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        idle.seed(event.getPlayer().getUniqueId());
    }

    /**
     * Drops the player from the idle timer and from the detector in force.
     *
     * @param event the quit
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        afk.forget(event.getPlayer().getUniqueId());
    }

    /**
     * Re-resolves when EssentialsX is enabled after SessionPulse.
     *
     * @param event any plugin enabling
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPluginEnable(PluginEnableEvent event) {
        if (EssentialsLookup.PLUGIN_NAME.equals(event.getPlugin().getName())) {
            afk.resolve();
        }
    }

    /**
     * Re-resolves as though EssentialsX were gone when it is being disabled. AUTO falls to the
     * idle timer; ESSENTIALS says in the log that nobody will be treated as AFK.
     *
     * @param event any plugin disabling
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPluginDisable(PluginDisableEvent event) {
        if (EssentialsLookup.PLUGIN_NAME.equals(event.getPlugin().getName())) {
            afk.resolveWithoutEssentials();
        }
    }

    private static boolean changedBlock(Location from, Location to) {
        return to != null
                && (from.getBlockX() != to.getBlockX()
                        || from.getBlockY() != to.getBlockY()
                        || from.getBlockZ() != to.getBlockZ());
    }
}
