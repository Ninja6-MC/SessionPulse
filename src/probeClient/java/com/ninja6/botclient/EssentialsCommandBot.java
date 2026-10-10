package com.ninja6.botclient;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import org.geysermc.mcprotocollib.network.Session;
import org.geysermc.mcprotocollib.network.event.session.DisconnectedEvent;
import org.geysermc.mcprotocollib.network.event.session.SessionAdapter;
import org.geysermc.mcprotocollib.network.factory.ClientNetworkSessionFactory;
import org.geysermc.mcprotocollib.network.packet.Packet;
import org.geysermc.mcprotocollib.protocol.MinecraftProtocol;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundLoginPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundSystemChatPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.player.ClientboundPlayerPositionPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.ServerboundChatCommandPacket;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Loopback-only, bounded command bridge for the disposable Essentials acceptance server. */
public final class EssentialsCommandBot {
    private EssentialsCommandBot() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 2 || !args[1].matches("SPX(Auto|Fallback|Manual)")) {
            throw new IllegalArgumentException("Expected fixture port and disposable player name");
        }
        int port = Integer.parseInt(args[0]);
        if (port < 1 || port > 65535) throw new IllegalArgumentException("Invalid port");
        CountDownLatch finished = new CountDownLatch(1);
        AtomicBoolean joined = new AtomicBoolean();
        var protocol = new MinecraftProtocol(args[1]);
        System.out.println("PROTOCOL " + protocol.getCodec().getMinecraftVersion() + " " + protocol.getCodec().getProtocolVersion());
        var session = ClientNetworkSessionFactory.factory().setAddress("127.0.0.1", port)
                .setProtocol(protocol).create();
        session.addListener(new SessionAdapter() {
            @Override
            public void packetReceived(Session connection, Packet packet) {
                if (packet instanceof ClientboundLoginPacket) joined.set(true);
                if (packet instanceof ClientboundPlayerPositionPacket position) {
                    connection.send(TeleportAcknowledgement.packet(position));
                    if (joined.compareAndSet(true, false)) System.out.println("READY");
                } else if (packet instanceof ClientboundSystemChatPacket chat && !chat.isOverlay()) {
                    String text = plain(chat.getContent());
                    if (text.matches("SPX[AB]:(ONE|TWO|You do not have permission to do that\\.|Your counted window: [0-9]+\\.[0-9]h \\([0-9]+ min\\)\\. Lifetime: [0-9]+\\.[0-9]h\\.)")) System.out.println("MESSAGE " + text);
                }
            }

            @Override
            public void disconnected(DisconnectedEvent event) {
                System.out.println("CLOSED");
                finished.countDown();
            }
        });
        session.connect();
        Thread commands = new Thread(() -> {
            try (var input = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = input.readLine()) != null) {
                    if (line.equals("QUIT")) break;
                    if (!line.equals("essentials:afk") && !line.equals("spulse time") && !line.equals("spulse time SPXAuto")) {
                        System.out.println("INVALID_COMMAND");
                        break;
                    }
                    session.send(new ServerboundChatCommandPacket(line));
                }
            } catch (Exception ignored) {
                System.out.println("COMMAND_STREAM_FAILED");
            } finally {
                session.disconnect("fixture complete");
            }
        }, "essentials-fixture-commands");
        commands.setDaemon(true);
        commands.start();
        finished.await(15, TimeUnit.MINUTES);
        session.disconnect("fixture timeout");
        System.exit(0);
    }

    private static String plain(Component component) {
        StringBuilder text = new StringBuilder();
        if (component instanceof TextComponent literal) text.append(literal.content());
        for (Component child : component.children()) text.append(plain(child));
        return text.toString();
    }
}
