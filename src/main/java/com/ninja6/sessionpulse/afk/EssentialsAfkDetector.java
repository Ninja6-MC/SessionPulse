package com.ninja6.sessionpulse.afk;

import com.ninja6.sessionpulse.platform.Scheduler;
import org.bukkit.entity.Player;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Asks EssentialsX whether a player is AFK, without naming a single EssentialsX type.
 *
 * <h2>Reflection, resolved once</h2>
 *
 * <p>EssentialsX is a soft dependency. A class that imported one of its types would fail to
 * load on every server without it, so the four calls are looked up by name in
 * {@link Hook#bind}, once per resolve, and nothing is looked up per call. The names are
 * {@code Essentials#getSettings()}, {@code ISettings#getAutoAfk()},
 * {@code Essentials#getUser(Player)} and {@code IUser#isAfk()}, verified against EssentialsX
 * 2.20.1 only. A later release that renames any of them fails the bind, and the resolver
 * falls back rather than the tick throwing.
 *
 * <h2>Never on the tick</h2>
 *
 * <p>The tick runs on the global region, which on Folia does not own the player, and
 * EssentialsX user data is player data. So {@link #isAfk} never calls EssentialsX. It returns
 * the last verdict cached for that player and, unless one is already queued, schedules a
 * refresh on the player's own region through {@link Scheduler#entity}. The refresh reads
 * EssentialsX there and caches the answer. A verdict is therefore one tick old, which costs
 * at most a second at either end of an AFK spell. There is at most one queued refresh per
 * player, so a slow region never piles them up.
 *
 * <h2>AUTO blends per player</h2>
 *
 * <p>EssentialsX only marks a player AFK automatically if they hold
 * {@code essentials.afk.auto}, which it declares {@code default: false}. On a stock install
 * only a manual {@code /afk} would ever pause anybody. So in AUTO the refresh also caches
 * that permission, and a player without it falls back to the built-in idle timer: they are
 * AFK when EssentialsX says so, or when the timer does. A player with the node is left to
 * EssentialsX entirely. In ESSENTIALS mode there is no blend, because the operator asked for
 * EssentialsX as the one source of truth.
 *
 * <h2>When EssentialsX breaks</h2>
 *
 * <p>A reflective call that fails in itself - a missing or inaccessible method, a result of
 * the wrong type - or a linkage error from inside EssentialsX means the binding no longer
 * fits the EssentialsX on the server, and will not start fitting again. The detector goes
 * dead: it says so once, schedules nothing more, and answers as though the hook had never
 * bound - the built-in timer in AUTO, nobody in ESSENTIALS. Any other exception from inside
 * EssentialsX's own code is one bad refresh, not a broken binding, and so is a {@code null}
 * user, which it returns while starting or stopping: the player is cached as not AFK, a
 * failure is reported once, and the next tick asks again.
 *
 * <p>A refresh still queued when its player leaves is left to run or retire. {@link #forget}
 * drops only the cached verdict, and the refresh checks the player is still valid before it
 * caches anything, so a departed player is not cached again.
 */
public final class EssentialsAfkDetector implements AfkDetector {

    /** The node EssentialsX requires before it marks anyone AFK on its own. */
    static final String AUTO_AFK_PERMISSION = "essentials.afk.auto";

    /** The reflective handles for one EssentialsX instance. */
    static final class Hook {

        final Object essentials;
        final Method getUser;
        final Method isAfk;
        final long autoAfkSeconds;

        Hook(Object essentials, Method getUser, Method isAfk, long autoAfkSeconds) {
            this.essentials = essentials;
            this.getUser = getUser;
            this.isAfk = isAfk;
            this.autoAfkSeconds = autoAfkSeconds;
        }

        /**
         * Resolves every method the detector will call, and reads {@code auto-afk}.
         *
         * <p>Main thread, from enable, reload or a plugin enable or disable. {@code auto-afk}
         * is read here and only here, so an {@code /essentials reload} that changes it is not
         * seen until SessionPulse resolves again.
         *
         * @param essentials the plugin instance
         * @return the bound hook
         * @throws ReflectiveOperationException if a method is missing or its call fails
         * @throws ClassCastException           if {@code getAutoAfk} is not a number
         */
        static Hook bind(Object essentials) throws ReflectiveOperationException {
            Object settings = essentials.getClass().getMethod("getSettings").invoke(essentials);
            Object autoAfk = settings.getClass().getMethod("getAutoAfk").invoke(settings);
            long autoAfkSeconds = ((Number) autoAfk).longValue();
            // The exact parameter type matters: Essentials also has getUser(Object),
            // getUser(String) and getUser(UUID).
            Method getUser = essentials.getClass().getMethod("getUser", Player.class);
            Method isAfk = getUser.getReturnType().getMethod("isAfk");
            if (isAfk.getReturnType() != boolean.class) {
                throw new NoSuchMethodException(
                        getUser.getReturnType().getName() + ".isAfk() does not return boolean");
            }
            return new Hook(essentials, getUser, isAfk, autoAfkSeconds);
        }
    }

    /** One player's last answer from the region. */
    private record Verdict(boolean essentialsAfk, boolean autoAfkPermission) {
    }

    /** Before the first refresh: not AFK by EssentialsX, and no node, so AUTO uses the timer. */
    private static final Verdict UNKNOWN = new Verdict(false, false);

    private final Hook hook;
    private final BuiltInAfkDetector idleFallback;
    private final Scheduler scheduler;
    private final Logger logger;

    private final Map<UUID, Verdict> cached = new ConcurrentHashMap<>();
    private final Set<UUID> refreshPending = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean dead = new AtomicBoolean();
    private final AtomicBoolean refreshFailureReported = new AtomicBoolean();
    private final AtomicBoolean scheduleFailureReported = new AtomicBoolean();

    /**
     * Creates the detector.
     *
     * @param hook         a bound hook
     * @param idleFallback the built-in timer, for AUTO; {@code null} for ESSENTIALS
     * @param scheduler    where each refresh is handed to the player's region
     * @param logger       where a broken hook is reported, once
     */
    EssentialsAfkDetector(Hook hook, BuiltInAfkDetector idleFallback, Scheduler scheduler,
                          Logger logger) {
        this.hook = hook;
        this.idleFallback = idleFallback;
        this.scheduler = scheduler;
        this.logger = logger;
    }

    @Override
    public boolean isAfk(Player player) {
        try {
            if (dead.get()) {
                return idleFallback != null && idleFallback.isAfk(player);
            }
            UUID uuid = player.getUniqueId();
            Verdict verdict = cached.getOrDefault(uuid, UNKNOWN);
            requestRefresh(player, uuid);
            return verdict.essentialsAfk()
                    || (idleFallback != null && !verdict.autoAfkPermission()
                            && idleFallback.isAfk(player));
        } catch (Throwable thrown) {
            // Nothing above calls EssentialsX, so this is not expected. It is here because the
            // tick does not catch around the gate, and a throw would stop the plugin counting.
            return false;
        }
    }

    /**
     * Drops the cached verdict. A queued refresh keeps its pending mark until it runs or
     * retires, so a player never has two queued at once.
     */
    @Override
    public void forget(UUID uuid) {
        cached.remove(uuid);
    }

    /** Whether a broken binding has stopped the detector. */
    boolean isDead() {
        return dead.get();
    }

    private void requestRefresh(Player player, UUID uuid) {
        if (!refreshPending.add(uuid)) {
            return;
        }
        try {
            scheduler.entity(player, () -> refresh(player, uuid), () -> refreshPending.remove(uuid));
        } catch (Throwable thrown) {
            // Left pending, this player would never be refreshed again.
            refreshPending.remove(uuid);
            if (scheduleFailureReported.compareAndSet(false, true)) {
                logger.log(Level.WARNING, "AFK detection: could not schedule an EssentialsX "
                        + "check for " + player.getName() + ". Retrying on later ticks.", thrown);
            }
        }
    }

    /** Region thread. The only place EssentialsX is called after the bind. */
    private void refresh(Player player, UUID uuid) {
        try {
            if (dead.get() || !player.isValid()) {
                // Region thread, so the validity read is safe. A player who left while this
                // was queued must not be cached again after forget.
                return;
            }
            Object user = hook.getUser.invoke(hook.essentials, player);
            boolean essentialsAfk = user != null && (Boolean) hook.isAfk.invoke(user);
            boolean autoAfkPermission =
                    idleFallback != null && player.hasPermission(AUTO_AFK_PERMISSION);
            cached.put(uuid, new Verdict(essentialsAfk, autoAfkPermission));
        } catch (Throwable thrown) {
            cached.remove(uuid);
            if (isBrokenBinding(thrown)) {
                if (dead.compareAndSet(false, true)) {
                    cached.clear();
                    logger.log(Level.WARNING, "AFK detection: EssentialsX stopped answering the "
                            + "calls SessionPulse binds to. "
                            + (idleFallback != null
                                    ? "Using the built-in idle timer until the next reload."
                                    : "Nobody will be treated as AFK until the next reload."),
                            thrown);
                }
            } else if (refreshFailureReported.compareAndSet(false, true)) {
                logger.log(Level.WARNING, "AFK detection: EssentialsX threw while checking "
                        + player.getName() + ". Treated as not AFK; reported once.", thrown);
            }
        } finally {
            refreshPending.remove(uuid);
        }
    }

    /**
     * Whether a failure means the binding itself no longer fits.
     *
     * <p>What EssentialsX's own code throws arrives wrapped in
     * {@link InvocationTargetException}, and of that only a linkage error - a class missing or
     * changed underneath it - is permanent. Unwrapped, the failure is the reflective call itself
     * or the cast of its result, which would repeat on every call.
     */
    private static boolean isBrokenBinding(Throwable thrown) {
        if (thrown instanceof InvocationTargetException) {
            return thrown.getCause() instanceof LinkageError;
        }
        return thrown instanceof LinkageError
                || thrown instanceof ReflectiveOperationException
                || thrown instanceof ClassCastException;
    }
}
