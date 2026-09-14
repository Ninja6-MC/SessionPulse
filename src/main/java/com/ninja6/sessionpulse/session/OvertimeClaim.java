package com.ninja6.sessionpulse.session;

/**
 * One overtime reminder, claimed on the session tick and waiting to be delivered.
 *
 * <p>The message is captured from the configuration that made the claim, so a reload landing
 * between the tick and the player's region cannot pair one file's minute with another file's
 * text.
 *
 * @param minute  the counted-window minute the reminder fell due at
 * @param message the MiniMessage to send, never {@code null}
 */
public record OvertimeClaim(long minute, String message) {
}
