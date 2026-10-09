package dev.livingkingdoms.profession.domain;

import dev.livingkingdoms.citizen.domain.Housing;
import dev.livingkingdoms.structure.*;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Rotation;
import java.util.*;

/** Capabilities come from building policy, never from Citizen entity classes. Bounds are persisted metadata. */
public record FunctionalBuilding(UUID id,UUID settlementId,String dimension,BuildingKind kind,
        ResourceLocation template,BlockPos origin,Rotation rotation,PlotBounds bounds,BlockPos entrance,
        int workplaceSlots,boolean active) {
    public FunctionalBuilding {
        Objects.requireNonNull(id); Objects.requireNonNull(settlementId); ResourceLocation.parse(dimension);
        Objects.requireNonNull(kind); Objects.requireNonNull(template); Objects.requireNonNull(origin);
        Objects.requireNonNull(rotation); Objects.requireNonNull(bounds); Objects.requireNonNull(entrance);
        if(workplaceSlots<0 || workplaceSlots>128 || bounds.maxX()-bounds.minX()>14 || bounds.maxZ()-bounds.minZ()>14
                || bounds.minX()!=origin.getX() || bounds.minZ()!=origin.getZ()) throw new IllegalArgumentException("Invalid workplace");
    }
    public static FunctionalBuilding from(UUID settlement,String dimension,SettlementLayoutMetadata.Building b,int slots) {
        return new FunctionalBuilding(Housing.identity(settlement,b),settlement,dimension,b.kind(),b.template(),b.origin(),b.rotation(),b.bounds(),b.entrance(),slots,true);
    }
    public Set<BuildingCapability> capabilities() {
        return switch(kind) {
            case HOUSE,HOUSE_VARIANT,HOUSE_THIRD -> Set.of(BuildingCapability.HOUSING);
            case CORE,TOWN_HALL -> Set.of(BuildingCapability.ADMINISTRATION);
            case FARM -> Set.of(BuildingCapability.FARMER_WORKPLACE,BuildingCapability.FOOD_PRODUCTION);
            case BARRACKS -> Set.of(BuildingCapability.GUARD_WORKPLACE);
            case BLACKSMITH -> Set.of(BuildingCapability.BLACKSMITH_WORKPLACE);
            default -> Set.of();
        };
    }
    public boolean containsCrop(BlockPos pos) {
        return kind==BuildingKind.FARM && bounds.contains(pos.getX(),pos.getZ())
                && pos.getY()>origin.getY() && pos.getY()<=origin.getY()+3;
    }
    public int cropVolume() { return (bounds.maxX()-bounds.minX()+1)*(bounds.maxZ()-bounds.minZ()+1)*3; }
    public BlockPos cropPosition(int index) {
        int width=bounds.maxX()-bounds.minX()+1,depth=bounds.maxZ()-bounds.minZ()+1;
        int i=Math.floorMod(index,cropVolume());
        return new BlockPos(bounds.minX()+i%width,origin.getY()+1+i/(width*depth),bounds.minZ()+(i/width)%depth);
    }
}
