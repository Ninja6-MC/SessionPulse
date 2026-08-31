package com.ninja6.sessionpulse.session;

/**
 * The plugin's only source of time.
 *
 * <p>Two clocks, and the distinction between them is the whole point of the interface.
 * {@link #nanoTime()} is monotonic: it measures elapsed time correctly across an operator
 * moving the system clock, and it is meaningless across a JVM restart.
 * {@link #wallMillis()} is calendar time: it survives a restart, and it is the only thing
 * that may ever be written to storage.
 *
 * <p><strong>Why one two-method interface and not two {@code LongSupplier} parameters.</strong>
 * Two suppliers are structurally identical, so wiring them the wrong way round compiles
 * clean and produces a plugin whose counted-window resets are driven by a nanosecond
 * counter that restarts at an arbitrary value on every boot. Nothing logs it; the only
 * symptom is "the window never resets", months later. A named interface makes the mistake
 * unrepresentable. Same argument {@code platform/Scheduler} makes for keeping the blanket
 * cancel off the seam.
 *
 * <p>{@link #system()} is the one place in {@code src/main/java} that calls
 * {@code System.nanoTime()} or {@code System.currentTimeMillis()}, and a source-scanning
 * test enforces that. Anything that reached past the seam would be untestable against an
 * injected clock while still looking injected.
 *
 * <p>Implementations are stateless and safe to call from any thread.
 */
public interface SessionClock {

    /**
     * Elapsed-time reading, in nanoseconds.
     *
     * <p>Monotonic, with no relationship to any calendar. Only ever subtracted from
     * another reading taken in the same JVM. Never persisted, never compared with a
     * {@link #wallMillis()} value.
     *
     * @return a nanosecond reading whose absolute value means nothing
     */
    long nanoTime();

    /**
     * Calendar time, in milliseconds since the epoch.
     *
     * <p>The only quantity this plugin persists, and the only one that can express "how
     * long was this player offline" - a question spanning two runs of the JVM, which no
     * monotonic reading can answer.
     *
     * @return epoch milliseconds, as the operating system reports them
     */
    long wallMillis();

    /**
     * The real clock.
     *
     * @return a clock reading the JVM's own time sources
     */
    static SessionClock system() {
        return new SessionClock() {
            @Override
            public long nanoTime() {
                return System.nanoTime();
            }

            @Override
            public long wallMillis() {
                return System.currentTimeMillis();
            }
        };
    }
}
