package dev.livingkingdoms.ui;

import dev.livingkingdoms.LivingKingdoms;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

import java.util.UUID;
import java.util.function.Consumer;

/** One bounded server snapshot; client actions contain only a session, quest identity and enum. */
public final class UiPayloads {
    private UiPayloads() {}
    public static Consumer<Snapshot> clientReceiver = ignored -> {};
    public enum Action { ACCEPT, CLAIM, TALK, BOARD, INFO, CLOSE, REFRESH }

    public record Snapshot(CompoundTag data) implements CustomPacketPayload {
        public static final Type<Snapshot> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(LivingKingdoms.MOD_ID, "village_ui"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Snapshot> CODEC = StreamCodec.of(
                (buf, value) -> buf.writeNbt(value.data), buf -> new Snapshot(java.util.Objects.requireNonNull(buf.readNbt())));
        @Override public Type<Snapshot> type() { return TYPE; }
    }

    public record Request(UUID session, UUID quest, Action action) implements CustomPacketPayload {
        public static final Type<Request> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(LivingKingdoms.MOD_ID, "village_action"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Request> CODEC = StreamCodec.of(
                (buf, value) -> { buf.writeUUID(value.session); buf.writeUUID(value.quest); buf.writeEnum(value.action); },
                buf -> new Request(buf.readUUID(), buf.readUUID(), buf.readEnum(Action.class)));
        @Override public Type<Request> type() { return TYPE; }
    }

    public static void register(RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar("1");
        registrar.playToClient(Snapshot.TYPE, Snapshot.CODEC, (payload, context) -> clientReceiver.accept(payload));
        registrar.playToServer(Request.TYPE, Request.CODEC, (payload, context) -> {
            if (context.player() instanceof ServerPlayer player) VillageUiService.handle(player, payload);
        });
    }
}
