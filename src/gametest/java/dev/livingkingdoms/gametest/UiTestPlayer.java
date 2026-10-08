package dev.livingkingdoms.gametest;

import com.mojang.authlib.GameProfile;
import dev.livingkingdoms.ui.UiPayloads;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.neoforged.neoforge.common.util.FakePlayer;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Records the real outbound payload path, without introducing production test hooks. */
class UiTestPlayer extends FakePlayer {
    final List<CompoundTag> snapshots = new ArrayList<>();
    UiTestPlayer(ServerLevel level) {
        super(level, new GameProfile(UUID.randomUUID(), "VillageUiTest"));
        Connection wire = new Connection(PacketFlow.SERVERBOUND) {
            @Override public void setListenerForServerboundHandshake(PacketListener listener) {}
        };
        connection = new ServerGamePacketListenerImpl(level.getServer(), wire, this, CommonListenerCookie.createInitial(getGameProfile(), false)) {
            @Override public void send(Packet<?> packet) {
                if (packet instanceof ClientboundCustomPayloadPacket custom && custom.payload() instanceof UiPayloads.Snapshot payload)
                    snapshots.add(payload.data().copy());
            }
        };
    }
}
