package com.ninja6.sessionpulse.storage;

import com.ninja6.sessionpulse.config.PluginConfig;
import com.ninja6.sessionpulse.platform.Scheduler;
import com.ninja6.sessionpulse.session.SessionSnapshot;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * {@link DataStorage} over a single {@code data.yml} in the plugin's data folder.
 *
 * <h2>The file</h2>
 *
 * <pre>
 * schema-version: 1
 * players:
 *   &lt;uuid&gt;:
 *     name: Steve
 *     lifetime-seconds: 123456
 *     window-start: 1700000000000
 *     window-seconds: 3600
 *     last-seen: 1700003600000
 *     cooldown-expires: 0
 * </pre>
 *
 * <p>An absent numeric key reads as {@code 0}, and {@code last-seen: 0} means the player's
 * counted state was never recorded - never the epoch. Keys this build does not know are
 * kept: the loaded document is the one that is written back, and each writer sets only
 * the keys it owns.
 *
 * <h2>Threads</h2>
 *
 * <table>
 *   <caption>Where each operation runs</caption>
 *   <tr><th>Operation</th><th>Thread</th><th>Disk</th></tr>
 *   <tr><td>{@link #loadFromDisk()}</td><td>main, from onEnable</td><td>read</td></tr>
 *   <tr><td>{@code load}, {@code cooldownExpiresMillis}, {@code records},
 *       {@code findByName}</td><td>any</td><td>none</td></tr>
 *   <tr><td>{@code save}, {@code setCooldown}</td><td>region, global or async</td>
 *       <td>none</td></tr>
 *   <tr><td>periodic flush</td><td>{@link Scheduler#async}</td><td>write</td></tr>
 *   <tr><td>quit and cooldown flush</td><td>{@link Scheduler#asyncOnce}</td>
 *       <td>write</td></tr>
 *   <tr><td>{@link #shutdown()}</td><td>main, from onDisable</td><td>write</td></tr>
 * </table>
 *
 * <p>"Never on the main thread" means the periodic, quit and cooldown flushes. The read at
 * enable and the write at disable are on the main thread deliberately: the first has to
 * finish before any player is seeded, and async work submitted during onDisable has no
 * guarantee of running at all.
 *
 * <h2>Locks</h2>
 *
 * <p>Two, and neither is ever held while touching {@link #records}:
 *
 * <ul>
 *   <li>The YAML guard protects the in-memory document. Writers take it inside
 *       {@code records.compute}, so the map and the document change in the same order for
 *       any one player; the flush takes it only to serialise. Region threads contend for
 *       this lock and nothing else, so a slow disk never stalls one.</li>
 *   <li>The disk lock orders whole flushes: serialise, then write. Without it two flushes
 *       can serialise in one order and reach the disk in the other, leaving the older file
 *       in place with nothing marked dirty to repair it.</li>
 * </ul>
 *
 * <p>Allowed nesting is map bin lock then YAML guard, and disk lock then YAML guard. A
 * third, small lock guards only the periodic task's handle; it is never held across I/O or
 * while another lock is taken, so a reload rescheduling the flush never waits on the disk.
 *
 * <h2>Accepted limitations</h2>
 *
 * <ul>
 *   <li>A system clock moved backwards shortens a cooldown already on record.</li>
 *   <li>A hard crash loses at most one flush interval of counted time, and leaves
 *       {@code last-seen} at most that stale.</li>
 *   <li>The cooldown flush is asynchronous, so a crash in the moment before it runs can
 *       lose that cooldown.</li>
 *   <li>A failed cooldown or quit flush is not re-queued. The change stays pending and is
 *       written by the next periodic flush, up to 60 minutes later.</li>
 *   <li>A periodic heartbeat racing a quit for the same player can store the heartbeat's
 *       copy, which is older by the width of the race. No newest-wins guard is possible:
 *       a window reset legitimately lowers {@code window-seconds}.</li>
 * </ul>
 */
public final class YamlDataStorage implements DataStorage {

    /** The layout this build writes, and the newest it will write over. */
    static final int SCHEMA_VERSION = 1;

    static final String SCHEMA_KEY = "schema-version";
    static final String PLAYERS = "players";
    static final String NAME = "name";
    static final String LIFETIME = "lifetime-seconds";
    static final String WINDOW_START = "window-start";
    static final String WINDOW_SECONDS = "window-seconds";
    static final String LAST_SEEN = "last-seen";
    static final String COOLDOWN = "cooldown-expires";

    private static final long TICKS_PER_MINUTE = 1_200L;

    private static final String MISSHAPEN = "has player entries that could not be read (see "
            + "the warnings above); the others were kept";

    /**
     * Puts serialised text on disk.
     *
     * <p>A seam for the tests, which need to hold a flush mid-write to prove the ordering.
     * Production uses {@link #writeAtomically}.
     */
    @FunctionalInterface
    interface DataFileWriter {
        void write(Path target, String text) throws IOException;
    }

    private final Path dataFile;
    private final Scheduler scheduler;
    private final Supplier<PluginConfig> config;
    private final Logger logger;
    private final DataFileWriter writer;

    private final Map<UUID, StoredPlayer> records = new ConcurrentHashMap<>();

    private final Object yamlLock = new Object();
    private final Object diskLock = new Object();
    private final Object taskLock = new Object();

    /** Guarded by {@link #yamlLock}. */
    private YamlConfiguration yaml = new YamlConfiguration();

    private final AtomicBoolean dirty = new AtomicBoolean();
    private final AtomicBoolean flushPending = new AtomicBoolean();

    private volatile boolean closed;
    private volatile boolean writable = true;
    private volatile String refusal;

    /** Guarded by {@link #taskLock}. */
    private Scheduler.Task flushTask;

    /** Guarded by {@link #taskLock}. Set before the final write, so no reschedule follows it. */
    private boolean stopped;

    /** Main thread only, during {@link #loadFromDisk()}. */
    private boolean setAsideThisLoad;
    private volatile Supplier<Map<UUID, SessionSnapshot>> online;

    /**
     * Creates the store. Nothing is read until {@link #loadFromDisk()}.
     *
     * <p>Takes a path, a scheduler, a configuration supplier and a logger rather than the
     * plugin, which is what lets every path through this class run under plain JUnit.
     *
     * @param dataFile  where {@code data.yml} lives
     * @param scheduler the scheduling seam, for the flushes
     * @param config    a supplier of the configuration in force, read per call
     * @param logger    where problems with the file are reported
     */
    public YamlDataStorage(Path dataFile, Scheduler scheduler, Supplier<PluginConfig> config,
                           Logger logger) {
        this(dataFile, scheduler, config, logger, YamlDataStorage::writeAtomically);
    }

    YamlDataStorage(Path dataFile, Scheduler scheduler, Supplier<PluginConfig> config,
                    Logger logger, DataFileWriter writer) {
        this.dataFile = dataFile;
        this.scheduler = scheduler;
        this.config = config;
        this.logger = logger;
        this.writer = writer;
    }

    // ------------------------------------------------------------------------------------
    // Lifecycle. On this class and not on DataStorage; see the note there.
    // ------------------------------------------------------------------------------------

    /**
     * Reads {@code data.yml} into memory. Main thread, from onEnable, before any player is
     * seeded.
     *
     * <p>Never throws, and never leaves a file it could not understand to be overwritten:
     *
     * <ul>
     *   <li>A missing file starts empty; the first flush creates it.</li>
     *   <li>A file that does not parse is copied, byte for byte, to the first free name of
     *       {@code data.yml.unreadable}, {@code data.yml.unreadable-1}, and so on, and
     *       storage starts empty. If the copy fails, writes are refused.</li>
     *   <li>A blank file is set aside the same way. This plugin never writes one, so a
     *       blank file is what a write lost to a power cut looks like, not an empty store.</li>
     *   <li>A file that parses but has the wrong shape - {@code players} that is not a
     *       section, an entry that is not a section, a key that is not a canonical UUID - is
     *       set aside too, and the records that could be read are kept. Without the copy,
     *       the first save would replace the misshapen node and the original would be
     *       gone.</li>
     *   <li>A file that cannot be read at all refuses writes for this session.</li>
     *   <li>A {@code schema-version} newer than this build is read as far as possible and
     *       never written over.</li>
     * </ul>
     *
     * <p>Uses the instance {@code loadFromString}, not the static
     * {@code YamlConfiguration.loadConfiguration}: the static form logs and returns an
     * empty document on a parse failure, and the next flush would then write that empty
     * document over a file that could have been recovered.
     */
    public void loadFromDisk() {
        setAsideThisLoad = false;
        YamlConfiguration loaded = Files.exists(dataFile) ? readDataFile() : missingFile();
        Map<UUID, StoredPlayer> read = parse(loaded);

        records.clear();
        records.putAll(read);
        synchronized (yamlLock) {
            yaml = loaded;
        }
        dirty.set(false);
        logger.info("Storage loaded: " + read.size() + " player record(s) from "
                + dataFile.getFileName() + ".");
    }

    /**
     * Starts the periodic flush.
     *
     * @param onlinePlayers the heartbeat: every online player's counted state, stamped now.
     *                      Stored on every periodic flush so that {@code last-seen} is at
     *                      most one interval old after a crash
     */
    public void startFlushing(Supplier<Map<UUID, SessionSnapshot>> onlinePlayers) {
        this.online = onlinePlayers;
        rescheduleFlush();
    }

    /**
     * Stops the periodic flush, writes everything synchronously on the calling thread, and
     * closes the store. Main thread, from onDisable.
     *
     * <p>The write waits for any flush already writing, so an async save that began before
     * the scheduler was cancelled cannot land after this one. Idempotent; once closed,
     * every writer and every flush is a no-op.
     */
    public void shutdown() {
        // Released before the write: a reschedule racing this call returns at once rather
        // than waiting on the disk.
        synchronized (taskLock) {
            stopped = true;
            if (flushTask != null) {
                flushTask.cancel();
                flushTask = null;
            }
        }
        synchronized (diskLock) {
            if (closed) {
                return;
            }
            try {
                if (writable) {
                    writeNow();
                } else {
                    logger.severe("Storage did not save " + dataFile + " at shutdown: "
                            + refusal);
                }
            } finally {
                closed = true;
            }
        }
    }

    // ------------------------------------------------------------------------------------
    // Readers. In-memory, lock-free, any thread.
    // ------------------------------------------------------------------------------------

    /**
     * {@inheritDoc}
     *
     * <p>The tracker calls this from inside its own map's {@code computeIfAbsent}, under a
     * bin lock. It must stay a plain read of a different map, with no I/O and no lock.
     */
    @Override
    public SessionSnapshot load(UUID uuid) {
        StoredPlayer record = records.get(uuid);
        return record == null ? SessionSnapshot.UNKNOWN : record.toSnapshot();
    }

    @Override
    public long cooldownExpiresMillis(UUID uuid) {
        StoredPlayer record = records.get(uuid);
        return record == null ? 0L : record.cooldownExpiresMillis();
    }

    @Override
    public Map<UUID, StoredPlayer> records() {
        return Map.copyOf(records);
    }

    @Override
    public UUID findByName(String name) {
        if (name == null || name.isEmpty()) {
            return null;
        }
        String wanted = name.toLowerCase(Locale.ROOT);
        UUID best = null;
        long bestSeen = Long.MIN_VALUE;
        for (Map.Entry<UUID, StoredPlayer> entry : records.entrySet()) {
            StoredPlayer record = entry.getValue();
            if (record.name() != null && record.name().toLowerCase(Locale.ROOT).equals(wanted)
                    && record.lastSeenMillis() > bestSeen) {
                best = entry.getKey();
                bestSeen = record.lastSeenMillis();
            }
        }
        return best;
    }

    // ------------------------------------------------------------------------------------
    // Writers. In-memory only; the flushes own the disk.
    // ------------------------------------------------------------------------------------

    /**
     * {@inheritDoc}
     *
     * <p>Owns the counted-state keys and {@code last-seen}; never reads or writes the
     * cooldown of a record that already exists. The read-modify-write is one
     * {@code compute}, so a cooldown written from another thread between the read and the
     * write cannot be lost.
     */
    @Override
    public void save(UUID uuid, SessionSnapshot snapshot) {
        if (closed) {
            return;
        }
        records.compute(uuid, (key, previous) -> {
            StoredPlayer next = previous == null
                    ? StoredPlayer.fromSession(snapshot)
                    : previous.withSession(snapshot);
            synchronized (yamlLock) {
                String path = PLAYERS + "." + key + ".";
                if (snapshot.name() != null) {
                    yaml.set(path + NAME, snapshot.name());
                }
                yaml.set(path + LIFETIME, next.lifetimeSeconds());
                yaml.set(path + WINDOW_START, next.windowStartMillis());
                yaml.set(path + WINDOW_SECONDS, next.windowSeconds());
                yaml.set(path + LAST_SEEN, next.lastSeenMillis());
                if (previous == null) {
                    // A new record gets every key, so the file always matches its layout.
                    yaml.set(path + COOLDOWN, 0L);
                }
            }
            return next;
        });
        dirty.set(true);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Owns {@code cooldown-expires}, and never reads or writes the counted state of a
     * record that already exists - the mirror image of {@link #save}.
     */
    @Override
    public void setCooldown(UUID uuid, String name, long expiresMillis) {
        if (closed) {
            return;
        }
        records.compute(uuid, (key, previous) -> {
            StoredPlayer next = previous == null
                    ? StoredPlayer.fromCooldown(name, expiresMillis)
                    : previous.withCooldown(name, expiresMillis);
            synchronized (yamlLock) {
                String path = PLAYERS + "." + key + ".";
                if (name != null) {
                    yaml.set(path + NAME, name);
                }
                if (previous == null) {
                    yaml.set(path + LIFETIME, 0L);
                    yaml.set(path + WINDOW_START, 0L);
                    yaml.set(path + WINDOW_SECONDS, 0L);
                    yaml.set(path + LAST_SEEN, 0L);
                }
                yaml.set(path + COOLDOWN, next.cooldownExpiresMillis());
            }
            return next;
        });
        dirty.set(true);
        // A cooldown that waits for the periodic flush is one a crash forgets, and the
        // player the plugin just removed would walk straight back in.
        flushAsync();
    }

    // ------------------------------------------------------------------------------------
    // Flushing.
    // ------------------------------------------------------------------------------------

    @Override
    public void rescheduleFlush() {
        synchronized (taskLock) {
            // Checked under the lock shutdown sets it under, so a reload racing a disable
            // cannot schedule a task after shutdown has cancelled the last one, and two
            // reloads cannot leak a handle between them.
            if (stopped || closed) {
                return;
            }
            if (flushTask != null) {
                flushTask.cancel();
            }
            long ticks = config.get().flushIntervalMinutes() * TICKS_PER_MINUTE;
            flushTask = scheduler.async(this::periodicFlush, ticks, ticks);
        }
    }

    @Override
    public void flushAsync() {
        if (closed) {
            return;
        }
        if (flushPending.compareAndSet(false, true)) {
            try {
                scheduler.asyncOnce(this::forcedFlush);
            } catch (RuntimeException e) {
                // Left set, the flag would swallow every later request for the whole session.
                flushPending.set(false);
                logger.log(Level.SEVERE, "Storage could not schedule a flush of " + dataFile
                        + "; the change is written by the next periodic flush.", e);
            }
        }
    }

    private void forcedFlush() {
        // Cleared BEFORE the write, not after. A change that lands while this one is
        // writing then queues a flush of its own; clearing afterwards would fold that
        // request into a write that had already serialised without it.
        flushPending.set(false);
        try {
            writeIfDirty();
        } catch (Throwable t) {
            logger.log(Level.SEVERE, "Storage flush of " + dataFile + " failed.", t);
        }
    }

    private void periodicFlush() {
        // Two blocks, so a heartbeat that throws still lets pending changes reach the disk.
        Supplier<Map<UUID, SessionSnapshot>> heartbeat = online;
        if (heartbeat != null) {
            try {
                heartbeat.get().forEach(this::save);
            } catch (Throwable t) {
                logger.log(Level.SEVERE, "Storage could not collect online players for the "
                        + "periodic flush of " + dataFile + "; writing what is pending.", t);
            }
        }
        try {
            writeIfDirty();
        } catch (Throwable t) {
            logger.log(Level.SEVERE, "Storage flush of " + dataFile + " failed.", t);
        }
    }

    private void writeIfDirty() {
        if (dirty.get()) {
            writeNow();
        }
    }

    /**
     * Serialises the document and writes it, on the calling thread.
     *
     * <p>Package-private, and deliberately not on {@link DataStorage}. The dirty flag is
     * cleared inside the YAML guard: writers set it after releasing the guard, so a change
     * is either in the serialised text or marks the store dirty again, never neither.
     */
    void writeNow() {
        synchronized (diskLock) {
            if (closed || !writable) {
                return;
            }
            String text;
            synchronized (yamlLock) {
                yaml.set(SCHEMA_KEY, SCHEMA_VERSION);
                text = yaml.saveToString();
                dirty.set(false);
            }
            try {
                writer.write(dataFile, text);
            } catch (IOException | RuntimeException e) {
                dirty.set(true);
                logger.log(Level.SEVERE, "Failed to save " + dataFile
                        + "; the changes are kept and retried at the next flush.", e);
            }
        }
    }

    /**
     * Writes to a sibling temporary file, forces it to the device, and moves it over the
     * target, so neither a crash nor a power cut mid-write leaves a truncated file.
     *
     * <p>The move alone only covers a dead JVM. On a filesystem that can commit the rename
     * before the data blocks, power lost between the two leaves a zero-length file under
     * the real name - which is why the temporary file is forced before the move, and why a
     * blank file on load is set aside rather than read as an empty store.
     */
    static void writeAtomically(Path target, String text) throws IOException {
        Path parent = target.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
        try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
            ByteBuffer bytes = StandardCharsets.UTF_8.encode(text);
            while (bytes.hasRemaining()) {
                channel.write(bytes);
            }
            channel.force(true);
        }
        try {
            Files.move(temporary, target,
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        }
        if (parent != null) {
            // Makes the rename itself durable on POSIX. Best effort: Windows cannot open a
            // directory as a channel, and the data is already forced either way.
            try (FileChannel directory = FileChannel.open(parent, StandardOpenOption.READ)) {
                directory.force(true);
            } catch (IOException e) {
                // Nothing further to do; see above.
            }
        }
    }

    // ------------------------------------------------------------------------------------
    // Reading the file.
    // ------------------------------------------------------------------------------------

    private YamlConfiguration missingFile() {
        logger.info("Storage: " + dataFile + " does not exist yet; the first flush creates it.");
        return new YamlConfiguration();
    }

    private YamlConfiguration readDataFile() {
        String text;
        try {
            text = Files.readString(dataFile, StandardCharsets.UTF_8);
        } catch (CharacterCodingException e) {
            // An IOException too, so it is caught first: it means the bytes are not text,
            // which is a parse failure, not a read failure.
            setAside("could not be decoded as UTF-8; storage starts with no records", e);
            return new YamlConfiguration();
        } catch (IOException e) {
            refuseWrites("it could not be read, so it is left untouched and changes are kept "
                    + "in memory only.", e);
            return new YamlConfiguration();
        }

        if (text.isBlank()) {
            // Never written by this plugin, so it is damage, not an empty store.
            setAside("is blank, which this plugin never writes; storage starts with no records",
                    null);
            return new YamlConfiguration();
        }
        YamlConfiguration parsed = new YamlConfiguration();
        try {
            parsed.loadFromString(text);
        } catch (InvalidConfigurationException e) {
            // Includes a root that is not a map at all.
            setAside("could not be parsed; storage starts with no records", e);
            return new YamlConfiguration();
        }
        return parsed;
    }

    /**
     * Copies a file this build could not fully understand aside, so the next flush cannot
     * destroy it. At most once per load: one copy of the original is enough.
     *
     * @param problem what is wrong, phrased to follow the file name
     * @param cause   the parse failure, or {@code null}
     */
    private void setAside(String problem, Exception cause) {
        if (setAsideThisLoad) {
            return;
        }
        setAsideThisLoad = true;
        String base = dataFile.getFileName() + ".unreadable";
        Path aside = dataFile.resolveSibling(base);
        for (int n = 1; Files.exists(aside); n++) {
            aside = dataFile.resolveSibling(base + "-" + n);
        }
        try {
            Files.copy(dataFile, aside);
        } catch (IOException e) {
            if (cause != null) {
                e.addSuppressed(cause);
            }
            refuseWrites("it " + problem + ", and it could not be copied aside to " + aside
                    + ", so it is left untouched and changes are kept in memory only.", e);
            return;
        }
        logger.log(Level.SEVERE, "Storage: " + dataFile + " " + problem + ". It was copied, "
                + "unchanged, to " + aside + " before anything could overwrite it.", cause);
    }

    private void refuseWrites(String reason, Throwable cause) {
        writable = false;
        refusal = reason;
        logger.log(Level.SEVERE, "Storage will not write " + dataFile + " this session: "
                + reason, cause);
    }

    private Map<UUID, StoredPlayer> parse(YamlConfiguration loaded) {
        Map<UUID, StoredPlayer> read = new HashMap<>();
        if (loaded.getKeys(false).isEmpty()) {
            return read;
        }
        checkSchemaVersion(loaded);

        if (!loaded.contains(PLAYERS)) {
            return read;
        }
        ConfigurationSection players = loaded.getConfigurationSection(PLAYERS);
        if (players == null) {
            logger.warning("Storage: " + PLAYERS + " in " + dataFile
                    + " is not a section; no records were read from it.");
            setAside("has a " + PLAYERS + " entry that is not a section; no records were read",
                    null);
            return read;
        }
        for (String key : players.getKeys(false)) {
            UUID uuid = parseUuid(key);
            if (uuid == null) {
                logger.warning("Storage: skipping " + PLAYERS + "." + key + " in " + dataFile
                        + ": the key is not a UUID.");
                setAside(MISSHAPEN, null);
                continue;
            }
            ConfigurationSection section = players.getConfigurationSection(key);
            if (section == null) {
                logger.warning("Storage: skipping " + PLAYERS + "." + key + " in " + dataFile
                        + ": it is not a section.");
                setAside(MISSHAPEN, null);
                continue;
            }
            read.put(uuid, new StoredPlayer(
                    section.getString(NAME, null),
                    readLong(uuid, section, LIFETIME),
                    readLong(uuid, section, WINDOW_START),
                    readLong(uuid, section, WINDOW_SECONDS),
                    readLong(uuid, section, LAST_SEEN),
                    readLong(uuid, section, COOLDOWN)));
        }
        return read;
    }

    private void checkSchemaVersion(YamlConfiguration loaded) {
        Object value = loaded.get(SCHEMA_KEY);
        long version = value instanceof Number number ? number.longValue() : 0L;
        if (version > SCHEMA_VERSION) {
            refuseWrites(SCHEMA_KEY + " is " + version + ", newer than this build's "
                    + SCHEMA_VERSION + ". It is read as far as possible and never written "
                    + "over, so the newer data survives.", null);
        } else if (version < 1L) {
            // Every build stamps the file, so there is no older layout this could be. A hand
            // edit, a half-written file or a mangled root all arrive here; say so, and read
            // what is there.
            logger.warning("Storage: " + dataFile + " has no valid " + SCHEMA_KEY
                    + " (found " + value + "); reading it as version " + SCHEMA_VERSION + ".");
        }
    }

    private long readLong(UUID uuid, ConfigurationSection section, String key) {
        Object value = section.get(key);
        if (value == null) {
            return 0L;
        }
        // instanceof Number, never isLong: YAML hands a small value back as an Integer, and
        // isLong is an instanceof Long check that would reject every one of them.
        long read;
        if (value instanceof Number number) {
            read = number.longValue();
        } else if (value instanceof String text && isWholeNumber(text.trim())) {
            // A hand edit that quoted a number. Read it: each key is rewritten only by its
            // own writer, so a cooldown read as 0 here would never be repaired or enforced.
            read = Long.parseLong(text.trim());
        } else {
            logger.warning("Storage: " + PLAYERS + "." + uuid + "." + key + " in " + dataFile
                    + " is not a number (" + value + "); reading it as 0.");
            return 0L;
        }
        if (read < 0L) {
            logger.warning("Storage: " + PLAYERS + "." + uuid + "." + key + " in " + dataFile
                    + " is negative (" + read + "); reading it as 0.");
            return 0L;
        }
        return read;
    }

    private static boolean isWholeNumber(String text) {
        try {
            Long.parseLong(text);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /**
     * One numeric key for a player, read from the in-memory document under the YAML guard.
     *
     * <p>For the concurrency test, which has to see the document as well as the map: a
     * writer that sets another writer's key from a stale record leaves the map right and
     * the file wrong.
     */
    long documentLong(UUID uuid, String key) {
        synchronized (yamlLock) {
            return yaml.getLong(PLAYERS + "." + uuid + "." + key);
        }
    }

    /**
     * A UUID in canonical form only, so a record cannot be read under one key and written
     * back under another.
     */
    private static UUID parseUuid(String key) {
        try {
            UUID uuid = UUID.fromString(key);
            return uuid.toString().equals(key) ? uuid : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
