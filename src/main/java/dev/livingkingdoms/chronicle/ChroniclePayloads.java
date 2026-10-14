package dev.livingkingdoms.chronicle;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import java.util.function.Consumer;

/** Small personal S2C fact; no common code depends on client rendering classes. */
public final class ChroniclePayloads {
    private ChroniclePayloads() {}
    public static Consumer<Notice> receiver = ignored -> {};
    public record Notice(ChronicleEvent event, String kingdomName) implements CustomPacketPayload {
        public Notice {
            java.util.Objects.requireNonNull(event); java.util.Objects.requireNonNull(kingdomName);
            if (kingdomName.length() > 128) throw new IllegalArgumentException("Invalid kingdom name");
        }
        public static final Type<Notice> TYPE = new Type<>(ResourceLocation.parse("livingkingdoms:kingdom_alert"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Notice> CODEC = StreamCodec.of((buf, notice) -> {
            var e = notice.event(); buf.writeUUID(e.id()); buf.writeUUID(e.settlement()); buf.writeEnum(e.kind());
            buf.writeVarLong(e.tick()); buf.writeUtf(e.receipt(), 160); buf.writeUtf(e.subject(), 80); buf.writeUtf(notice.kingdomName(), 128);
        }, buf -> new Notice(new ChronicleEvent(buf.readUUID(), buf.readUUID(), buf.readEnum(ChronicleEvent.Kind.class),
                buf.readVarLong(), buf.readUtf(160), buf.readUtf(80)), buf.readUtf(128)));
        @Override public Type<Notice> type() { return TYPE; }
    }
    public static void register(RegisterPayloadHandlersEvent event) {
        event.registrar("1").playToClient(Notice.TYPE, Notice.CODEC, (payload, context) -> receiver.accept(payload));
    }
}
