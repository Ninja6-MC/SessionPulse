package com.ninja6.sessionpulse.enforce;

import com.ninja6.sessionpulse.config.EnforcementPolicy;
import com.ninja6.sessionpulse.config.PluginConfig;
import com.ninja6.sessionpulse.notify.Notifier;
import com.ninja6.sessionpulse.notify.Placeholders;
import com.ninja6.sessionpulse.platform.Scheduler;
import com.ninja6.sessionpulse.reminder.ReminderObserver;
import com.ninja6.sessionpulse.session.PlayerSession;
import com.ninja6.sessionpulse.session.SessionClock;
import com.ninja6.sessionpulse.session.SessionObserver;
import com.ninja6.sessionpulse.session.SessionTracker;
import com.ninja6.sessionpulse.storage.DataStorage;
import org.bukkit.entity.Player;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * Disconnects a player whose counted window reaches {@code enforcement.at-minutes}, and holds
 * them out for {@code enforcement.cooldown-minutes}.
 *
 * <p>Off unless the operator turned it on. With {@code enforcement.enabled: false} the claim
 * on the tick refuses before it touches anything, so the shipped file kicks nobody and
 * schedules nothing.
 *
 * <h2>Claim on the tick, act on the region</h2>
 *
 * <p>The claim is {@link SessionTracker#claimEnforcement}, made on the session tick where the
 * window is measured, for the same reason {@link ReminderObserver} claims there: two ticks
 * both seeing "not yet" before either region task ran would disconnect twice and write two
 * cooldowns. So reaching the limit costs one entity task per connection, and every other tick
 * schedules nothing.
 *
 * <p>Everything with an effect runs inside {@link Scheduler#entity} for that player, in a
 * fixed order, each step load-bearing:
 *
 * <ol>
 *   <li><b>Exemption first</b>, before any side effect. {@code sessionpulse.exempt} is player
 *       data and is never read on the tick. An exempt player's claim is consumed for this
 *       connection, as a reminder's is; losing the permission takes effect on the next one.</li>
 *   <li><b>The configuration again.</b> A reload between the claim and this task may have
 *       switched enforcement off, or raised {@code at-minutes} past the window that was
 *       claimed. Either way the claim is given back rather than kept, so a later tick that
 *       really is over the limit in force can still claim it.</li>
 *   <li><b>Still the live session.</b> A quit or a window reset in between replaced it; the
 *       player is leaving or starting over, and nothing is written for them.</li>
 *   <li><b>The cooldown</b>, before anything touches the window. Storage keeps the two apart
 *       - a save never touches the cooldown and a cooldown never touches the window - so the
 *       order in memory is the only thing deciding what a flush can catch. With the cooldown
 *       first, any flush that carries the zeroed window carries the expiry too. The other way
 *       round, a periodic flush between the two could store a window of zero with no
 *       cooldown, and a crash then would hand back a fresh allowance with no break.</li>
 *   <li><b>The window is reset and checkpointed.</b> The enforced break is what ends the
 *       counted window. Without it the cooldown would end the break but not the over-limit
 *       state: a rejoin inside {@code window-reset-hours} carries the window, the first tick
 *       claims again, and the player is back out with a fresh cooldown - a boot loop with a
 *       thirty-minute period. Lifetime is carried across. If the session vanished between
 *       the check and the reset, the cooldown already written stands against the old window:
 *       the worst case is one extra kick once it lapses, never a missed break.</li>
 *   <li><b>A flush is requested</b> explicitly. The cooldown's own forced flush may already
 *       have run on another thread before the checkpoint, and the zeroed window must not wait
 *       for the periodic one: a crash in between would restore the old window, and the player
 *       would be kicked once more after the cooldown.</li>
 *   <li><b>The disconnect</b>, last. The cooldown is already in memory, so a reconnect the
 *       kick races can never reach the login gate ahead of it.</li>
 * </ol>
 *
 * <p>The kick message is rendered through {@link Notifier#legacy}, because
 * {@code Player#kickPlayer} is String-only on spigot-api. Its {@code <hours>} and
 * {@code <minutes>} are the window at the claim, not the zero it reads once reset.
 *
 * <h2>Known limits</h2>
 *
 * <ul>
 *   <li>A plugin that cancels the {@code PlayerKickEvent} leaves the player online with a
 *       reset window and a cooldown on record that does nothing until they next connect.
 *       This plugin does not fight another plugin's decision to keep someone.</li>
 *   <li>Paper and Folia deliver the quit some time after {@code kickPlayer} returns. A tick in
 *       between sees the fresh session at a window of zero minutes, which reaches no
 *       milestone, no overtime point and no threshold, so it claims nothing.</li>
 *   <li>The checkpoint shares a pre-existing race with any window reset: a quit landing
 *       between the {@code isLive} test and the save can overwrite the quit's final figure
 *       with the reset one. Not introduced here, and not fixed here.</li>
 *   <li>A player gone before the region task runs is not disconnected and has no cooldown
 *       written. The claim dies with their session; the next connection is judged afresh on
 *       its first tick. Every failure errs towards later, never twice.</li>
 * </ul>
 */
public final class EnforcementService implements SessionObserver {

    private static final long MILLIS_PER_MINUTE = 60_000L;

    private final SessionTracker tracker;
    private final Scheduler scheduler;
    private final Notifier notifier;
    private final DataStorage storage;
    private final SessionClock clock;
    private final Supplier<PluginConfig> config;

    /**
     * Creates the service.
     *
     * @param tracker   decides when the threshold is reached, and resets the window after it
     * @param scheduler where the disconnect is handed to the player's region
     * @param notifier  renders the kick message
     * @param storage   where the cooldown is recorded and flushed
     * @param clock     the calendar reading a cooldown's expiry is measured from
     * @param config    a supplier of the configuration in force, read per call. Never the
     *                  object itself
     */
    public EnforcementService(SessionTracker tracker, Scheduler scheduler, Notifier notifier,
                              DataStorage storage, SessionClock clock,
                              Supplier<PluginConfig> config) {
        this.tracker = tracker;
        this.scheduler = scheduler;
        this.notifier = notifier;
        this.storage = storage;
        this.clock = clock;
        this.config = config;
    }

    @Override
    public void afterAccrual(Player player, PlayerSession session) {
        if (!tracker.claimEnforcement(session, session.windowMinutes())) {
            return;
        }
        // Captured here, on the tick, so the message states the window that was claimed and
        // not the zero the reset leaves behind.
        UUID uuid = session.uuid();
        String name = session.name();
        long windowSeconds = session.windowSeconds();

        // An explicit no-op for a retired player: they left on their own, nothing is written.
        scheduler.entity(player, () -> enforce(player, session, uuid, name, windowSeconds),
                () -> {});
    }

    /** Region thread. The order is documented on the class, and each step depends on it. */
    private void enforce(Player player, PlayerSession session, UUID uuid, String name,
                         long windowSeconds) {
        if (player.hasPermission(ReminderObserver.EXEMPT_PERMISSION)) {
            return;
        }
        PluginConfig current = config.get();
        // The claim was judged against the file in force on the tick; this is the one in
        // force now. Truncated minutes, the boundary the claim itself used.
        if (current == null || !current.enforcement().enabled()
                || windowSeconds / 60L < current.enforcement().atMinutes()) {
            session.releaseEnforcement();
            return;
        }
        if (tracker.session(uuid) != session) {
            return;
        }
        EnforcementPolicy policy = current.enforcement();
        long cooldownMinutes = policy.cooldownMinutes();
        storage.setCooldown(uuid, name,
                clock.wallMillis() + cooldownMinutes * MILLIS_PER_MINUTE);

        PlayerSession fresh = tracker.resetWindow(uuid);
        if (fresh == null) {
            return;
        }
        tracker.checkpoint(fresh);
        storage.flushAsync();

        player.kickPlayer(notifier.legacy(policy.kickMessage(), Placeholders.none()
                .player(name)
                .hours(windowSeconds)
                .minutes(windowSeconds)
                .cooldown(cooldownMinutes * 60L)));
    }
}
