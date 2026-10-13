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

/** One bounded server snapshot; requests contain only a session, target identity and action. */
public final class UiPayloads {
    private UiPayloads() {}
    public static Consumer<Snapshot> clientReceiver = ignored -> {};
    public static Consumer<HudSnapshot> hudReceiver = ignored -> {};
    public static Consumer<TerritoryOverlay> territoryReceiver = ignored -> {};
    public enum Action { ACCEPT, CLAIM, TALK, BOARD, INFO, CLOSE, REFRESH, CONSTRUCTION, DEPOSIT, RETRY, PLAN,
        IMMIGRATION, ACCEPT_CITIZEN, DECLINE_CITIZEN, CITIZENS, ASSIGN_FARMER, REMOVE_PROFESSION, PLAN_FARM, ASSIGN_GUARD, PLAN_BARRACKS, PLAN_WATCHTOWER, ASSIGN_BUILDER, TRACK, UNTRACK }

    /** Personal, temporary metadata overlay. This packet never requests chunk contents. */
    public record TerritoryOverlay(CompoundTag data) implements CustomPacketPayload {
        public static final Type<TerritoryOverlay> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(LivingKingdoms.MOD_ID, "territory_overlay"));
        public static final StreamCodec<RegistryFriendlyByteBuf, TerritoryOverlay> CODEC = StreamCodec.of(
                (buf, value) -> buf.writeNbt(value.data), buf -> new TerritoryOverlay(java.util.Objects.requireNonNull(buf.readNbt())));
        @Override public Type<TerritoryOverlay> type() { return TYPE; }
    }

    /** Naming is bounded and carried separately from quest and management actions. */
    public record MayorRequest(UUID session, String action, String name) implements CustomPacketPayload {
        public static final Type<MayorRequest> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(LivingKingdoms.MOD_ID, "mayor_onboarding"));
        public static final StreamCodec<RegistryFriendlyByteBuf, MayorRequest> CODEC = StreamCodec.of(
                (buf, value) -> { buf.writeUUID(value.session); buf.writeUtf(value.action, 16); buf.writeUtf(value.name, 48); },
                buf -> new MayorRequest(buf.readUUID(), buf.readUtf(16), buf.readUtf(48)));
        @Override public Type<MayorRequest> type() { return TYPE; }
    }

    public record HudSnapshot(CompoundTag data) implements CustomPacketPayload {
        public static final Type<HudSnapshot> TYPE=new Type<>(ResourceLocation.fromNamespaceAndPath(LivingKingdoms.MOD_ID,"kingdom_hud"));
        public static final StreamCodec<RegistryFriendlyByteBuf,HudSnapshot> CODEC=StreamCodec.of(
                (buf,value) -> buf.writeNbt(value.data),buf -> new HudSnapshot(java.util.Objects.requireNonNull(buf.readNbt())));
        @Override public Type<HudSnapshot> type() { return TYPE; }
    }
    public record Shortcut(ShortcutAction action) implements CustomPacketPayload {
        public static final Type<Shortcut> TYPE=new Type<>(ResourceLocation.fromNamespaceAndPath(LivingKingdoms.MOD_ID,"kingdom_shortcut"));
        public static final StreamCodec<RegistryFriendlyByteBuf,Shortcut> CODEC=StreamCodec.of(
                (buf,value) -> buf.writeEnum(value.action),buf -> new Shortcut(buf.readEnum(ShortcutAction.class)));
        @Override public Type<Shortcut> type() { return TYPE; }
    }

    public record Snapshot(CompoundTag data) implements CustomPacketPayload {
        public static final Type<Snapshot> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(LivingKingdoms.MOD_ID, "village_ui"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Snapshot> CODEC = StreamCodec.of(
                (buf, value) -> buf.writeNbt(value.data), buf -> new Snapshot(java.util.Objects.requireNonNull(buf.readNbt())));
        @Override public Type<Snapshot> type() { return TYPE; }
    }

    /** The existing quest field is also the candidate/project target for its corresponding action. */
    public record Request(UUID session, UUID quest, Action action) implements CustomPacketPayload {
        public static final Type<Request> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(LivingKingdoms.MOD_ID, "village_action"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Request> CODEC = StreamCodec.of(
                (buf, value) -> { buf.writeUUID(value.session); buf.writeUUID(value.quest); buf.writeEnum(value.action); },
                buf -> new Request(buf.readUUID(), buf.readUUID(), buf.readEnum(Action.class)));
        @Override public Type<Request> type() { return TYPE; }
    }

    public static void register(RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar("10");
        registrar.playToClient(TerritoryOverlay.TYPE, TerritoryOverlay.CODEC, (payload, context) -> territoryReceiver.accept(payload));
        registrar.playToServer(MayorRequest.TYPE, MayorRequest.CODEC, (payload, context) -> {
            if (context.player() instanceof ServerPlayer player) VillageUiService.handleMayor(player, payload);
        });
        registrar.playToClient(HudSnapshot.TYPE,HudSnapshot.CODEC,(payload,context) -> hudReceiver.accept(payload));
        registrar.playToServer(Shortcut.TYPE,Shortcut.CODEC,(payload,context) -> {
            if(context.player() instanceof ServerPlayer player) QuestJournalService.shortcut(player,payload.action());
        });
        registrar.playToClient(Snapshot.TYPE, Snapshot.CODEC, (payload, context) -> clientReceiver.accept(payload));
        registrar.playToServer(Request.TYPE, Request.CODEC, (payload, context) -> {
            if (context.player() instanceof ServerPlayer player) VillageUiService.handle(player, payload);
        });
    }
}
