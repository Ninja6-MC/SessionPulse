package com.ninja6.sessionpulse.config;

import net.kyori.adventure.text.minimessage.MiniMessage;

/**
 * The one place that decides whether a configured string is usable MiniMessage.
 *
 * <h2>Why this is not simply a try/catch around the ordinary parser</h2>
 *
 * <p>Verified against adventure-text-minimessage 4.21.0, the version this build pins:
 * {@code MiniMessage.miniMessage()} does not throw for a merely unknown or malformed tag.
 * It catches its own {@code ParsingException} internally and emits the offending tag as
 * literal text, so {@code <bogustag>}, {@code <color:#zzzzzz>},
 * {@code <hover:not_an_event:x>} and a bare {@code <} all "parse" cleanly and render as
 * the characters the operator typed.
 *
 * <p>So a try/catch around the ordinary parser alone would be a validator that almost
 * never fails, which is worse than no validator: it makes the log say nothing while the
 * operator's message quietly renders as tag soup. Two classes of mistake do get reported,
 * and they are the two worth a warning:
 *
 * <ul>
 *   <li>a tag left open - reported by the <b>strict</b> parser
 *       ({@code All tags must be explicitly closed while in strict mode});</li>
 *   <li>tags closed out of order - {@code <red>a<blue>b</red></blue>} is rejected with
 *       {@code Unclosed tag encountered; blue is not closed, because red was closed
 *       first}.</li>
 * </ul>
 *
 * <p>In one sentence, and this is the sentence the shipped {@code config.yml} repeats:
 * close your tags, and close them in order - {@code <red><b>x</b></red>}, not
 * {@code <red><b>x</red></b>}. That is the definition of "unparseable" this plugin uses,
 * and the shipped {@code config.yml} is written to satisfy it.
 *
 * <p>Both parsers are held as static finals: they are immutable, thread-safe, and building
 * one per call would be a parse of the tag registry per configuration value.
 *
 * <p>Note for whoever bumps Adventure: the lenient call is made first, so if a later
 * version starts reporting more from the ordinary parser, {@link #problem} widens to catch
 * it for free.
 */
final class MessageCheck {

    private static final MiniMessage LENIENT = MiniMessage.miniMessage();
    private static final MiniMessage STRICT = MiniMessage.builder().strict(true).build();

    private MessageCheck() {
    }

    /**
     * Why this string cannot be used, or {@code null} when it can.
     *
     * <p>Catches {@link Throwable}, not {@code ParsingException}. A parser bug must cost
     * the operator one milestone, never the plugin's whole enable, and naming the concrete
     * exception type here would couple this class to Adventure internals that the
     * relocation already makes awkward to name.
     *
     * @param input the configured string, or {@code null}
     * @return a single line, already trimmed to one line, suitable for appending to a
     *         warning; {@code null} if the string is fine or absent
     */
    static String problem(String input) {
        if (input == null) {
            return null;
        }
        try {
            LENIENT.deserialize(input);
        } catch (Throwable t) {
            return firstLine(t);
        }
        try {
            STRICT.deserialize(input);
        } catch (Throwable t) {
            return firstLine(t);
        }
        return null;
    }

    /**
     * The first line of an exception's message, or its class name when it has none.
     *
     * <p>MiniMessage's parsing exceptions carry a three-line message with a caret diagram
     * under the offending tag. That diagram is genuinely useful and genuinely unreadable
     * once a logger has prefixed every line with a timestamp and a thread name, so only
     * the sentence survives.
     */
    private static String firstLine(Throwable t) {
        String message = t.getMessage();
        if (message == null || message.isBlank()) {
            return t.getClass().getSimpleName();
        }
        int newline = message.indexOf('\n');
        String line = newline < 0 ? message : message.substring(0, newline);
        return line.strip();
    }
}
