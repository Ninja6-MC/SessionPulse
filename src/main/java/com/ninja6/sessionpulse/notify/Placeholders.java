package com.ninja6.sessionpulse.notify;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * The values a configured message may refer to by tag: {@code <player>}, {@code <hours>},
 * {@code <minutes>} and {@code <cooldown>}; and, for {@code /spulse}'s own lines, {@code <rank>}
 * and {@code <lifetime>}.
 *
 * <p>Immutable. Every wither returns a new instance and leaves the receiver alone, so
 * {@link #none()} can be shared and one player's name can never end up in another player's
 * message.
 *
 * <p>Names no Adventure type, not even package-private. Turning these values into tag
 * resolvers is {@link Notifier}'s job, and it always inserts them as unparsed text: a value
 * is never read as markup. There is deliberately no public {@code with(key, value)}; an
 * arbitrary key is how a later caller would start passing markup through.
 *
 * <p>A tag with no value set renders as the characters written - {@code Hi <player>!} stays
 * exactly that. MiniMessage leaves an unknown tag alone at 4.26.1, and a test pins it.
 */
public final class Placeholders {

    private static final Placeholders NONE = new Placeholders(Map.of());

    private final Map<String, String> values;

    private Placeholders(Map<String, String> values) {
        this.values = values;
    }

    /** No values at all. Every tag in the message renders literally. */
    public static Placeholders none() {
        return NONE;
    }

    /**
     * {@code <player>}: the name, verbatim.
     *
     * @param name the player's name; anything in it that looks like a tag stays text
     * @return a copy with the value set
     */
    public Placeholders player(String name) {
        return with("player", name);
    }

    /**
     * {@code <hours>}: counted hours to one decimal place, truncated.
     *
     * <p>Integer arithmetic rather than {@code String.format}, which would write
     * {@code 1,5} on a server whose default locale uses a decimal comma. Truncated like
     * {@code PlayerSession#windowMinutes}, so the value never claims time not yet played.
     *
     * @param countedSeconds seconds of counted window; a negative is treated as zero
     * @return a copy with the value set
     */
    public Placeholders hours(long countedSeconds) {
        long tenths = Math.max(0, countedSeconds) / 360;
        return with("hours", tenths / 10 + "." + tenths % 10);
    }

    /**
     * {@code <minutes>}: counted minutes, whole and truncated.
     *
     * @param countedSeconds seconds of counted window; a negative is treated as zero
     * @return a copy with the value set
     */
    public Placeholders minutes(long countedSeconds) {
        return with("minutes", Long.toString(Math.max(0, countedSeconds) / 60));
    }

    /**
     * {@code <cooldown>}: whole minutes until the player may rejoin, rounded up.
     *
     * <p>Up, not down: a player told "0 minutes" who reconnects at once is refused again,
     * and a player told "1 minute" with one second left is merely early.
     *
     * @param remainingSeconds seconds of cooldown left; a negative is treated as zero
     * @return a copy with the value set
     */
    public Placeholders cooldown(long remainingSeconds) {
        return with("cooldown", Long.toString((Math.max(0, remainingSeconds) + 59) / 60));
    }

    /**
     * {@code <rank>}: a leaderboard position, as a plain integer.
     *
     * @param rank the position, counted from one
     * @return a copy with the value set
     */
    public Placeholders rank(int rank) {
        return with("rank", Integer.toString(rank));
    }

    /**
     * {@code <lifetime>}: lifetime hours to one decimal place, truncated.
     *
     * <p>The same arithmetic as {@link #hours}, for the same two reasons, under its own tag so
     * one message can show the counted window and the lifetime total side by side.
     *
     * @param lifetimeSeconds seconds of lifetime playtime; a negative is treated as zero
     * @return a copy with the value set
     */
    public Placeholders lifetime(long lifetimeSeconds) {
        long tenths = Math.max(0, lifetimeSeconds) / 360;
        return with("lifetime", tenths / 10 + "." + tenths % 10);
    }

    /** Every value set, in the order it was set. Unmodifiable. */
    Map<String, String> values() {
        return values;
    }

    /** Rejects {@code null} here, where the caller is, rather than at send time on a region thread. */
    private Placeholders with(String key, String value) {
        Objects.requireNonNull(value, key);
        Map<String, String> copy = new LinkedHashMap<>(values);
        copy.put(key, value);
        return new Placeholders(Collections.unmodifiableMap(copy));
    }
}
