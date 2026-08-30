package com.ninja6.sessionpulse.config;

import java.util.Locale;

/**
 * How the session clock decides a player has stopped playing.
 *
 * <p>Named here rather than in the AFK issue because {@code tracking.afk.mode} is parsed
 * at load and a config class cannot validate a value it has no type for. The detection
 * itself lands with that issue; this enum is only the vocabulary the file may use.
 */
public enum AfkMode {

    /**
     * Use EssentialsX if it is installed, otherwise the built-in detector. The default,
     * and the only value that is correct on a server whose plugin list changes later:
     * ESSENTIALS on a server that then removes EssentialsX silently stops pausing anyone.
     */
    AUTO,

    /**
     * Require EssentialsX. If it is absent, nothing is ever treated as AFK - stated in
     * the log at startup rather than quietly falling back, because an operator who wrote
     * ESSENTIALS asked for one specific source of truth.
     */
    ESSENTIALS,

    /** Always use the built-in idle timer, even where EssentialsX is installed. */
    BUILT_IN,

    /**
     * Never pause the clock. A player who walks away accrues counted time exactly as if
     * they were playing, which is the right answer on a server that would rather not
     * guess, and the wrong one everywhere enforcement is on.
     */
    OFF;

    /**
     * Parses a configured name, falling back to {@code fallback} for anything
     * unrecognised rather than failing plugin startup over a typo. The caller reports the
     * correction.
     *
     * @param name     the configured value, or {@code null}
     * @param fallback the value to use when {@code name} is absent or unrecognised
     * @return the parsed mode, or {@code fallback} when the name is absent or unknown
     */
    public static AfkMode parse(String name, AfkMode fallback) {
        if (name == null) {
            return fallback;
        }
        try {
            return valueOf(name.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }
}
