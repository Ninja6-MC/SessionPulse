package com.ninja6.sessionpulse.milestone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ninja6.sessionpulse.config.PluginConfig;
import com.ninja6.sessionpulse.notify.Notifier;
import com.ninja6.sessionpulse.notify.TestNotifiers;
import com.ninja6.sessionpulse.platform.RecordingScheduler;
import com.ninja6.sessionpulse.session.PlayerSession;
import com.ninja6.sessionpulse.session.SessionClock;
import com.ninja6.sessionpulse.session.SessionSnapshot;
import com.ninja6.sessionpulse.session.SessionStore;
import com.ninja6.sessionpulse.session.SessionTracker;
import java.io.StringReader;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link ReminderObserver}: claimed on the tick, delivered through the entity scheduler, and
 * never delivered to an exempt player.
 *
 * <p>The player is a {@link Proxy} answering only what the observer and the recording
 * scheduler ask of it, so an observer that starts reaching further into Bukkit fails here.
 * Every permission read is stamped with whether it happened inside an entity task.
 */
class ReminderObserverTest {

    private static final String CONFIG = """
            reminders:
              prefix: ""
              milestones:
                - minute: 60
                  message: "<green>hour <minutes> <hours> <player></green>"
                  action-bar: "<aqua>bar</aqua>"
                  title: "<gold>top</gold>"
                  subtitle: "<gold>bottom</gold>"
                  sound: BLOCK_NOTE_BLOCK_CHIME
                - minute: 61
                  message: "<green>sixty-one</green>"
                - minute: 120
                  message: "<green>two hours</green>"
            """;

    /** A milestone at 180 on every channel, and overtime from 180 every 30 minutes. */
    private static final String OVERTIME_CONFIG = """
            reminders:
              prefix: ""
              milestones:
                - minute: 180
                  message: "<green>three hours</green>"
                  action-bar: "<aqua>bar</aqua>"
                  title: "<gold>top</gold>"
                  subtitle: "<gold>bottom</gold>"
                  sound: BLOCK_NOTE_BLOCK_CHIME
              overtime:
                enabled: true
                after-minutes: 180
                every-minutes: 30
                message: "<red>over <minutes> <hours> <player></red>"
            """;

    /** The same overtime with no milestones at all. */
    private static final String OVERTIME_ONLY_CONFIG = """
            reminders:
              prefix: ""
              milestones: []
              overtime:
                enabled: true
                after-minutes: 180
                every-minutes: 30
                message: "<red>over <minutes> <hours> <player></red>"
            """;

    /** What one delivery of the minute-60 milestone looks like, at a window of 60 minutes. */
    private static final List<String> SIXTY = List.of(
            "chat:§ahour 60 1.0 Ada",
            "actionBar:§bbar",
            "title:§6top/§6bottom",
            "sound:minecraft:block.note_block.chime");

    private long nanos = 5_000_000_000L;
    private long wallMillis = 1_700_000_000_000L;
    private final SessionClock clock = new SessionClock() {
        @Override
        public long nanoTime() {
            return nanos;
        }

        @Override
        public long wallMillis() {
            return wallMillis;
        }
    };

    private final Map<UUID, SessionSnapshot> saved = new HashMap<>();
    private final SessionStore store = new SessionStore() {
        @Override
        public SessionSnapshot load(UUID uuid) {
            return saved.getOrDefault(uuid, SessionSnapshot.UNKNOWN);
        }

        @Override
        public void save(UUID uuid, SessionSnapshot snapshot) {
            saved.put(uuid, snapshot);
        }
    };

    private PluginConfig config = parse(CONFIG);
    private final SessionTracker tracker = new SessionTracker(() -> config, clock, store);
    private final RecordingScheduler scheduler = new RecordingScheduler();
    private final TestNotifiers.RecordingAudience audience = new TestNotifiers.RecordingAudience();
    private final Notifier notifier = TestNotifiers.recording(() -> config, audience);

    private final List<String> flushOrder = new ArrayList<>();
    private final ReminderObserver observer = new ReminderObserver(tracker, scheduler, notifier,
            () -> flushOrder.add("flush with window " + saved.get(this.uuid).windowSeconds()));

    private boolean exempt;
    private final List<Boolean> permissionReadInEntity = new ArrayList<>();
    private final UUID uuid = UUID.randomUUID();

    private final Player player = (Player) Proxy.newProxyInstance(Player.class.getClassLoader(),
            new Class<?>[] {Player.class}, (proxy, method, args) -> switch (method.getName()) {
                case "hasPermission" -> {
                    permissionReadInEntity.add(scheduler.inEntity);
                    yield exempt && ReminderObserver.EXEMPT_PERMISSION.equals(args[0]);
                }
                case "getUniqueId" -> uuid;
                case "equals" -> proxy == args[0];
                case "hashCode" -> System.identityHashCode(proxy);
                case "toString" -> "Ada";
                default -> throw new UnsupportedOperationException(method.getName());
            });

    private static PluginConfig parse(String yaml) {
        return new PluginConfig(YamlConfiguration.loadConfiguration(new StringReader(yaml)));
    }

    /** One session tick for the player, after {@code played} of real time. */
    private PlayerSession tick(Duration played) {
        nanos += played.toNanos();
        wallMillis += played.toMillis();
        PlayerSession session = tracker.accrue(uuid, false);
        observer.afterAccrual(player, session);
        return session;
    }

    @Test
    @DisplayName("a crossing is delivered through Scheduler#entity for that player, every channel in order")
    void dispatchesViaEntityForThatPlayer() {
        tracker.onJoin(uuid, "Ada");

        tick(Duration.ofMinutes(60));

        assertEquals(List.of(player), scheduler.entityTargets,
                "delivery must be handed to the player's own region, once");
        assertEquals(SIXTY, audience.calls());
    }

    @Test
    @DisplayName("two milestones crossed in one tick go out in one entity task, ascending")
    void twoMilestonesOneEntityTaskAscending() {
        tracker.onJoin(uuid, "Ada");
        tick(Duration.ofSeconds(59 * 60 + 30));

        tick(Duration.ofMinutes(2));

        assertEquals(1, scheduler.entityTargets.size());
        assertEquals(List.of(
                "chat:§ahour 61 1.0 Ada", "actionBar:§bbar", "title:§6top/§6bottom",
                "sound:minecraft:block.note_block.chime", "chat:§asixty-one"), audience.calls());
    }

    @Test
    @DisplayName("a tick with nothing due schedules nothing and requests no flush")
    void nothingDueSchedulesNothing() {
        tracker.onJoin(uuid, "Ada");

        tick(Duration.ofMinutes(60).minusSeconds(1));

        assertTrue(scheduler.entityTargets.isEmpty());
        assertTrue(flushOrder.isEmpty());
        assertTrue(saved.isEmpty(), "and checkpoints nothing");
    }

    @Test
    @DisplayName("one entity task per crossing, not per tick, while delivery is still pending")
    void claimHappensOnTickNotRegion() {
        tracker.onJoin(uuid, "Ada");
        scheduler.deferEntity = true;

        tick(Duration.ofMinutes(60));
        tick(Duration.ofSeconds(1));

        assertEquals(1, scheduler.entityTargets.size(),
                "a claim left to the region would let the second tick schedule the same alert");
        assertEquals(1, scheduler.runEntity());
        assertEquals(SIXTY, audience.calls());
    }

    @Test
    @DisplayName("a player gone before the region runs gets nothing, and the milestone stays claimed")
    void retiredPlayerStaysClaimed() {
        tracker.onJoin(uuid, "Ada");
        scheduler.deferEntity = true;

        PlayerSession session = tick(Duration.ofMinutes(60));
        assertEquals(1, scheduler.retireEntity());
        tick(Duration.ofSeconds(1));

        assertTrue(audience.calls().isEmpty());
        assertTrue(session.hasFired(60));
        assertEquals(1, scheduler.entityTargets.size(), "retiring must not re-arm the milestone");
    }

    @Test
    @DisplayName("a player holding sessionpulse.exempt receives nothing, and it stays claimed")
    void exemptNeverReceives() {
        exempt = true;
        tracker.onJoin(uuid, "Ada");

        PlayerSession session = tick(Duration.ofMinutes(60));
        tick(Duration.ofMinutes(60));

        assertTrue(audience.calls().isEmpty(), "an exempt player saw " + audience.calls());
        assertTrue(session.hasFired(60) && session.hasFired(120));

        exempt = false;
        tick(Duration.ofMinutes(1));
        assertTrue(audience.calls().isEmpty(),
                "losing the exemption must not replay the milestones already passed");
    }

    @Test
    @DisplayName("sessionpulse.exempt is read only inside the entity task, never on the tick")
    void exemptPermissionNotReadOnTick() {
        tracker.onJoin(uuid, "Ada");

        tick(Duration.ofMinutes(59));
        tick(Duration.ofMinutes(1));
        tick(Duration.ofMinutes(60));

        assertFalse(permissionReadInEntity.isEmpty(), "the permission was never checked");
        assertFalse(permissionReadInEntity.contains(false),
                "a permission read on the global tick touches player data off its region");
    }

    @Test
    @DisplayName("a claim checkpoints the reached window, then requests a flush")
    void claimCheckpointsBeforeFlush() {
        tracker.onJoin(uuid, "Ada");

        tick(Duration.ofSeconds(60 * 60 + 5));
        tick(Duration.ofSeconds(1));

        assertEquals(List.of("flush with window 3605"), flushOrder,
                "one flush per claiming tick, requested after the reached window was stored");
    }

    @Test
    @DisplayName("placeholders carry the counted window and name at the claim")
    void placeholdersUseCountedWindow() {
        saved.put(uuid, new SessionSnapshot("Ada", 7200L, wallMillis, 3000L, wallMillis));
        tracker.onJoin(uuid, "Ada");
        scheduler.deferEntity = true;

        tick(Duration.ofMinutes(10));
        tick(Duration.ofMinutes(45));
        scheduler.runEntity();

        assertEquals(SIXTY.get(0), audience.calls().get(0),
                "fifty stored minutes plus ten played; not the ten-minute session, and not the "
                        + "window by the time the region ran");
    }

    @Test
    @DisplayName("a milestone and overtime on the same minute go out in one task, milestone first")
    void sameMinuteMilestoneThenOvertime() {
        config = parse(OVERTIME_CONFIG);
        tracker.onJoin(uuid, "Ada");

        tick(Duration.ofMinutes(180));

        assertEquals(1, scheduler.entityTargets.size(), "two tasks would leave the order to Folia");
        assertEquals(List.of(
                "chat:§athree hours", "actionBar:§bbar", "title:§6top/§6bottom",
                "sound:minecraft:block.note_block.chime", "chat:§cover 180 3.0 Ada"),
                audience.calls(),
                "every milestone channel first, then the overtime chat line, and neither replaces "
                        + "the other");
    }

    @Test
    @DisplayName("an overtime reminder alone is one task and one chat line, text fixed at the claim")
    void overtimeAloneCapturesPlaceholdersAtClaim() {
        config = parse(OVERTIME_CONFIG);
        tracker.onJoin(uuid, "Ada");
        tick(Duration.ofMinutes(180));
        int before = audience.calls().size();
        scheduler.deferEntity = true;

        tick(Duration.ofMinutes(30));
        tick(Duration.ofMinutes(7));

        assertEquals(2, scheduler.entityTargets.size(), "one task at 180, one at 210");
        assertEquals(1, scheduler.runEntity());
        assertEquals(List.of("chat:§cover 210 3.5 Ada"),
                audience.calls().subList(before, audience.calls().size()),
                "read at delivery, the window would say 217");
    }

    @Test
    @DisplayName("an exempt player's reminder is consumed, and losing the exemption does not replay it")
    void exemptOvertimeConsumed() {
        config = parse(OVERTIME_CONFIG);
        tracker.onJoin(uuid, "Ada");
        tick(Duration.ofSeconds(179 * 60 + 59));
        exempt = true;

        tick(Duration.ofSeconds(1));
        exempt = false;
        tick(Duration.ofSeconds(1));
        tick(Duration.ofSeconds(30 * 60 - 2));

        assertTrue(audience.calls().isEmpty(),
                "a claim held back while exempt would be sent here: " + audience.calls());
        assertFalse(permissionReadInEntity.contains(false),
                "a permission read on the global tick touches player data off its region");

        tick(Duration.ofSeconds(1));
        assertEquals(List.of("chat:§cover 210 3.5 Ada"), audience.calls());
    }

    @Test
    @DisplayName("an overtime claim checkpoints the reached window, then requests a flush")
    void overtimeClaimCheckpointsBeforeFlush() {
        config = parse(OVERTIME_ONLY_CONFIG);
        tracker.onJoin(uuid, "Ada");

        tick(Duration.ofSeconds(180 * 60 + 5));
        tick(Duration.ofSeconds(1));

        assertEquals(List.of("flush with window 10805"), flushOrder,
                "one flush per claiming tick, requested after the reached window was stored");
    }

    @Test
    @DisplayName("a reminder lost to a quit before the region runs is not sent after the rejoin")
    void retiredOvertimeNotResent() {
        config = parse(OVERTIME_ONLY_CONFIG);
        tracker.onJoin(uuid, "Ada");
        scheduler.deferEntity = true;

        tick(Duration.ofMinutes(180));
        assertEquals(1, scheduler.retireEntity());
        tracker.onQuit(uuid);
        tracker.onJoin(uuid, "Ada");
        scheduler.deferEntity = false;
        tick(Duration.ofSeconds(29 * 60 + 59));

        assertTrue(audience.calls().isEmpty());
        assertEquals(1, scheduler.entityTargets.size(), "the rejoin must not re-arm minute 180");
    }
}
