package com.ninja6.sessionpulse.session;

import java.time.Duration;

/**
 * A {@link SessionClock} the test drives by hand.
 *
 * <p>The two readings move independently on purpose. Real time advances both, an offline
 * gap advances only the calendar, and a stopped system clock advances only the monotonic
 * reading. Being able to move one without the other is what makes it possible to prove
 * that the window-reset decision reads the calendar and the accrual reads the monotonic
 * counter, rather than that the two merely happen to agree.
 *
 * <p>Both start at deliberately unequal, non-zero values: a clock starting at zero would
 * let a mix-up between the two pass unnoticed.
 */
final class TestClock implements SessionClock {

    /** An arbitrary monotonic origin. Nothing may depend on its value. */
    static final long START_NANOS = 4_000_000_000L;

    /** 2023-11-14T22:13:20Z. An arbitrary but realistic calendar origin. */
    static final long START_WALL_MILLIS = 1_700_000_000_000L;

    private long nanos = START_NANOS;
    private long wallMillis = START_WALL_MILLIS;

    @Override
    public long nanoTime() {
        return nanos;
    }

    @Override
    public long wallMillis() {
        return wallMillis;
    }

    /** Real time passing: both readings move. */
    void advance(Duration duration) {
        nanos += duration.toNanos();
        wallMillis += duration.toMillis();
    }

    /** An offline gap: calendar time passes, this JVM measures nothing. */
    void advanceWallOnly(Duration duration) {
        wallMillis += duration.toMillis();
    }

    /** A frozen system clock: the monotonic counter still advances. */
    void advanceNanosOnly(Duration duration) {
        nanos += duration.toNanos();
    }

    /** Moves the calendar to an arbitrary instant, forwards or backwards. */
    void setWallMillis(long millis) {
        this.wallMillis = millis;
    }
}
