package com.ninja6.botclient;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.TranslatableComponent;
import org.geysermc.mcprotocollib.network.ClientSession;
import org.geysermc.mcprotocollib.network.Session;
import org.geysermc.mcprotocollib.network.event.session.DisconnectedEvent;
import org.geysermc.mcprotocollib.network.event.session.SessionAdapter;
import org.geysermc.mcprotocollib.network.factory.ClientNetworkSessionFactory;
import org.geysermc.mcprotocollib.network.packet.Packet;
import org.geysermc.mcprotocollib.protocol.MinecraftProtocol;
import org.geysermc.mcprotocollib.protocol.data.game.level.sound.Sound;
import org.geysermc.mcprotocollib.protocol.packet.common.clientbound.ClientboundDisconnectPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundLoginPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundSoundEntityPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundSystemChatPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.player.ClientboundPlayerPositionPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.level.ClientboundSoundPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.title.ClientboundSetActionBarTextPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.title.ClientboundSetSubtitleTextPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.title.ClientboundSetTitleTextPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.level.ServerboundAcceptTeleportationPacket;
import org.geysermc.mcprotocollib.protocol.packet.login.clientbound.ClientboundLoginDisconnectPacket;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A real Minecraft client that joins, stands still, and writes down every player-facing
 * thing SessionPulse sends it. Never shipped.
 *
 * <p>SessionPulse logs nothing when a milestone fires, when enforcement kicks, or when a
 * cooldown refuses a login - the player is the audience, not the console. So the only
 * honest proof that those paths work on a live server is a player receiving them, and this
 * is that player. The smoke script greps the lines printed here; this process asserts
 * nothing itself.
 *
 * <p>Connects in offline mode, so the server creates a genuine {@code Player}, fires the
 * join and pre-login events, and runs every listener the plugin registered - including the
 * player facets of adventure-platform-bukkit, which a boot with nobody online never
 * touches.
 *
 * <p>Modelled on SpiralGenesis's GateProbeBot. It does not walk: SessionPulse counts
 * seconds online and AFK detection is switched off in the smoke config, so movement would
 * prove nothing. Keep-alive is answered by MCProtocolLib's own ClientListener; teleports
 * are acknowledged here, because a client that never confirms its spawn teleport is one
 * the server treats as not yet in the world.
 *
 * <p>Output, one line per event, every text plain (markup would mean it was sent
 * unrendered, and the script checks for that):
 * <pre>
 * BOT joined
 * BOT chat overlay=&lt;bool&gt; text=&lt;plain&gt;
 * BOT actionbar text=&lt;plain&gt;
 * BOT title text=&lt;plain&gt;
 * BOT subtitle text=&lt;plain&gt;
 * BOT sound key=&lt;namespace:path&gt;
 * BOT disconnect phase=&lt;login|game&gt; reason=&lt;plain&gt;
 * BOT closed
 * </pre>
 */
public final class SessionPulseProbeBot {

    private SessionPulseProbeBot() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 4) {
            System.err.println("usage: SessionPulseProbeBot <host> <port> <username> <seconds>");
            System.exit(2);
        }
        String host = args[0];
        int port = Integer.parseInt(args[1]);
        String username = args[2];
        long seconds = Long.parseLong(args[3]);

        AtomicBoolean inGame = new AtomicBoolean();
        AtomicBoolean reportedDisconnect = new AtomicBoolean();
        CountDownLatch finished = new CountDownLatch(1);

        MinecraftProtocol protocol = new MinecraftProtocol(username);
        ClientSession session = ClientNetworkSessionFactory.factory()
                .setAddress(host, port)
                .setProtocol(protocol)
                .create();

        session.addListener(new SessionAdapter() {
            @Override
            public void packetReceived(Session s, Packet packet) {
                if (packet instanceof ClientboundLoginPacket) {
                    inGame.set(true);
                    report("joined");
                } else if (packet instanceof ClientboundPlayerPositionPacket pos) {
                    s.send(new ServerboundAcceptTeleportationPacket(pos.getId()));
                } else if (packet instanceof ClientboundSystemChatPacket chat) {
                    // overlay=true is the legacy action-bar route. It is logged so a change in
                    // how the server routes the action bar is visible, but the script only
                    // ever counts overlay=false as the chat line.
                    report("chat overlay=" + chat.isOverlay() + " text=" + plain(chat.getContent()));
                } else if (packet instanceof ClientboundSetActionBarTextPacket bar) {
                    report("actionbar text=" + plain(bar.getText()));
                } else if (packet instanceof ClientboundSetTitleTextPacket title) {
                    report("title text=" + plain(title.getText()));
                } else if (packet instanceof ClientboundSetSubtitleTextPacket subtitle) {
                    report("subtitle text=" + plain(subtitle.getText()));
                } else if (packet instanceof ClientboundSoundPacket sound) {
                    report("sound key=" + key(sound.getSound()));
                } else if (packet instanceof ClientboundSoundEntityPacket sound) {
                    // Adventure's playSound(Sound, Emitter.self()) takes this route rather than
                    // the positional packet, so both count.
                    report("sound key=" + key(sound.getSound()));
                } else if (packet instanceof ClientboundLoginDisconnectPacket d) {
                    // Login phase: AsyncPlayerPreLoginEvent#disallow, the cooldown refusal.
                    reportedDisconnect.set(true);
                    report("disconnect phase=login reason=" + plain(d.getReason()));
                } else if (packet instanceof ClientboundDisconnectPacket d) {
                    // Configuration or play phase. Only play counts as a game kick; a kick
                    // before ClientboundLoginPacket is reported as login, which the script
                    // then refuses to accept as the enforcement kick.
                    reportedDisconnect.set(true);
                    report("disconnect phase=" + (inGame.get() ? "game" : "login")
                            + " reason=" + plain(d.getReason()));
                }
            }

            @Override
            public void disconnected(DisconnectedEvent event) {
                finished.countDown();
            }
        });

        session.connect();

        // Stays connected for the requested time, or until the server ends the session
        // first - a kick or a refusal - whichever comes first.
        finished.await(seconds, TimeUnit.SECONDS);
        if (finished.getCount() > 0) {
            session.disconnect("done");
            finished.await(10, TimeUnit.SECONDS);
        }
        report("closed");
        // Netty keeps non-daemon threads alive; nothing here needs a graceful pool shutdown.
        System.exit(0);
    }

    /**
     * Vanilla sounds arrive as BuiltinSound, whose name carries no namespace, and anything
     * the registry does not know arrives as CustomSound with its key as sent. Both are
     * normalised to namespace:path so the script matches one exact string.
     */
    private static String key(Sound sound) {
        String name = sound == null ? "null" : sound.getName();
        return name.indexOf(':') >= 0 ? name : "minecraft:" + name;
    }

    /**
     * Flattens a component to its text, depth first. Hand-rolled rather than taking the
     * plain serializer so the fixture adds no dependency beyond MCProtocolLib; a
     * translatable component contributes its key, which is enough to see it was not ours.
     */
    private static String plain(Component component) {
        StringBuilder out = new StringBuilder();
        append(out, component);
        return out.toString().replace('\n', ' ');
    }

    private static void append(StringBuilder out, Component component) {
        if (component == null) {
            return;
        }
        if (component instanceof TextComponent text) {
            out.append(text.content());
        } else if (component instanceof TranslatableComponent translatable) {
            out.append(translatable.key());
        }
        for (Component child : component.children()) {
            append(out, child);
        }
    }

    /** One line per event, prefixed so the smoke script can grep it. */
    private static void report(String message) {
        System.out.println("BOT " + message);
        System.out.flush();
    }
}
