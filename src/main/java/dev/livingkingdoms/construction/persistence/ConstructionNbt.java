package dev.livingkingdoms.construction.persistence;

import dev.livingkingdoms.construction.domain.*;
import dev.livingkingdoms.quest.expansion.domain.ResourceKind;
import dev.livingkingdoms.structure.*;
import dev.livingkingdoms.settlement.domain.Territory;
import net.minecraft.core.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import java.util.*;

/** Full immutable blueprint including original terrain and native template, independent of later pack changes. */
public final class ConstructionNbt {
    private ConstructionNbt() {}
    public static CompoundTag writeProject(ConstructionProject p) {
        CompoundTag t = new CompoundTag();
        t.putUUID("id", p.id()); t.putUUID("settlement", p.settlementId()); t.putString("building", p.building().name());
        t.putString("state", p.state().name()); t.putLong("duration", p.durationTicks()); t.putLong("created", p.createdAt());
        t.putLong("started", p.startedAt()); t.putLong("completed", p.completedAt()); t.putBoolean("awaiting_chunks", p.awaitingChunks());
        t.putIntArray("plot", new int[]{p.plot().x(), p.plot().y(), p.plot().z()}); t.putString("rotation", p.plot().rotation().name());
        t.put("required", resources(p.required())); t.put("supplied", resources(p.supplied())); return t;
    }
    public static ConstructionProject readProject(CompoundTag t) {
        if (!t.hasUUID("id") || !t.hasUUID("settlement")) throw new IllegalArgumentException("Missing project identity");
        for (String k : List.of("building", "state", "rotation")) require(t,k,Tag.TAG_STRING);
        for (String k : List.of("duration", "created", "started", "completed")) require(t,k,Tag.TAG_LONG);
        require(t,"awaiting_chunks",Tag.TAG_BYTE);
        BlockPos pos = pos(t,"plot");
        return new ConstructionProject(t.getUUID("id"),t.getUUID("settlement"),BuildingKind.valueOf(t.getString("building")),
                new ConstructionProject.Plot(pos.getX(),pos.getY(),pos.getZ(),ConstructionProject.Orientation.valueOf(t.getString("rotation"))),
                ConstructionState.valueOf(t.getString("state")),resources(t,"required"),resources(t,"supplied"),
                t.getLong("duration"),t.getLong("created"),t.getLong("started"),t.getLong("completed"),t.getBoolean("awaiting_chunks"));
    }
    private static CompoundTag resources(Map<ResourceKind,Integer> values) {
        CompoundTag t = new CompoundTag(); values.forEach((k,v) -> t.putInt(k.name(),v)); return t;
    }
    private static Map<ResourceKind,Integer> resources(CompoundTag parent,String key) {
        require(parent,key,Tag.TAG_COMPOUND); CompoundTag t = parent.getCompound(key);
        Map<ResourceKind,Integer> map = new EnumMap<>(ResourceKind.class);
        for (String k : t.getAllKeys()) { require(t,k,Tag.TAG_INT); map.put(ResourceKind.valueOf(k),t.getInt(k)); } return map;
    }
    static CompoundTag writePlan(SettlementLayout plan) {
        plan.validateGeometry();
        if (plan.buildings().size() != 1) throw new IllegalArgumentException("One building per construction project");
        var b = plan.buildings().getFirst(); var territory = plan.territory(); CompoundTag t = new CompoundTag();
        t.putString("dimension",territory.dimension()); t.putIntArray("territory",new int[]{territory.x(),territory.y(),territory.z(),territory.radius()});
        t.putString("style",plan.style().name()); t.putString("kind",b.module().kind().name()); t.putString("template_id",b.module().id().toString());
        t.put("template",b.module().template().save(new CompoundTag())); t.putString("rotation",b.rotation().name());
        t.putLong("origin",b.origin().asLong()); t.putLong("entrance",b.entrance().asLong());
        t.putIntArray("bounds",new int[]{b.bounds().minX(),b.bounds().minZ(),b.bounds().maxX(),b.bounds().maxZ()});
        t.putLongArray("supports",b.supports().stream().mapToLong(BlockPos::asLong).toArray());
        t.put("before",states(plan.before())); t.put("paths",states(plan.pathBlocks())); return t;
    }
    static SettlementLayout readPlan(CompoundTag t,HolderLookup.Provider registries) {
        for (String k : List.of("dimension","style","kind","template_id","rotation")) require(t,k,Tag.TAG_STRING);
        require(t,"template",Tag.TAG_COMPOUND); require(t,"origin",Tag.TAG_LONG); require(t,"entrance",Tag.TAG_LONG);
        int[] area = ints(t,"territory",4), bounds = ints(t,"bounds",4);
        Territory territory = new Territory(t.getString("dimension"),area[0],area[1],area[2],area[3]);
        CompoundTag blueprint=t.getCompound("template"); validateTemplate(blueprint,registries);
        StructureTemplate nativeTemplate = new StructureTemplate(); nativeTemplate.load(registries.lookupOrThrow(Registries.BLOCK),blueprint);
        var module = BuildingTemplate.validate(BuildingKind.valueOf(t.getString("kind")),ResourceLocation.parse(t.getString("template_id")),nativeTemplate);
        require(t,"supports",Tag.TAG_LONG_ARRAY); long[] supports = t.getLongArray("supports");
        if (supports.length > 4096) throw new IllegalArgumentException("Too many supports");
        SettlementLayout plan = new SettlementLayout(territory,ArchitectureStyle.valueOf(t.getString("style")),List.of(new SettlementLayout.Building(module,
                BlockPos.of(t.getLong("origin")),Rotation.valueOf(t.getString("rotation")),new PlotBounds(bounds[0],bounds[1],bounds[2],bounds[3]),
                BlockPos.of(t.getLong("entrance")),Arrays.stream(supports).mapToObj(BlockPos::of).toList())),states(t,"paths",registries),states(t,"before",registries));
        plan.validateGeometry(); return plan;
    }
    private static ListTag states(Map<BlockPos,BlockState> map) {
        ListTag list = new ListTag(); map.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(e -> {
            CompoundTag t = new CompoundTag(); t.putLong("pos",e.getKey().asLong()); t.put("state",NbtUtils.writeBlockState(e.getValue())); list.add(t);
        }); return list;
    }
    private static Map<BlockPos,BlockState> states(CompoundTag parent,String key,HolderLookup.Provider registries) {
        ListTag list = compounds(parent,key,32768); Map<BlockPos,BlockState> result = new LinkedHashMap<>();
        for (int i=0;i<list.size();i++) {
            CompoundTag t = list.getCompound(i); require(t,"pos",Tag.TAG_LONG); require(t,"state",Tag.TAG_COMPOUND);
            CompoundTag state = t.getCompound("state"); validateState(state,registries);
            if (result.put(BlockPos.of(t.getLong("pos")),NbtUtils.readBlockState(registries.lookupOrThrow(Registries.BLOCK),state)) != null)
                throw new IllegalArgumentException("Duplicate blueprint position");
        } return result;
    }
    private static void validateTemplate(CompoundTag t,HolderLookup.Provider registries) {
        require(t,"size",Tag.TAG_LIST); var size=t.getList("size",Tag.TAG_INT);
        if(size.size()!=3 || size.getInt(0)<3 || size.getInt(0)>15 || size.getInt(1)<3 || size.getInt(1)>12 || size.getInt(2)<3 || size.getInt(2)>15)
            throw new IllegalArgumentException("Unsafe saved native template size");
        var palette=compounds(t,"palette",512); for(int i=0;i<palette.size();i++) validateState(palette.getCompound(i),registries);
        var blocks=compounds(t,"blocks",2700);
        for(int i=0;i<blocks.size();i++) {
            var block=blocks.getCompound(i); require(block,"state",Tag.TAG_INT); require(block,"pos",Tag.TAG_LIST); var pos=block.getList("pos",Tag.TAG_INT);
            if(block.getInt("state")<0 || block.getInt("state")>=palette.size() || pos.size()!=3) throw new IllegalArgumentException("Invalid saved template block");
            for(int axis=0;axis<3;axis++) if(pos.getInt(axis)<0 || pos.getInt(axis)>=size.getInt(axis)) throw new IllegalArgumentException("Saved template position outside size");
        }
        if(!compounds(t,"entities",0).isEmpty() || t.contains("palettes")) throw new IllegalArgumentException("Saved templates cannot contain entities or multiple palettes");
    }
    private static void validateState(CompoundTag state,HolderLookup.Provider registries) {
        require(state,"Name",Tag.TAG_STRING);
        var block=registries.lookupOrThrow(Registries.BLOCK).get(net.minecraft.resources.ResourceKey.create(Registries.BLOCK,ResourceLocation.parse(state.getString("Name"))))
                .orElseThrow(() -> new IllegalArgumentException("Unknown saved blueprint block")).value();
        if(state.contains("Properties")) {
            require(state,"Properties",Tag.TAG_COMPOUND); var properties=state.getCompound("Properties");
            for(String key:properties.getAllKeys()) {
                require(properties,key,Tag.TAG_STRING); var property=block.getStateDefinition().getProperty(key);
                if(property==null || property.getValue(properties.getString(key)).isEmpty()) throw new IllegalArgumentException("Invalid saved block property: "+key);
            }
        }
    }
    static void require(CompoundTag t,String k,int type) { if (!t.contains(k,type)) throw new IllegalArgumentException("Missing/invalid construction field: " + k); }
    static ListTag compounds(CompoundTag t,String k,int max) {
        require(t,k,Tag.TAG_LIST); ListTag l=(ListTag)t.get(k);
        if (l.size()>max || !l.isEmpty() && l.getElementType()!=Tag.TAG_COMPOUND) throw new IllegalArgumentException("Invalid construction list"); return l;
    }
    private static int[] ints(CompoundTag t,String k,int size) { require(t,k,Tag.TAG_INT_ARRAY); int[] a=t.getIntArray(k); if(a.length!=size) throw new IllegalArgumentException("Invalid position"); return a; }
    private static BlockPos pos(CompoundTag t,String k) { int[] a=ints(t,k,3); return new BlockPos(a[0],a[1],a[2]); }
}
