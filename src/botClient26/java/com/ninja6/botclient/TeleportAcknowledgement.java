package com.ninja6.botclient;

import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.player.ClientboundPlayerPositionPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.level.ServerboundAcceptTeleportationPacket;

/** The protocol-specific spawn acknowledgement, kept off the other fixture's classpath. */
final class TeleportAcknowledgement {
    private TeleportAcknowledgement() {
    }

    static ServerboundAcceptTeleportationPacket packet(ClientboundPlayerPositionPacket position) {
        return new ServerboundAcceptTeleportationPacket(position.getId(), position.getPosition().getX(), position.getPosition().getY(),
                position.getPosition().getZ(), position.getYRot(), position.getXRot());
    }
}
