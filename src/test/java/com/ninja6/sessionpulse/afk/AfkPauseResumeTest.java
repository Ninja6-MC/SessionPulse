package com.ninja6.sessionpulse.afk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;

import com.ninja6.sessionpulse.config.PluginConfig;
import com.ninja6.sessionpulse.platform.RecordingScheduler;
import com.ninja6.sessionpulse.session.PlayerSession;
import com.ninja6.sessionpulse.session.SessionSnapshot;
import com.ninja6.sessionpulse.session.SessionStore;
import com.ninja6.sessionpulse.session.SessionTracker;
import java.util.UUID;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The counted window pauses and resumes under every mode, end to end: the service the plugin
 * hands the tick, feeding {@link SessionTracker#accrue} once a second, with entity tasks run
 * between ticks the way a region would run them.
 *
 * <p>{@code SessionTickTask} itself resolves players through the static {@code Bukkit}, so the
 * tick is reproduced here as the one line it is. The arithmetic of the pause has its own tests
 * in the session package; what these prove is that each detector's verdict reaches it.
 */
class AfkPauseResumeTest {

    private final RecordingScheduler scheduler = new RecordingScheduler();
    private final AfkFixture.Clock clock = new AfkFixture.Clock();
    private final AfkFixture.Log log = new AfkFixture.Log();
    private final int[] violations = new int[1];
    private PluginConfig config = AfkFixture.config("AUTO", 300);
    private final BuiltInAfkDetector builtIn = new BuiltInAfkDetector(clock, () -> config);
    private Object essentials;
    private final AfkService service =
            new AfkService(() -> essentials, builtIn, scheduler, log.logger, () -> config);
    private final SessionTracker tracker = new SessionTracker(() -> config, clock, new SessionStore() {
        @Override
        public SessionSnapshot load(UUID uuid) {
            return SessionSnapshot.UNKNOWN;
        }

        @Override
        public void save(UUID uuid, SessionSnapshot snapshot) {
        }
    });
    private final UUID uuid = UUID.randomUUID();

    AfkPauseResumeTest() {
        scheduler.deferEntity = true;
    }

    @AfterEach
    void essentialsWasOnlyCalledOnTheRegion() {
        if (essentials instanceof FakeEssentials fake) {
            assertEquals(0, fake.offRegionCalls, "EssentialsX was called from the tick");
        }
        assertEquals(0, violations[0], "a permission was read from the tick");
    }

    private Player join(boolean hasAutoAfk) {
        Player player = AfkFixture.player(uuid, "Ada", hasAutoAfk, scheduler, violations);
        tracker.onJoin(uuid, "Ada");
        builtIn.seed(uuid);
        return player;
    }

    private void resolve(String mode) {
        config = AfkFixture.config(mode, 300);
        service.resolve();
    }

    /** One second of play: optional input, the tick, then whatever the region has queued. */
    private void tick(Player player, int seconds, boolean input) {
        for (int i = 0; i < seconds; i++) {
            clock.advanceSeconds(1);
            if (input) {
                builtIn.markActive(uuid);
            }
            tracker.accrue(uuid, service.isAfk(player));
            scheduler.runEntity();
        }
    }

    /** 60s active, 600s without input, then 30s active again. */
    private long idleSpell(Player player) {
        PlayerSession session = tracker.sessions().iterator().next();
        tick(player, 60, true);
        tick(player, 600, false);
        tick(player, 30, true);
        return session.windowSeconds();
    }

    /**
     * 60 active; the idle ticks before the threshold (299, since the tick at exactly 300 is
     * AFK) still count; the rest of the 600 do not; the first input ends the pause at the next
     * tick; then 30.
     */
    private static final long IDLE_TIMER_WINDOW = 60 + 299 + 30;

    @Test
    @DisplayName("BUILT_IN: ten minutes without input pauses after the threshold, and input resumes")
    void builtInPausesAndResumes() {
        resolve("BUILT_IN");
        Player player = join(false);

        assertEquals(IDLE_TIMER_WINDOW, idleSpell(player));
    }

    @Test
    @DisplayName("AUTO without EssentialsX: the same pause as BUILT_IN")
    void autoWithoutEssentialsUsesTheTimer() {
        resolve("AUTO");
        Player player = join(false);

        assertSame(builtIn, service.current());
        assertEquals(IDLE_TIMER_WINDOW, idleSpell(player));
    }

    @Test
    @DisplayName("OFF: ten minutes without input is counted in full")
    void offNeverPauses() {
        resolve("OFF");
        Player player = join(false);

        assertEquals(60 + 600 + 30, idleSpell(player));
    }

    @Test
    @DisplayName("ESSENTIALS: pauses one tick after EssentialsX marks the player AFK, resumes one tick after")
    void essentialsPausesAndResumesOneTickLate() {
        FakeEssentials fake = new FakeEssentials(scheduler, 300);
        essentials = fake;
        resolve("ESSENTIALS");
        Player player = join(false);
        PlayerSession session = tracker.sessions().iterator().next();

        tick(player, 60, false);
        fake.user(uuid).afk = true;
        tick(player, 10, false);
        fake.user(uuid).afk = false;
        tick(player, 10, false);

        assertEquals(60 + 1 + 9, session.windowSeconds(),
                "the first tick of each spell reads the verdict cached a second earlier");
    }

    @Test
    @DisplayName("ESSENTIALS: idle without /afk never pauses; EssentialsX is the one source of truth")
    void essentialsIgnoresTheTimer() {
        essentials = new FakeEssentials(scheduler, 300);
        resolve("ESSENTIALS");
        Player player = join(false);

        assertEquals(60 + 600 + 30, idleSpell(player));
    }

    @Test
    @DisplayName("AUTO with EssentialsX and the auto-afk node: EssentialsX decides, the timer does not")
    void autoWithTheNodeFollowsEssentials() {
        FakeEssentials fake = new FakeEssentials(scheduler, 300);
        essentials = fake;
        resolve("AUTO");
        Player player = join(true);
        PlayerSession session = tracker.sessions().iterator().next();

        assertInstanceOf(EssentialsAfkDetector.class, service.current());
        assertEquals(60 + 600 + 30, idleSpell(player),
                "EssentialsX never marked them, and it owns players holding the node");

        fake.user(uuid).afk = true;
        tick(player, 10, true);
        assertEquals(60 + 600 + 30 + 1, session.windowSeconds());
    }

    @Test
    @DisplayName("AUTO with EssentialsX, auto-afk 300 and no auto-afk node: idle past the threshold pauses")
    void autoWithoutTheNodeFallsBackToTheTimer() {
        essentials = new FakeEssentials(scheduler, 300);
        resolve("AUTO");
        Player player = join(false);

        assertInstanceOf(EssentialsAfkDetector.class, service.current());
        assertEquals(IDLE_TIMER_WINDOW, idleSpell(player),
                "EssentialsX never marks a player without essentials.afk.auto, so without the "
                        + "fallback this player is never paused");
    }

    @Test
    @DisplayName("AUTO without the node: a manual /afk still pauses before the timer would")
    void autoWithoutTheNodeStillHonoursManualAfk() {
        FakeEssentials fake = new FakeEssentials(scheduler, 300);
        essentials = fake;
        resolve("AUTO");
        Player player = join(false);
        PlayerSession session = tracker.sessions().iterator().next();

        tick(player, 60, true);
        fake.user(uuid).afk = true;
        tick(player, 10, true);

        assertEquals(60 + 1, session.windowSeconds());
    }

    @Test
    @DisplayName("AUTO with a hook that breaks mid-run: the idle timer takes over")
    void autoWithADeadHookUsesTheTimer() {
        FakeEssentials fake = new FakeEssentials(scheduler, 300);
        essentials = fake;
        resolve("AUTO");
        Player player = join(true);

        tick(player, 1, true);
        fake.getUserThrows = new NoClassDefFoundError("net/ess3/api/IUser");
        PlayerSession session = tracker.sessions().iterator().next();
        long before = session.windowSeconds();
        tick(player, 59, true);
        tick(player, 600, false);
        tick(player, 30, true);

        assertEquals(before + 59 + 299 + 30, session.windowSeconds(),
                "a dead hook in AUTO is the timer, not nobody");
    }
}
