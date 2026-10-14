package dev.livingkingdoms.commerce;

import dev.livingkingdoms.economy.StockResource;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import java.util.UUID;
import java.util.function.Consumer;

public final class CommercePayloads {
    public static Consumer<Snapshot> clientReceiver=ignored->{};
    public enum Action { BUY,SELL,REFRESH,CLOSE }
    public record Request(UUID session,UUID offer,StockResource resource,Action action) implements CustomPacketPayload {
        public static final Type<Request> TYPE=new Type<>(ResourceLocation.parse("livingkingdoms:commerce_action"));
        public static final StreamCodec<RegistryFriendlyByteBuf,Request> CODEC=StreamCodec.of((b,v)->{b.writeUUID(v.session);b.writeUUID(v.offer);b.writeEnum(v.resource);b.writeEnum(v.action);},
                b->new Request(b.readUUID(),b.readUUID(),b.readEnum(StockResource.class),b.readEnum(Action.class)));
        @Override public Type<Request> type(){return TYPE;}
    }
    public record Snapshot(CompoundTag data) implements CustomPacketPayload {
        public static final Type<Snapshot> TYPE=new Type<>(ResourceLocation.parse("livingkingdoms:commerce"));
        public static final StreamCodec<RegistryFriendlyByteBuf,Snapshot> CODEC=StreamCodec.of((b,v)->b.writeNbt(v.data),b->new Snapshot(java.util.Objects.requireNonNull(b.readNbt())));
        @Override public Type<Snapshot> type(){return TYPE;}
    }
    public static void register(RegisterPayloadHandlersEvent event) {
        var registrar=event.registrar("1");
        registrar.playToClient(Snapshot.TYPE,Snapshot.CODEC,(payload,context)->clientReceiver.accept(payload));
        registrar.playToServer(Request.TYPE,Request.CODEC,(payload,context)->{if(context.player() instanceof ServerPlayer player)CommerceService.handle(player,payload);});
    }
    private CommercePayloads() {}
}
