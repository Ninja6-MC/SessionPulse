package com.ninja6.sessionpulse.afk;

import com.ninja6.sessionpulse.platform.RecordingScheduler;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.bukkit.entity.Player;

/**
 * Stands in for the EssentialsX plugin instance, with the method names the detector binds to
 * and nothing else.
 *
 * <p>{@code public}, with {@code public} nested types, because the detector reaches it through
 * {@link java.lang.reflect.Method#invoke}. A call to {@link #getUser} or {@link User#isAfk}
 * outside an entity task is counted in {@link #offRegionCalls} and throws: the detector swallows
 * whatever EssentialsX throws, so the counter is what a test asserts on.
 */
public class FakeEssentials {

    private final RecordingScheduler scheduler;
    private final Map<UUID, User> users = new HashMap<>();
    private final Settings settings = new Settings();

    public int offRegionCalls;
    public int getUserCalls;

    /** Thrown from {@link #getSettings} when set. */
    public Error settingsError;
    /** Thrown from {@link #getUser} when set. */
    public Throwable getUserThrows;
    /** Returned from {@link #getUser} instead of a user when set, as EssentialsX does while stopping. */
    public boolean nullUser;

    public FakeEssentials(RecordingScheduler scheduler, long autoAfk) {
        this.scheduler = scheduler;
        this.settings.autoAfk = autoAfk;
    }

    /** The settings object; {@code auto-afk} lives here. */
    public static class Settings {

        public long autoAfk;

        public long getAutoAfk() {
            return autoAfk;
        }
    }

    /** One player's EssentialsX user. */
    public class User {

        public boolean afk;
        /** Thrown from {@link #isAfk} when set. */
        public Throwable isAfkThrows;

        public boolean isAfk() {
            checkRegion();
            if (isAfkThrows != null) {
                sneakyThrow(isAfkThrows);
            }
            return afk;
        }
    }

    public Settings getSettings() {
        if (settingsError != null) {
            throw settingsError;
        }
        return settings;
    }

    public User getUser(Player player) {
        checkRegion();
        getUserCalls++;
        if (getUserThrows != null) {
            sneakyThrow(getUserThrows);
        }
        return nullUser ? null : user(player.getUniqueId());
    }

    /** Ordinary test access to a user, on no particular thread. */
    public User user(UUID uuid) {
        return users.computeIfAbsent(uuid, ignored -> new User());
    }

    private void checkRegion() {
        if (!scheduler.inEntity) {
            offRegionCalls++;
            throw new AssertionError("EssentialsX called off the player's region");
        }
    }

    @SuppressWarnings("unchecked")
    private static <T extends Throwable> void sneakyThrow(Throwable thrown) throws T {
        throw (T) thrown;
    }

    /** An EssentialsX with no {@code getUser(Player)}, as a renamed API would look. */
    public static class WithoutGetUser {

        public Settings getSettings() {
            Settings settings = new Settings();
            settings.autoAfk = 300L;
            return settings;
        }
    }
}
