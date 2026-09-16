package com.ninja6.sessionpulse.notify;

import com.ninja6.sessionpulse.config.Milestone;
import com.ninja6.sessionpulse.config.PluginConfig;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.platform.bukkit.BukkitAudiences;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.title.Title;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * The only route to a player's screen: chat, action bar, title and sound, rendered from
 * MiniMessage and delivered through Adventure on Paper, Folia, Spigot and Purpur alike.
 *
 * <h2>Why everything goes through here</h2>
 *
 * <p>Adventure is shaded and relocated: our {@code net.kyori} is
 * {@code com.ninja6.sessionpulse.lib.kyori} inside the jar, so on Paper our
 * {@code Component} is not the {@code Component} Paper ships. {@code player.sendMessage(Component)}
 * would compile against our copy and fail at runtime against Paper's. Three gates keep that
 * call out of the plugin. The compiler is the first: this project compiles against
 * spigot-api, where the overload does not exist. This class is the second: every output
 * goes through {@link BukkitAudiences}, on all four platforms. {@code OutputDoorTest} is the
 * third: no other main source names {@code net.kyori}, and the configuration validator
 * that has to name MiniMessage may name nothing else from it.
 *
 * <p>Two doors stay open, because neither names Adventure and no scan can tell them from
 * legitimate code: {@code player.spigot().sendMessage(ChatMessageType, BaseComponent...)},
 * and a raw MiniMessage string passed to {@code player.sendMessage(String)} or
 * {@code player.sendTitle}, which shows the operator's tags as text. Use this class instead
 * of either.
 *
 * <h2>Threading</h2>
 *
 * <p>This class does not schedule. On Folia a player may only be touched from that player's
 * own region thread, so every delivery method is called from inside
 * {@code Scheduler#entity} for that player, never from the session tick directly. The render
 * methods touch no player and no server and are safe anywhere.
 *
 * <h2>Why {@link #open()} is separate from the constructor</h2>
 *
 * <p>Building {@link BukkitAudiences} registers listeners with the plugin manager and reads
 * the online players. That belongs on the main thread in {@code onEnable}, not on whatever
 * region thread happens to make the first send, and it must not happen in the constructor
 * either, or nothing here could be built in a unit test. Before {@code open()} and after
 * {@link #close()} every delivery method returns without touching the player.
 *
 * <h2>Versions</h2>
 *
 * <p>adventure-platform-bukkit 4.4.1 was built against adventure-api 4.21.0 and runs here
 * against 4.26.1. Adventure keeps binary compatibility within 4.x, and the boot legs link
 * the console path on every run, but <b>no automated check exercises the player facets</b>;
 * nothing joins a CI server.
 *
 * <p>That version gap is why no delivery here passes a {@code Sound.Emitter}. The platform's
 * {@code CraftBukkitFacet$EntitySound} and {@code $EntitySound_1_19_3}, the only facets that
 * can carry one, are skipped on 26.x; the emitter-less {@code BukkitFacet$SoundWithCategory} is the
 * one that applies everywhere, and it plays at the audience's own position. An emitter-taking
 * call would match no facet on 26.x and be dropped without a word.
 */
public final class Notifier {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    /**
     * Section-sign legacy text with hex colours kept.
     *
     * <p>Not {@code LegacyComponentSerializer.legacySection()}: that one downsamples
     * {@code <color:#ff8800>} to the nearest named colour, {@code §6}. This one writes the
     * {@code §x§f§f§8§8§0§0} form every server since 1.16 displays. Immutable and thread-safe.
     *
     * <p>Package-private so the tests assert against this instance, not a look-alike.
     */
    static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.builder()
            .character(LegacyComponentSerializer.SECTION_CHAR)
            .hexColors()
            .useUnusualXRepeatedCharacterHexFormat()
            .build();

    /** Stored only. Nothing reads it until {@link #open()}. */
    private final Plugin plugin;

    /** Read on every render, never captured, so a reload reaches the prefix. */
    private final Supplier<PluginConfig> config;

    /**
     * Where a player's or a command sender's audience comes from in a test; {@code null} for
     * the real thing.
     */
    private final Function<CommandSender, Audience> injectedAudiences;

    /** {@code null} before {@link #open()} and after {@link #close()}. */
    private volatile BukkitAudiences audiences;

    /**
     * A notifier that touches nothing until it is opened.
     *
     * @param plugin the owning plugin, used by {@link #open()} and nothing else
     * @param config the configuration in force; handed {@code plugin::config}, never the object
     */
    public Notifier(Plugin plugin, Supplier<PluginConfig> config) {
        this.plugin = plugin;
        this.config = config;
        this.injectedAudiences = null;
    }

    /**
     * A notifier that delivers to whatever audience {@code audiences} returns. For tests: an
     * {@link Audience}'s delivery methods are all default methods, so a recording one needs
     * no server. {@link #open()} and {@link #close()} do nothing on it.
     */
    Notifier(Supplier<PluginConfig> config, Function<CommandSender, Audience> audiences) {
        this.plugin = null;
        this.config = config;
        this.injectedAudiences = audiences;
    }

    /**
     * Builds the audience provider. Main thread, from {@code onEnable}. A second call while
     * open does nothing.
     */
    public void open() {
        if (injectedAudiences == null && audiences == null) {
            audiences = BukkitAudiences.create(plugin);
        }
    }

    /**
     * Closes the audience provider. Safe before {@link #open()} and safe twice, because
     * {@code onDisable} also runs after an enable that failed part way.
     */
    public void close() {
        BukkitAudiences closing = audiences;
        audiences = null;
        if (closing != null) {
            closing.close();
        }
    }

    /**
     * A chat line, with the configured prefix in front of it.
     *
     * @param player       the recipient; call on its region thread
     * @param miniMessage  the message, or {@code null} to send nothing
     * @param placeholders values for the message and the prefix
     */
    public void chat(Player player, String miniMessage, Placeholders placeholders) {
        if (miniMessage == null) {
            return;
        }
        Audience audience = audienceFor(player);
        if (audience != null) {
            deliverChat(audience, miniMessage, placeholders);
        }
    }

    /**
     * A reply to whoever ran a command: a chat line, with the configured prefix, to a player
     * or to the console alike.
     *
     * <p>No entity hop, and none is needed. A command already runs where its sender may be
     * touched: a player's on that player's own region thread, the console's on the global
     * region. A player goes through {@link #chat}, so there is one route to a player's chat
     * whatever asked for it; any other sender goes through the audience provider's
     * {@code sender} lookup. Does nothing before {@link #open()} and after {@link #close()}.
     *
     * @param sender       who ran the command; call on the thread the command runs on
     * @param miniMessage  the reply, or {@code null} to send nothing
     * @param placeholders values for the reply and the prefix
     */
    public void send(CommandSender sender, String miniMessage, Placeholders placeholders) {
        if (sender instanceof Player player) {
            chat(player, miniMessage, placeholders);
            return;
        }
        if (miniMessage == null) {
            return;
        }
        Audience audience = senderAudience(sender);
        if (audience != null) {
            deliverChat(audience, miniMessage, placeholders);
        }
    }

    /**
     * Action-bar text. No prefix.
     *
     * @param player       the recipient; call on its region thread
     * @param miniMessage  the text, or {@code null} to send nothing
     * @param placeholders values for the text
     */
    public void actionBar(Player player, String miniMessage, Placeholders placeholders) {
        if (miniMessage == null) {
            return;
        }
        Audience audience = audienceFor(player);
        if (audience != null) {
            deliverActionBar(audience, miniMessage, placeholders);
        }
    }

    /**
     * A title and subtitle. No prefix. A missing half is shown empty; both missing sends
     * nothing.
     *
     * @param player       the recipient; call on its region thread
     * @param title        the title, or {@code null}
     * @param subtitle     the subtitle, or {@code null}
     * @param placeholders values for both halves
     */
    public void title(Player player, String title, String subtitle, Placeholders placeholders) {
        if (title == null && subtitle == null) {
            return;
        }
        Audience audience = audienceFor(player);
        if (audience != null) {
            deliverTitle(audience, title, subtitle, placeholders);
        }
    }

    /**
     * A sound at the player's own position.
     *
     * <p>Delivered through the emitter-less {@code playSound(Sound)}: it is the only overload
     * an applicable facet backs on 26.x, and it already plays where the audience stands. See
     * the class javadoc for the facet the emitter-taking overloads would have needed.
     *
     * @param player the recipient; call on its region thread
     * @param sound  the sound, or {@code null} for silence
     */
    public void sound(Player player, Sound sound) {
        if (sound == null) {
            return;
        }
        Audience audience = audienceFor(player);
        if (audience != null) {
            deliverSound(audience, sound);
        }
    }

    /**
     * Everything a milestone carries, in the order chat, action bar, title, sound. Each
     * field that is {@code null} is skipped.
     *
     * @param player       the recipient; call on its region thread
     * @param milestone    the milestone that fired
     * @param placeholders values for every text field
     */
    public void milestone(Player player, Milestone milestone, Placeholders placeholders) {
        if (!milestone.hasAnythingToShow()) {
            return;
        }
        Audience audience = audienceFor(player);
        if (audience == null) {
            return;
        }
        if (milestone.message() != null) {
            deliverChat(audience, milestone.message(), placeholders);
        }
        if (milestone.actionBar() != null) {
            deliverActionBar(audience, milestone.actionBar(), placeholders);
        }
        if (milestone.title() != null || milestone.subtitle() != null) {
            deliverTitle(audience, milestone.title(), milestone.subtitle(), placeholders);
        }
        if (milestone.sound() != null) {
            deliverSound(audience, milestone.sound());
        }
    }

    /**
     * A message rendered to section-sign text, for the two places Adventure cannot reach:
     * {@code AsyncPlayerPreLoginEvent#disallow(Result, String)} and
     * {@code Player#kickPlayer(String)}, both String-only on spigot-api. No prefix.
     *
     * <p>Lossy. Colours and decorations survive, hex included; a gradient becomes one hex
     * colour per character; hover and click are dropped and their text kept.
     *
     * @param miniMessage  the message, or {@code null}
     * @param placeholders values for the message
     * @return the rendered text; empty for {@code null}
     */
    public String legacy(String miniMessage, Placeholders placeholders) {
        if (miniMessage == null) {
            return "";
        }
        return LEGACY.serialize(renderLine(miniMessage, placeholders));
    }

    /**
     * Boot linkage probe; not a reminder channel.
     *
     * <p>Sends a chat line, an action bar, a title and a sound to the console through exactly
     * the render methods a player gets. The console discards the last three; linking them is
     * the point, so an Adventure API / platform mismatch fails a boot leg instead of the
     * first milestone on a live server. The sound links Adventure's {@code playSound(Sound)},
     * the same emitter-less call a player gets. It matters most: {@link #renderSound} calls
     * {@code Sound#getKey()}, compiled against the 1.20.4 enum, and on 1.21.x {@link Sound}
     * is an interface. Does nothing while closed, and skips each {@code null}.
     *
     * @param message   chat line, prefixed as a player's would be
     * @param actionBar action-bar text
     * @param title     title text
     * @param subtitle  subtitle text
     * @param sound     a sound, mapped by its key as a milestone's is
     */
    public void console(String message, String actionBar, String title, String subtitle,
                        Sound sound) {
        Audience console = injectedAudiences != null ? null : consoleAudience();
        if (console == null) {
            return;
        }
        Placeholders none = Placeholders.none();
        if (message != null) {
            deliverChat(console, message, none);
        }
        if (actionBar != null) {
            deliverActionBar(console, actionBar, none);
        }
        if (title != null || subtitle != null) {
            deliverTitle(console, title, subtitle, none);
        }
        if (sound != null) {
            deliverSound(console, sound);
        }
    }

    // -----------------------------------------------------------------------------
    // Rendering. Package-private: this is what the tests assert on, and no public
    // signature may name an Adventure type.
    // -----------------------------------------------------------------------------

    /**
     * The prefix and the message as siblings under an empty root.
     *
     * <p>Siblings rather than {@code prefix.append(body)}. Today the two render identically,
     * because MiniMessage's root is unstyled and a validated prefix closes its tags; the
     * sibling form is what keeps a prefix's style out of the body if that validation ever
     * loosens. The prefix is read from the configuration on this call and gets the same
     * placeholders as the body.
     *
     * <p>No configuration means no prefix. The plugin nulls its configuration during disable,
     * and on Folia a region task that fetched its audience before {@link #close()} can still
     * reach this line afterwards.
     */
    Component renderChat(String miniMessage, Placeholders placeholders) {
        PluginConfig current = config.get();
        String prefix = current == null ? "" : current.reminderPrefix();
        return Component.empty()
                .append(renderLine(prefix, placeholders))
                .append(renderLine(miniMessage, placeholders));
    }

    /** One MiniMessage string, placeholders filled, no prefix. */
    Component renderLine(String miniMessage, Placeholders placeholders) {
        return MINI.deserialize(miniMessage, resolver(placeholders));
    }

    /**
     * A title, or {@code null} when both halves are {@code null}. A missing half is
     * {@link Component#empty()}. Vanilla timings: half a second in, three and a half
     * held, one out.
     */
    Title renderTitle(String title, String subtitle, Placeholders placeholders) {
        if (title == null && subtitle == null) {
            return null;
        }
        Component top = title == null ? Component.empty() : renderLine(title, placeholders);
        Component bottom = subtitle == null ? Component.empty() : renderLine(subtitle, placeholders);
        return Title.title(top, bottom, Title.DEFAULT_TIMES);
    }

    /**
     * The Adventure sound for a Bukkit one, by its namespaced key.
     *
     * <p>By key, never by enum name: {@code BLOCK_NOTE_BLOCK_CHIME} lowercased and dotted is
     * {@code block.note.block.chime}, which the client ignores without a word; the key is
     * {@code minecraft:block.note_block.chime}. {@code MASTER} so a health reminder is not
     * muted along with the players slider.
     */
    static net.kyori.adventure.sound.Sound renderSound(Sound sound) {
        NamespacedKey key = sound.getKey();
        return net.kyori.adventure.sound.Sound.sound(Key.key(key.getNamespace(), key.getKey()),
                net.kyori.adventure.sound.Sound.Source.MASTER, 1f, 1f);
    }

    /**
     * Every value as an unparsed placeholder, and never a parsed one.
     *
     * <p>A player named {@code </bold><click:run_command:'/op me'>boom} becomes a live
     * {@code /op me} click through {@code Placeholder.parsed}, and through splicing the name
     * into the string before parsing. Through {@code unparsed} it is text.
     */
    private static TagResolver resolver(Placeholders placeholders) {
        Map<String, String> values = placeholders.values();
        if (values.isEmpty()) {
            return TagResolver.empty();
        }
        List<TagResolver> resolvers = new ArrayList<>(values.size());
        values.forEach((key, value) -> resolvers.add(Placeholder.unparsed(key, value)));
        return TagResolver.resolver(resolvers);
    }

    private Audience audienceFor(Player player) {
        if (injectedAudiences != null) {
            return injectedAudiences.apply(player);
        }
        BukkitAudiences open = audiences;
        return open == null ? null : open.player(player);
    }

    private Audience senderAudience(CommandSender sender) {
        if (injectedAudiences != null) {
            return injectedAudiences.apply(sender);
        }
        BukkitAudiences open = audiences;
        return open == null ? null : open.sender(sender);
    }

    private Audience consoleAudience() {
        BukkitAudiences open = audiences;
        return open == null ? null : open.console();
    }

    private void deliverChat(Audience audience, String miniMessage, Placeholders placeholders) {
        audience.sendMessage(renderChat(miniMessage, placeholders));
    }

    private void deliverActionBar(Audience audience, String miniMessage, Placeholders placeholders) {
        audience.sendActionBar(renderLine(miniMessage, placeholders));
    }

    private void deliverTitle(Audience audience, String title, String subtitle,
                              Placeholders placeholders) {
        audience.showTitle(renderTitle(title, subtitle, placeholders));
    }

    private void deliverSound(Audience audience, Sound sound) {
        audience.playSound(renderSound(sound));
    }
}
