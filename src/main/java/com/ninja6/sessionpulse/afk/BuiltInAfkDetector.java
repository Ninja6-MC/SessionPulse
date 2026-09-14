package com.ninja6.sessionpulse.afk;

import com.ninja6.sessionpulse.config.PluginConfig;
import com.ninja6.sessionpulse.session.SessionClock;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * The idle timer: a player is AFK once {@code tracking.afk.idle-seconds} have passed since
 * their last input.
 *
 * <h2>Threads</h2>
 *
 * <p>Written from event threads - the region thread for movement and clicks, the async chat
 * thread for chat - and read from the session tick. The only shared state is one
 * {@link ConcurrentHashMap} of monotonic readings, and every write is "now", so two writers
 * racing lose each other a few nanoseconds and nothing else. {@link #isAfk} reads the
 * player's UUID and the clock, and no other player data, so it is safe on Folia's global
 * region.
 *
 * <h2>Only a join creates an entry</h2>
 *
 * <p>{@link #seed} is the only {@code put}. {@link #markActive} only replaces an entry that
 * already exists, because async chat can be delivered after the quit that called
 * {@link #forget}; a {@code put} there would bring the departed player back into the map and
 * keep them there for the rest of the run, one entry per late chat line.
 *
 * <p>A player with no entry is never AFK. Treating a missing entry as "last active at zero"
 * would pause everybody the plugin had not yet seen.
 */
public final class BuiltInAfkDetector implements AfkDetector {

    private static final long NANOS_PER_SECOND = 1_000_000_000L;

    private final SessionClock clock;
    private final Supplier<PluginConfig> config;
    private final Map<UUID, Long> lastActivityNanos = new ConcurrentHashMap<>();

    /**
     * Creates the timer.
     *
     * @param clock  the plugin's clock; the same instance the tracker reads
     * @param config read on every check, so a reload reaches the idle threshold
     */
    public BuiltInAfkDetector(SessionClock clock, Supplier<PluginConfig> config) {
        this.clock = clock;
        this.config = config;
    }

    /**
     * Starts the timer for a player who has just joined, or who was online at enable.
     *
     * <p>Without it a joiner who never moves is never AFK rather than AFK after the idle
     * threshold.
     *
     * @param uuid the player
     */
    public void seed(UUID uuid) {
        lastActivityNanos.put(uuid, clock.nanoTime());
    }

    /**
     * Restarts the timer for a player who did something. A player with no entry is ignored.
     *
     * @param uuid the player
     */
    public void markActive(UUID uuid) {
        lastActivityNanos.replace(uuid, clock.nanoTime());
    }

    @Override
    public boolean isAfk(Player player) {
        Long last = lastActivityNanos.get(player.getUniqueId());
        if (last == null) {
            return false;
        }
        long idleNanos = config.get().afkIdleSeconds() * NANOS_PER_SECOND;
        return clock.nanoTime() - last >= idleNanos;
    }

    @Override
    public void forget(UUID uuid) {
        lastActivityNanos.remove(uuid);
    }

    /** How many players the timer holds. For tests that prove an entry does not leak. */
    int tracked() {
        return lastActivityNanos.size();
    }
}
