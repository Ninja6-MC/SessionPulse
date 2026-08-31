package com.ninja6.sessionpulse.session;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A {@link SessionStore} backed by a map, which also remembers the order it was written in.
 *
 * <p>{@link #preload} is how a test says "this player has been here before": it writes a
 * record without recording it as a save, so the saves list stays a record of what the code
 * under test actually did.
 */
final class RecordingSessionStore implements SessionStore {

    private final Map<UUID, SessionSnapshot> records = new LinkedHashMap<>();

    /** Every save, in order, as the code under test made them. */
    final List<SessionSnapshot> saves = new ArrayList<>();

    @Override
    public SessionSnapshot load(UUID uuid) {
        return records.getOrDefault(uuid, SessionSnapshot.UNKNOWN);
    }

    @Override
    public void save(UUID uuid, SessionSnapshot snapshot) {
        records.put(uuid, snapshot);
        saves.add(snapshot);
    }

    /** Seeds a record as if a previous run of the plugin had written it. */
    void preload(UUID uuid, SessionSnapshot snapshot) {
        records.put(uuid, snapshot);
    }

    /** What this store currently holds for a player, without going through load(). */
    SessionSnapshot stored(UUID uuid) {
        return records.getOrDefault(uuid, SessionSnapshot.UNKNOWN);
    }

    /** Writes a whole flush back, the way the flush task will. */
    void flush(Map<UUID, SessionSnapshot> all) {
        all.forEach(this::save);
    }
}
