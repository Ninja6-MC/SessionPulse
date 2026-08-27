package com.ninja6.sessionpulse;

import org.bukkit.plugin.java.JavaPlugin;

/**
 * Plugin lifecycle entrypoint for SessionPulse.
 *
 * <p>Deliberately empty beyond the two lifecycle callbacks. The command declared in
 * {@code plugin.yml} has no executor yet, so {@code /spulse} prints its usage string on a
 * live server until the command issue lands.
 */
public class SessionPulsePlugin extends JavaPlugin {

    @Override
    public void onEnable() {
        getLogger().info("SessionPulse enabled.");
    }

    @Override
    public void onDisable() {
        getLogger().info("SessionPulse disabled.");
    }
}
