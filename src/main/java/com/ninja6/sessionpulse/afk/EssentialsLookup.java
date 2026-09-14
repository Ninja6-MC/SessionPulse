package com.ninja6.sessionpulse.afk;

import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;

/**
 * Finds EssentialsX, by plugin name and nothing else.
 *
 * <p>A seam so the resolution rules can be tested against a plain object standing in for the
 * plugin. The return type is {@link Object} on purpose: nothing here may name an EssentialsX
 * type, or loading this class on a server without EssentialsX would fail.
 */
@FunctionalInterface
public interface EssentialsLookup {

    /** The name EssentialsX registers under. Also what {@code plugin.yml} soft-depends on. */
    String PLUGIN_NAME = "Essentials";

    /**
     * The EssentialsX plugin instance, if it is installed and enabled.
     *
     * @return the instance, or {@code null} when it is absent or disabled
     */
    Object enabledPlugin();

    /**
     * The lookup the plugin uses.
     *
     * @param plugins the server's plugin manager
     * @return a lookup reading the plugin manager on every call
     */
    static EssentialsLookup of(PluginManager plugins) {
        return () -> {
            Plugin essentials = plugins.getPlugin(PLUGIN_NAME);
            return essentials != null && essentials.isEnabled() ? essentials : null;
        };
    }
}
