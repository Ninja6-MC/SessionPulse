package com.ninja6.sessionpulse.config;

/**
 * The repeating reminder that runs after the one-time milestones have all fired.
 *
 * <p>Off by default. It is the marathon safety net, not the ordinary path: a server that
 * wants to say something at 60 and 120 minutes uses milestones, and a server that wants to
 * keep saying something for as long as somebody is online uses this.
 *
 * @param enabled      never true unless the operator set it - do not read a default here
 * @param afterMinutes counted-window minutes before the first overtime reminder. Clamped
 *                     to {@code 1-10080}
 * @param everyMinutes minutes between reminders after that. Clamped to {@code 1-1440};
 *                     the floor of 1 is not cosmetic, a zero would schedule a reminder
 *                     every tick
 * @param message      MiniMessage sent each time, never {@code null} - an unusable one
 *                     falls back to the shipped default and is named in the log
 */
public record OvertimePolicy(boolean enabled,
                             int afterMinutes,
                             int everyMinutes,
                             String message) {
}
