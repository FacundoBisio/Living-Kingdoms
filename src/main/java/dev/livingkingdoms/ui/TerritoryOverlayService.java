package dev.livingkingdoms.ui;

import dev.livingkingdoms.settlement.domain.Settlement;
import dev.livingkingdoms.settlement.persistence.SettlementSavedData;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import java.util.Optional;

/** Explicit request only: metadata lookup and one client snapshot, with no entity/chunk reads. */
public final class TerritoryOverlayService {
    public static final int NEAREST_DISTANCE=512;
    public static final int DURATION_TICKS=1200;
    private TerritoryOverlayService() {}

    public static Optional<Settlement> resolve(ServerPlayer player) {
        var data=SettlementSavedData.get(player.server);
        var pos=player.blockPosition(); String dimension=player.level().dimension().location().toString();
        return data.alliedAt(dimension,pos.getX(),pos.getZ())
                .or(() -> data.nearestAllied(dimension,pos.getX(),pos.getZ(),NEAREST_DISTANCE));
    }
    public static CompoundTag snapshot(Settlement settlement) {
        var territory=settlement.territory(); var tag=new CompoundTag();
        tag.putUUID("settlement",settlement.id()); tag.putString("name",VillageNames.display(settlement));
        tag.putString("dimension",territory.dimension());
        tag.putInt("x",territory.x()); tag.putInt("y",territory.y()); tag.putInt("z",territory.z());
        tag.putInt("radius",territory.radius()); tag.putInt("duration",DURATION_TICKS);
        return tag;
    }
    public static void show(ServerPlayer player,Settlement settlement) {
        PacketDistributor.sendToPlayer(player,new UiPayloads.TerritoryOverlay(snapshot(settlement)));
    }
    public static void clear(ServerPlayer player) {
        PacketDistributor.sendToPlayer(player,new UiPayloads.TerritoryOverlay(new CompoundTag()));
    }
}
