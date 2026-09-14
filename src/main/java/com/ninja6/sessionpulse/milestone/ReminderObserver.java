package com.ninja6.sessionpulse.milestone;

import com.ninja6.sessionpulse.config.Milestone;
import com.ninja6.sessionpulse.notify.Notifier;
import com.ninja6.sessionpulse.notify.Placeholders;
import com.ninja6.sessionpulse.platform.Scheduler;
import com.ninja6.sessionpulse.session.OvertimeClaim;
import com.ninja6.sessionpulse.session.PlayerSession;
import com.ninja6.sessionpulse.session.SessionObserver;
import com.ninja6.sessionpulse.session.SessionTracker;
import org.bukkit.entity.Player;

import java.util.List;

/**
 * Fires each configured milestone once per counted window, and the recurring overtime
 * reminder at each point on its series.
 *
 * <h2>Claim on the tick, deliver on the region</h2>
 *
 * <p>The claims are {@link SessionTracker#claimDue} and {@link SessionTracker#claimOvertime},
 * made here on the session tick where the window is measured. Moving them into the entity
 * task would let two ticks both see "not fired" before either region task ran, and the alert
 * would be sent twice. So a crossing costs one entity task, not one per tick, and a tick with
 * nothing due schedules nothing.
 *
 * <p>Delivery runs inside {@link Scheduler#entity} for that player, because on Folia the tick
 * may not touch a player. Milestones crossed in the same tick go out together in one task, in
 * ascending minute order; a later title replaces an earlier one, and every chat line stays.
 *
 * <h2>One task, milestones first</h2>
 *
 * <p>Milestones and overtime share this observer so that a tick due both schedules one entity
 * task, not two whose relative order would be the scheduler's business. Inside it every
 * milestone goes out first, on all of its channels, and the overtime chat line after them.
 * The order is fixed in {@code deliver}, not by the order of the two claims. Overtime is chat
 * only, so it is sent in addition to a milestone at the same minute, never instead of one.
 *
 * <h2>Exempt players</h2>
 *
 * <p>{@code sessionpulse.exempt} is read on the region thread, never on the tick: it is
 * player data, and a permission plugin is not obliged to be thread-safe. An exempt player's
 * milestones and reminders are still claimed, and delivery is dropped. That consumes them,
 * which is the safe direction: losing the permission mid-session takes effect at the next
 * one, not as a burst of every one already passed.
 *
 * <h2>Lost, never repeated</h2>
 *
 * <p>A player who leaves before the region task runs gets nothing, and the claim stands; on
 * rejoin the stored window seeds it as sent. Every failure here errs towards a missing
 * alert, because the alternative is repeating one.
 */
public final class ReminderObserver implements SessionObserver {

    /** Holders never receive a milestone or a reminder. Declared in {@code plugin.yml}. */
    static final String EXEMPT_PERMISSION = "sessionpulse.exempt";

    private final SessionTracker tracker;
    private final Scheduler scheduler;
    private final Notifier notifier;
    private final Runnable requestFlush;

    /**
     * Creates the observer.
     *
     * @param tracker      decides what is due, and checkpoints the window once it is
     * @param scheduler    where delivery is handed to the player's region
     * @param notifier     the only route to the player's screen
     * @param requestFlush asks storage to write soon; handed {@code storage::flushAsync}
     */
    public ReminderObserver(SessionTracker tracker, Scheduler scheduler, Notifier notifier,
                            Runnable requestFlush) {
        this.tracker = tracker;
        this.scheduler = scheduler;
        this.notifier = notifier;
        this.requestFlush = requestFlush;
    }

    @Override
    public void afterAccrual(Player player, PlayerSession session) {
        List<Milestone> due = tracker.claimDue(session);
        OvertimeClaim overtime = tracker.claimOvertime(session);
        if (due.isEmpty() && overtime == null) {
            return;
        }
        // Checkpoint before the flush request, or the flush finds nothing new to write. Neither
        // claim is persisted: a stored window at or past the minute is what stops a crash
        // straight after this alert from sending it again on rejoin.
        tracker.checkpoint(session);
        requestFlush.run();

        // Captured here, on the tick, so the text matches the claim rather than whatever the
        // window reads by the time the region runs.
        long windowSeconds = session.windowSeconds();
        String name = session.name();

        // An explicit no-op for a retired player: the claim stands and nothing is sent.
        scheduler.entity(player, () -> deliver(player, due, overtime, name, windowSeconds),
                () -> {});
    }

    /**
     * Region thread. Sends every claimed milestone and then the overtime reminder, unless the
     * player is exempt.
     */
    private void deliver(Player player, List<Milestone> due, OvertimeClaim overtime, String name,
                         long windowSeconds) {
        if (player.hasPermission(EXEMPT_PERMISSION)) {
            return;
        }
        Placeholders placeholders = Placeholders.none()
                .player(name)
                .hours(windowSeconds)
                .minutes(windowSeconds);
        for (Milestone milestone : due) {
            notifier.milestone(player, milestone, placeholders);
        }
        if (overtime != null) {
            notifier.chat(player, overtime.message(), placeholders);
        }
    }
}
