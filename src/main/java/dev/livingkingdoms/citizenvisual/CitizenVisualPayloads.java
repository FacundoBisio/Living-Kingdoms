package dev.livingkingdoms.citizenvisual;

import dev.livingkingdoms.profession.domain.ProfessionType;
import dev.livingkingdoms.profession.domain.WorkState;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import java.util.UUID;
import java.util.function.Consumer;

/** A separate bounded wire format keeps rendering independent of the management screens. */
public final class CitizenVisualPayloads {
    private CitizenVisualPayloads() {}
    public static Consumer<Snapshot> receiver = ignored -> {};
    public static Consumer<Remove> removeReceiver = ignored -> {};
    public record Snapshot(CitizenPresentation data, boolean workPulse, boolean openScreen) implements CustomPacketPayload {
        public static final Type<Snapshot> TYPE = new Type<>(ResourceLocation.parse("livingkingdoms:citizen_visual"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Snapshot> CODEC = StreamCodec.of((buf, value) -> {
            var d = value.data;
            buf.writeVarInt(d.entityId()); buf.writeUUID(d.entityUuid()); buf.writeUtf(d.name(), 80);
            buf.writeVarInt(d.level()); buf.writeEnum(d.profession()); buf.writeVarInt(d.professionLevel());
            buf.writeEnum(d.state()); buf.writeBoolean(d.active()); buf.writeBoolean(d.travelling());
            buf.writeBoolean(d.housed()); buf.writeUtf(d.workplace(), 48); buf.writeVarLong(d.experience());
            buf.writeUtf(d.target(), 48);
            buf.writeBoolean(value.workPulse); buf.writeBoolean(value.openScreen);
        }, buf -> new Snapshot(new CitizenPresentation(buf.readVarInt(), buf.readUUID(), buf.readUtf(80),
                buf.readVarInt(), buf.readEnum(ProfessionType.class), buf.readVarInt(), buf.readEnum(WorkState.class),
                buf.readBoolean(), buf.readBoolean(), buf.readBoolean(), buf.readUtf(48), buf.readVarLong(), buf.readUtf(48)),
                buf.readBoolean(), buf.readBoolean()));
        @Override public Type<Snapshot> type() { return TYPE; }
    }
    public record Remove(UUID entityUuid) implements CustomPacketPayload {
        public static final Type<Remove> TYPE = new Type<>(ResourceLocation.parse("livingkingdoms:citizen_visual_remove"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Remove> CODEC = StreamCodec.of(
                (buf, value) -> buf.writeUUID(value.entityUuid), buf -> new Remove(buf.readUUID()));
        @Override public Type<Remove> type() { return TYPE; }
    }
    public static void register(RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar("2");
        registrar.playToClient(Snapshot.TYPE, Snapshot.CODEC, (payload, context) -> receiver.accept(payload));
        registrar.playToClient(Remove.TYPE, Remove.CODEC, (payload, context) -> removeReceiver.accept(payload));
    }
}
