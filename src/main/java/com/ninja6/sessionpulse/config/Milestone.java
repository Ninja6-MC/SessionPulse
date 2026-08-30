package com.ninja6.sessionpulse.config;

import org.bukkit.Sound;

/**
 * One alert that fires exactly once, when a player's counted window first reaches
 * {@link #minute()}.
 *
 * <p>A dumb carrier on purpose. Every rule about what a milestone may contain lives in
 * {@link PluginConfig}, because that is the only place that can turn a violation into a
 * warning naming the entry rather than into an exception that takes plugin startup down
 * with it. Nothing here throws and nothing here corrects.
 *
 * <p>Measured against the <b>counted window</b>, not the current session - see the
 * milestone decision recorded on the tracking issue. A player who quits and rejoins does
 * not get their alerts again.
 *
 * @param minute    minutes of counted window at which this fires. Always at least 1 by the
 *                  time an instance reaches anyone: an entry with a smaller minute is
 *                  skipped at load
 * @param message   chat message in MiniMessage, or {@code null} to send none
 * @param actionBar action-bar text in MiniMessage, or {@code null} to send none
 * @param title     title text in MiniMessage, or {@code null} to send none
 * @param subtitle  subtitle text in MiniMessage, or {@code null}. A subtitle with no title
 *                  is shown with an empty title rather than dropped, which is what the
 *                  vanilla title packet does anyway
 * @param sound     the sound played alongside, or {@code null} for silence. Resolved
 *                  against the running server's {@link Sound} enum at load, so a sound
 *                  added in a later Minecraft version works on a server that has it even
 *                  though this plugin compiles against 1.20.4
 */
public record Milestone(int minute,
                        String message,
                        String actionBar,
                        String title,
                        String subtitle,
                        Sound sound) {

    /**
     * Whether this milestone would put anything at all on a player's screen.
     *
     * <p>A milestone with a sound and no text is legitimate; one with neither is a timer
     * that fires into nothing, and {@link PluginConfig} skips it rather than scheduling it.
     *
     * @return {@code true} when at least one of the five payload fields is set
     */
    public boolean hasAnythingToShow() {
        return message != null || actionBar != null || title != null
                || subtitle != null || sound != null;
    }
}
