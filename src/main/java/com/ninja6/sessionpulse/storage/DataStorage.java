package com.ninja6.sessionpulse.storage;

import com.ninja6.sessionpulse.session.SessionSnapshot;
import com.ninja6.sessionpulse.session.SessionStore;

import java.util.Map;
import java.util.UUID;

/**
 * Persistent player state: the counted window the tracker needs, plus the cooldown
 * enforcement needs.
 *
 * <p>A {@link SessionStore}, so the tracker is wired to this and to nothing else, and the
 * two inherited methods keep their contract: {@link #load(UUID)} is an in-memory read and
 * {@link #save(UUID, SessionSnapshot)} marks the record dirty without touching the disk.
 *
 * <p><strong>There is deliberately no way to write the file through this interface.</strong>
 * No {@code save()}, and no load, start or shutdown either. SpiralGenesis puts a public
 * {@code save()} on its storage interface; this one departs from that on purpose. The
 * plugin hands this type out, so anything on it is reachable from every later issue, and a
 * {@code storage().save()} written on the main thread would stall the server on disk with
 * nothing to catch it. With the write off the interface, "the flush never runs on the
 * main thread" is a property of the compiler, not of review - the same move the scheduler
 * seam makes for its blanket cancel. The lifecycle lives on {@link YamlDataStorage},
 * which only the plugin class holds by concrete type.
 *
 * <p>A later SQLite implementation maps one row per player to the six fields of
 * {@link StoredPlayer}, with the schema version in a table of its own.
 */
public interface DataStorage extends SessionStore {

    /**
     * When a player's cooldown ends.
     *
     * <p>In-memory, like {@link #load(UUID)}, so the pre-login check can call it without
     * touching the disk.
     *
     * @param uuid the player
     * @return epoch milliseconds, or {@code 0} if there is no cooldown on record
     */
    long cooldownExpiresMillis(UUID uuid);

    /**
     * Records a cooldown and asks for it to reach the disk now.
     *
     * <p>Only the cooldown and the name are written; the counted state the tracker owns is
     * left exactly as it is, whichever thread wrote it last. Returns without waiting for
     * the disk: the write itself runs on an async one-shot.
     *
     * @param uuid          the player
     * @param name          their name, or {@code null} to keep the one on record
     * @param expiresMillis when the cooldown ends, in epoch milliseconds
     */
    void setCooldown(UUID uuid, String name, long expiresMillis);

    /**
     * Every record, for listing offline players.
     *
     * @return an immutable copy, never {@code null}
     */
    Map<UUID, StoredPlayer> records();

    /**
     * Resolves a name to a player storage has seen.
     *
     * <p>Case-insensitive. Names are not unique over time - a player can take a name
     * another player once had - so when more than one record matches, the one seen most
     * recently wins.
     *
     * @param name the name to look up
     * @return the player, or {@code null} if no record carries that name
     */
    UUID findByName(String name);

    /**
     * Cancels the periodic flush by its handle and schedules it again at the interval now
     * in force.
     *
     * <p>For {@code /spulse reload}. It never touches any other task.
     */
    void rescheduleFlush();

    /**
     * Asks for pending changes to be written soon, off the calling thread.
     *
     * <p>Safe from any thread. Requests made while one is already queued collapse into it.
     */
    void flushAsync();
}
