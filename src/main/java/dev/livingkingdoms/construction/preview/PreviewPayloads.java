package dev.livingkingdoms.construction.preview;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Rotation;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import java.util.UUID;
import java.util.function.Consumer;

public final class PreviewPayloads {
    private PreviewPayloads() {}
    public static Consumer<CompoundTag> receiver = ignored -> {};
    public enum Action { ROTATE, MOVE, CONFIRM, CANCEL }
    public record Snapshot(CompoundTag data) implements CustomPacketPayload {
        public static final Type<Snapshot> TYPE = new Type<>(ResourceLocation.parse("livingkingdoms:construction_preview"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Snapshot> CODEC = StreamCodec.of(
                (b,p) -> b.writeNbt(p.data), b -> new Snapshot(java.util.Objects.requireNonNull(b.readNbt())));
        @Override public Type<Snapshot> type() { return TYPE; }
    }
    public record Request(UUID session, Action action, BlockPos origin, Rotation rotation) implements CustomPacketPayload {
        public static final Type<Request> TYPE = new Type<>(ResourceLocation.parse("livingkingdoms:construction_preview_action"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Request> CODEC = StreamCodec.of(
                (b,p) -> { b.writeUUID(p.session); b.writeEnum(p.action); b.writeBlockPos(p.origin); b.writeEnum(p.rotation); },
                b -> new Request(b.readUUID(), b.readEnum(Action.class), b.readBlockPos(), b.readEnum(Rotation.class)));
        @Override public Type<Request> type() { return TYPE; }
    }
    public static void register(RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar("1");
        registrar.playToClient(Snapshot.TYPE, Snapshot.CODEC, (p,c) -> receiver.accept(p.data));
        registrar.playToServer(Request.TYPE, Request.CODEC, (p,c) -> {
            if (c.player() instanceof ServerPlayer player) ConstructionPreviewService.handle(player,p);
        });
    }
}
