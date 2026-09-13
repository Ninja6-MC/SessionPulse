package com.ninja6.sessionpulse.storage;

import com.ninja6.sessionpulse.config.PluginConfig;
import com.ninja6.sessionpulse.platform.RecordingScheduler;
import com.ninja6.sessionpulse.session.SessionClock;
import java.io.IOException;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * What every storage test needs, without a server.
 *
 * <p>The configuration and clock fixtures in the other packages are package-private there,
 * so the few lines they would have saved are repeated here rather than widened.
 */
final class StorageFixture {

    /** 2023-11-14T22:13:20Z. An arbitrary but realistic calendar value. */
    static final long WALL = 1_700_000_000_000L;

    private StorageFixture() {
    }

    /** Parses a YAML document exactly as written. */
    static PluginConfig config(String yaml) {
        return new PluginConfig(YamlConfiguration.loadConfiguration(new StringReader(yaml)));
    }

    /** Everything at its default. */
    static PluginConfig defaults() {
        return config("");
    }

    /** A store over {@code file}, not yet loaded. */
    static YamlDataStorage store(Path file, RecordingScheduler scheduler, Log log) {
        return new YamlDataStorage(file, scheduler, StorageFixture::defaults, log.logger);
    }

    /** A fresh store over {@code file}, loaded: the stand-in for the next server start. */
    static YamlDataStorage restart(Path file) {
        YamlDataStorage store = store(file, new RecordingScheduler(), new Log());
        store.loadFromDisk();
        return store;
    }

    static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static void write(Path file, String text) {
        try {
            Files.writeString(file, text, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The file as a document, parsed independently of the store. */
    static YamlConfiguration parse(Path file) {
        return YamlConfiguration.loadConfiguration(new StringReader(read(file)));
    }

    /** A logger that keeps what it is given and prints nothing. */
    static final class Log {

        final Logger logger = Logger.getAnonymousLogger();
        final List<LogRecord> records = Collections.synchronizedList(new ArrayList<>());

        Log() {
            logger.setUseParentHandlers(false);
            logger.setLevel(Level.ALL);
            logger.addHandler(new Handler() {
                @Override
                public void publish(LogRecord record) {
                    records.add(record);
                }

                @Override
                public void flush() {
                }

                @Override
                public void close() {
                }
            });
        }

        /** The messages logged at exactly {@code level}. */
        List<String> at(Level level) {
            List<String> messages = new ArrayList<>();
            synchronized (records) {
                for (LogRecord record : records) {
                    if (record.getLevel() == level) {
                        messages.add(record.getMessage());
                    }
                }
            }
            return messages;
        }

        /** Whether one message at {@code level} contains every fragment. */
        boolean has(Level level, String... fragments) {
            for (String message : at(level)) {
                boolean all = true;
                for (String fragment : fragments) {
                    all &= message.contains(fragment);
                }
                if (all) {
                    return true;
                }
            }
            return false;
        }
    }

    /** A clock the test moves by hand. Both readings move together unless told otherwise. */
    static final class Clock implements SessionClock {

        long nanos = 7_000_000_000L;
        long wallMillis = WALL;

        @Override
        public long nanoTime() {
            return nanos;
        }

        @Override
        public long wallMillis() {
            return wallMillis;
        }

        void advance(long millis) {
            nanos += millis * 1_000_000L;
            wallMillis += millis;
        }

        void advanceWallOnly(long millis) {
            wallMillis += millis;
        }
    }
}
