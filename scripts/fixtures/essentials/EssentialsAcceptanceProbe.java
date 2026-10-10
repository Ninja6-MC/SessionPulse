package com.ninja6.sessionpulse.fixture;

import com.ninja6.sessionpulse.SessionPulsePlugin;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;

/** Disposable Paper fixture; not part of the shipped plugin. */
public final class EssentialsAcceptanceProbe extends JavaPlugin implements Listener {
    @Override
    public void onEnable() {
        getServer().getPluginManager().registerEvents(this, this);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (!player.getName().matches("SPX(Auto|Fallback|Manual)")) return;
        var attachment = player.addAttachment(this);
        attachment.setPermission("essentials.afk", true);
        attachment.setPermission("essentials.afk.auto", player.getName().equals("SPXAuto"));
        attachment.setPermission("essentials.afk.kickexempt", true);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof ConsoleCommandSender)) return true;
        try {
            var pulse = (SessionPulsePlugin) getServer().getPluginManager().getPlugin("SessionPulse");
            var essentials = getServer().getPluginManager().getPlugin("Essentials");
            if (pulse == null || essentials == null) throw new IllegalStateException("Missing fixture plugins");
            for (Player player : getServer().getOnlinePlayers()) {
                if (!player.getName().matches("SPX(Auto|Fallback|Manual)")) continue;
                var session = pulse.tracker().session(player.getUniqueId());
                Object user = essentials.getClass().getMethod("getUser", Player.class).invoke(essentials, player);
                boolean afk = (boolean) user.getClass().getMethod("isAfk").invoke(user);
                Object settings = essentials.getClass().getMethod("getSettings").invoke(essentials);
                long threshold = ((Number) settings.getClass().getMethod("getAutoAfk").invoke(settings)).longValue();
                getLogger().info("SPX_SAMPLE {\"name\":\"" + player.getName() + "\",\"seconds\":"
                        + session.windowSeconds() + ",\"lifetime\":" + session.lifetimeSeconds()
                        + ",\"essentialsAfk\":" + afk + ",\"autoPermission\":"
                        + player.hasPermission("essentials.afk.auto") + ",\"threshold\":" + threshold
                        + ",\"adminPermission\":" + player.hasPermission("sessionpulse.admin")
                        + ",\"fired\":" + session.firedMinutes() + "}");
            }
        } catch (Exception exception) {
            getLogger().severe("SPX_FIXTURE_ERROR " + exception);
        }
        return true;
    }
}
