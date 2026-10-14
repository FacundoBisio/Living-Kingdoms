package dev.livingkingdoms.ui;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import java.util.UUID;
import java.util.function.Consumer;

public final class ManagementPayloads {
    private ManagementPayloads() {}
    public static Consumer<CompoundTag> receiver=ignored->{};
    public enum Action { UPGRADE, REPAIR, EQUIP, REPAIR_HELD, EXPAND, REFRESH, BACK, CLOSE }
    public record Request(UUID session,long revision,UUID target,Action action) implements CustomPacketPayload {
        public static final Type<Request> TYPE=new Type<>(ResourceLocation.parse("livingkingdoms:management_action"));
        public static final StreamCodec<RegistryFriendlyByteBuf,Request> CODEC=StreamCodec.of(
                (b,p)->{b.writeUUID(p.session);b.writeLong(p.revision);b.writeUUID(p.target);b.writeEnum(p.action);},
                b->new Request(b.readUUID(),b.readLong(),b.readUUID(),b.readEnum(Action.class)));
        @Override public Type<Request> type() {return TYPE;}
    }
    public record Snapshot(CompoundTag data) implements CustomPacketPayload {
        public static final Type<Snapshot> TYPE=new Type<>(ResourceLocation.parse("livingkingdoms:management"));
        public static final StreamCodec<RegistryFriendlyByteBuf,Snapshot> CODEC=StreamCodec.of((b,p)->b.writeNbt(p.data),b->new Snapshot(java.util.Objects.requireNonNull(b.readNbt())));
        @Override public Type<Snapshot> type() {return TYPE;}
    }
    public static void register(RegisterPayloadHandlersEvent event) {
        var registrar=event.registrar("1");
        registrar.playToClient(Snapshot.TYPE,Snapshot.CODEC,(p,c)->receiver.accept(p.data));
        registrar.playToServer(Request.TYPE,Request.CODEC,(p,c)->{if(c.player() instanceof ServerPlayer player) ManagementService.handle(player,p);});
    }
}
