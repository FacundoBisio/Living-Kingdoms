package dev.livingkingdoms.profession.persistence;

import dev.livingkingdoms.citizen.domain.*;
import dev.livingkingdoms.profession.domain.*;
import dev.livingkingdoms.progression.domain.LevelValue;
import dev.livingkingdoms.settlement.domain.Settlement;
import dev.livingkingdoms.structure.*;
import net.minecraft.core.*;
import net.minecraft.nbt.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.storage.*;
import java.nio.file.*;
import java.util.*;

/** Additional guarded store leaves 0.11 citizen/housing/immigration schemas and IDs intact. */
public final class ProfessionSavedData extends SavedData {
    public static final String DATA_NAME="livingkingdoms_professions";
    private final Map<UUID,Profession> professions=new LinkedHashMap<>();
    private final Map<UUID,FunctionalBuilding> buildings=new LinkedHashMap<>();
    private final Map<UUID,Set<UUID>> settlementBuildings=new HashMap<>(),settlementWorkers=new HashMap<>();
    private final Map<UUID,Set<UUID>> workplaceWorkers=new HashMap<>();
    private final Map<UUID,FoodStock> food=new LinkedHashMap<>();
    private record Career(int level,long xp) {
        Career { if(level<1 || level>100 || xp<0 || xp>1_000_000_000L) throw new IllegalArgumentException("Invalid career"); }
    }
    private final Map<UUID,Map<ProfessionType,Career>> careers=new LinkedHashMap<>();
    private final Map<UUID,Long> employmentRevisions=new HashMap<>();
    private Thread owner;

    public ProfessionSavedData() {}
    ProfessionSavedData(Thread owner) { this.owner=owner; }

    public static ProfessionSavedData get(MinecraftServer server) {
        if(!server.isSameThread()) throw new IllegalStateException("Professions require server thread");
        var data=getOrCreate(server.overworld().getDataStorage(),server.getWorldPath(LevelResource.ROOT).resolve("data"));
        data.owner=Thread.currentThread(); return data;
    }
    public static ProfessionSavedData getOrCreate(DimensionDataStorage storage,Path directory) {
        var data=storage.computeIfAbsent(new Factory<>(() -> {
            if(!Files.notExists(directory.resolve(DATA_NAME+".dat")))
                throw new IllegalStateException("Unreadable profession data; refusing to overwrite it");
            return new ProfessionSavedData();
        },ProfessionSavedData::load),DATA_NAME);
        return data;
    }
    private void authority() { if(owner!=null && owner!=Thread.currentThread()) throw new IllegalStateException("Professions require server thread"); }
    public long employmentRevision(UUID citizen) { authority(); return employmentRevisions.getOrDefault(citizen,0L); }
    public Optional<Profession> profession(UUID citizen) { authority(); return Optional.ofNullable(professions.get(citizen)); }
    public Optional<FunctionalBuilding> building(UUID id) { authority(); return Optional.ofNullable(buildings.get(id)); }
    public List<FunctionalBuilding> buildings(UUID settlement) { authority(); return settlementBuildings.getOrDefault(settlement,Set.of()).stream().map(buildings::get).toList(); }
    public List<Profession> professions(UUID settlement) { authority(); return settlementWorkers.getOrDefault(settlement,Set.of()).stream().map(professions::get).toList(); }
    public int workers(UUID workplace) { authority(); return workplaceWorkers.getOrDefault(workplace,Set.of()).size(); }
    public FoodStock food(UUID settlement,int capacity) { authority(); return food.getOrDefault(settlement,new FoodStock(0,capacity,0)); }
    public void ensureFood(UUID settlement,int capacity) {
        authority(); var old=food.get(settlement); var next=old==null?new FoodStock(0,capacity,0):old.capacity(capacity);
        if(!next.equals(old)) { food.put(settlement,next); setDirty(); }
    }
    public int addFood(UUID settlement,int units) {
        authority(); var old=Objects.requireNonNull(food.get(settlement),"Food store not initialized"); var next=old.add(units);
        if(!old.equals(next)) { food.put(settlement,next); setDirty(); } return next.stock()-old.stock();
    }
    public void initialize(Citizen citizen) {
        authority(); var existing=professions.get(citizen.id());
        if(existing!=null) {
            if(!existing.settlementId().equals(citizen.settlementId())) throw new IllegalArgumentException("Citizen profession belongs to another settlement");
            return;
        }
        if(professions.size()>=100000) throw new IllegalStateException("Too many profession records");
        var initial=Profession.initial(citizen.id(),citizen.settlementId(),citizen.role()==CitizenRole.MAYOR);
        put(citizen.state()==CitizenState.ACTIVE?initial:initial.retired());
    }
    private void put(Profession p) {
        var old=professions.put(p.citizenId(),p);
        if(old!=null && (old.type()!=p.type() || old.active()!=p.active() || !Objects.equals(old.workplaceId(),p.workplaceId())))
            employmentRevisions.put(p.citizenId(),Math.addExact(employmentRevision(p.citizenId()),1));
        if(careerType(p.type()))
            careers.computeIfAbsent(p.citizenId(),key -> new EnumMap<>(ProfessionType.class)).put(p.type(),new Career(p.level().value(),p.experience()));
        if(old!=null && old.active() && old.workplaceId()!=null) {
            var members=workplaceWorkers.get(old.workplaceId());
            if(members!=null) { members.remove(old.citizenId()); if(members.isEmpty()) workplaceWorkers.remove(old.workplaceId()); }
        }
        if(p.active() && p.workplaceId()!=null) workplaceWorkers.computeIfAbsent(p.workplaceId(),key -> new LinkedHashSet<>()).add(p.citizenId());
        settlementWorkers.computeIfAbsent(p.settlementId(),key -> new LinkedHashSet<>()).add(p.citizenId()); setDirty();
    }
    public void synchronize(Settlement settlement,SettlementLayoutMetadata layout,int farmSlots,List<Citizen> citizens) {
        synchronize(settlement,layout,farmSlots,0,citizens);
    }
    public void synchronize(Settlement settlement,SettlementLayoutMetadata layout,int farmSlots,int guardSlots,List<Citizen> citizens) {
        synchronize(settlement,layout,farmSlots,guardSlots,0,citizens);
    }
    public void synchronize(Settlement settlement,SettlementLayoutMetadata layout,int farmSlots,int guardSlots,int builderSlots,List<Citizen> citizens) {
        authority(); var desired=new LinkedHashMap<UUID,FunctionalBuilding>();
        if(layout!=null) for(var b:layout.buildings()) {
            int slots=switch(b.kind()) { case FARM -> farmSlots; case BARRACKS -> guardSlots; case CORE,TOWN_HALL -> builderSlots; default -> 0; };
            var functional=FunctionalBuilding.from(settlement.id(),settlement.territory().dimension(),b,slots);
            if(desired.put(functional.id(),functional)!=null) throw new IllegalArgumentException("Duplicate functional building");
        }
        // Converted vanilla villages use their existing administrative plaza, without placing or changing a structure.
        if(builderSlots>0 && settlement.provenance().origin()==dev.livingkingdoms.settlement.domain.SettlementOrigin.CONVERTED
                && desired.values().stream().noneMatch(b -> b.supports(ProfessionType.BUILDER))) {
            var t=settlement.territory(); var center=new BlockPos(t.x(),t.y(),t.z());
            var plaza=new SettlementLayoutMetadata.Building(BuildingKind.TOWN_HALL,ResourceLocation.parse("livingkingdoms:allied/converted/plaza"),
                    center,Rotation.NONE,new PlotBounds(t.x(),t.z(),t.x(),t.z()),center.east(2));
            var functional=FunctionalBuilding.from(settlement.id(),t.dimension(),plaza,builderSlots); desired.put(functional.id(),functional);
        }
        if(!buildings(settlement.id()).equals(List.copyOf(desired.values()))) {
            if((long)buildings.size()-buildings(settlement.id()).size()+desired.size()>65536) throw new IllegalStateException("Too many functional buildings");
            for(var b:buildings(settlement.id())) buildings.remove(b.id());
            buildings.putAll(desired); settlementBuildings.put(settlement.id(),new LinkedHashSet<>(desired.keySet())); setDirty();
        }
        var people=new HashMap<UUID,Citizen>(); citizens.forEach(c -> {initialize(c); people.put(c.id(),c);});
        var used=new HashMap<UUID,Integer>();
        for(var p:professions(settlement.id())) if(p.active() && careerType(p.type())) {
            var citizen=people.get(p.citizenId()); var b=buildings.get(p.workplaceId()); int count=used.getOrDefault(p.workplaceId(),0);
            if(citizen==null || citizen.state()!=CitizenState.ACTIVE || citizen.homeId()==null || b==null || !b.active()
                    || !b.supports(p.type()) || count>=b.workplaceSlots()) put(p.retired()); else used.put(b.id(),count+1);
        }
    }
    public boolean assignFarmer(Citizen citizen,UUID workplace,long now) {
        return assign(citizen,workplace,now,ProfessionType.FARMER);
    }
    public boolean assignGuard(Citizen citizen,UUID workplace,long now) {
        return assign(citizen,workplace,now,ProfessionType.GUARD);
    }
    public boolean assignBuilder(Citizen citizen,UUID workplace,long now) { return assign(citizen,workplace,now,ProfessionType.BUILDER); }
    private boolean assign(Citizen citizen,UUID workplace,long now,ProfessionType type) {
        authority(); initialize(citizen); var old=profession(citizen.id()).orElseThrow(); var b=buildings.get(workplace);
        if(now<0 || citizen.state()!=CitizenState.ACTIVE || citizen.homeId()==null || citizen.role()==CitizenRole.MAYOR
                || (type==ProfessionType.GUARD || type==ProfessionType.BUILDER) && old.type()!=ProfessionType.UNASSIGNED || old.type()==ProfessionType.MAYOR || old.active() && old.type()!=ProfessionType.UNASSIGNED
                || b==null || !b.active() || !b.supports(type) || !b.settlementId().equals(citizen.settlementId())
                || workers(workplace)>=b.workplaceSlots()) return false;
        var progress=careers.getOrDefault(citizen.id(),Map.of()).getOrDefault(type,
                new Career(1,0));
        put(new Profession(citizen.id(),citizen.settlementId(),type,new LevelValue(progress.level()),progress.xp(),workplace,true,
                WorkState.IDLE,now,0,0,old.traits())); return true;
    }
    public boolean removeFarmer(UUID citizen) {
        return remove(citizen,ProfessionType.FARMER);
    }
    public boolean removeGuard(UUID citizen) { return remove(citizen,ProfessionType.GUARD); }
    public boolean removeBuilder(UUID citizen) { return remove(citizen,ProfessionType.BUILDER); }
    private boolean remove(UUID citizen,ProfessionType type) {
        authority(); var old=professions.get(citizen);
        if(old==null || old.type()!=type) return false;
        put(new Profession(old.citizenId(),old.settlementId(),ProfessionType.UNASSIGNED,old.level(),old.experience(),null,false,
                WorkState.IDLE,old.nextWorkAt(),0,0,old.traits())); return true;
    }
    public boolean retire(UUID citizen) {
        authority(); var old=professions.get(citizen); if(old==null || !old.active()) return false; put(old.retired()); return true;
    }
    public boolean replace(Profession expected,Profession next) {
        authority(); if(!expected.citizenId().equals(next.citizenId()) || !expected.settlementId().equals(next.settlementId())) throw new IllegalArgumentException("Profession identity changed");
        if(!expected.equals(professions.get(expected.citizenId()))) return false;
        if(!next.equals(expected)) put(next); return true;
    }
    /** Work cooldown is consumed before native world writes. XP and resources commit once for the same receipt. */
    public boolean harvest(Profession expected,int units,int xp,int cap,int step) {
        authority(); if(expected.type()!=ProfessionType.FARMER || !expected.active() || !expected.equals(professions.get(expected.citizenId())) || units<0 || xp<1) return false;
        long experience=Math.min(1_000_000_000L,expected.experience()+xp);
        var next=new Profession(expected.citizenId(),expected.settlementId(),expected.type(),new LevelValue(FarmerProgression.level(experience,cap,step)),
                experience,expected.workplaceId(),true,WorkState.WORKING,expected.nextWorkAt(),expected.cropCursor(),0,expected.traits());
        addFood(expected.settlementId(),units); put(next); return true;
    }
    public boolean guardExperience(Profession expected,int xp,int cap,int step) {
        authority(); if(expected.type()!=ProfessionType.GUARD || !expected.active() || xp<1 || !expected.equals(professions.get(expected.citizenId()))) return false;
        long total=Math.min(1_000_000_000L,expected.experience()+xp);
        put(new Profession(expected.citizenId(),expected.settlementId(),expected.type(),new LevelValue(GuardPolicy.level(total,cap,step)),total,
                expected.workplaceId(),true,expected.workState(),expected.nextWorkAt(),expected.cropCursor(),expected.navigationFailures(),expected.traits())); return true;
    }
    /** The ConstructionProject milestone is the receipt; compare-and-set rejects stale/repeated reward calls. */
    public boolean builderExperience(Profession expected,int xp,int cap,int step) {
        authority(); if(expected.type()!=ProfessionType.BUILDER || !expected.active() || xp<1 || !expected.equals(professions.get(expected.citizenId()))) return false;
        long total=Math.min(1_000_000_000L,expected.experience()+xp);
        put(new Profession(expected.citizenId(),expected.settlementId(),expected.type(),new LevelValue(BuilderPolicy.level(total,cap,step)),total,
                expected.workplaceId(),true,expected.workState(),expected.nextWorkAt(),expected.cropCursor(),expected.navigationFailures(),expected.traits())); return true;
    }
    private static boolean careerType(ProfessionType type) { return type==ProfessionType.FARMER || type==ProfessionType.GUARD || type==ProfessionType.BUILDER; }
    public static ProfessionSavedData load(CompoundTag tag,HolderLookup.Provider registries) {
        require(tag,"schema_version",Tag.TAG_INT); int version=tag.getInt("schema_version"); if(version<1 || version>3) throw new IllegalArgumentException("Unsupported profession schema");
        var data=new ProfessionSavedData();
        for(var element:list(tag,"buildings",65536)) {
            var e=(CompoundTag)element; var origin=pos(e,"origin");
            var b=new FunctionalBuilding(uuid(e,"id"),uuid(e,"settlement"),string(e,"dimension"),BuildingKind.valueOf(string(e,"kind")),ResourceLocation.parse(string(e,"template")),
                    origin,Rotation.valueOf(string(e,"rotation")),new PlotBounds(origin.getX(),origin.getZ(),integer(e,"max_x"),integer(e,"max_z")),pos(e,"entrance"),integer(e,"slots"),bool(e,"active"));
            if(data.buildings.putIfAbsent(b.id(),b)!=null) throw new IllegalArgumentException("Duplicate workplace");
            data.settlementBuildings.computeIfAbsent(b.settlementId(),key -> new LinkedHashSet<>()).add(b.id());
        }
        for(var element:list(tag,"professions",100000)) {
            var e=(CompoundTag)element; Set<CitizenTrait> traits=new HashSet<>();
            for(var trait:list(e,"traits",4)) if(!traits.add(CitizenTrait.valueOf(string((CompoundTag)trait,"trait")))) throw new IllegalArgumentException("Duplicate trait");
            var p=new Profession(uuid(e,"citizen"),uuid(e,"settlement"),ProfessionType.valueOf(string(e,"type")),new LevelValue(integer(e,"level")),number(e,"xp"),
                    e.contains("workplace")?uuid(e,"workplace"):null,bool(e,"active"),WorkState.valueOf(string(e,"work_state")),number(e,"next_work"),integer(e,"cursor"),integer(e,"failures"),traits);
            if(data.professions.containsKey(p.citizenId())) throw new IllegalArgumentException("Duplicate profession");
            if(p.active() && p.workplaceId()!=null) {
                var b=data.buildings.get(p.workplaceId());
                if(b==null || !b.active() || !b.settlementId().equals(p.settlementId()) || !careerType(p.type())
                        || !b.supports(p.type()) || data.workers(b.id())>=b.workplaceSlots()) throw new IllegalArgumentException("Invalid workplace assignment");
            }
            data.put(p);
            if(version>=2) { long revision=number(e,"employment_revision"); if(revision<0) throw new IllegalArgumentException("Invalid employment revision"); data.employmentRevisions.put(p.citizenId(),revision); }
        }
        for(var element:list(tag,"food",65536)) {
            var e=(CompoundTag)element;
            if(data.food.putIfAbsent(uuid(e,"settlement"),new FoodStock(integer(e,"stock"),integer(e,"capacity"),number(e,"produced")))!=null) throw new IllegalArgumentException("Duplicate food stock");
        }
        if(version>=2) {
            var seen=new HashSet<String>();
            for(var element:list(tag,"careers",300000)) {
                var e=(CompoundTag)element; UUID id=uuid(e,"citizen"); var type=ProfessionType.valueOf(string(e,"type"));
                if(!data.professions.containsKey(id) || !careerType(type) || !seen.add(id+"/"+type)) throw new IllegalArgumentException("Invalid career owner");
                var progress=new Career(integer(e,"level"),number(e,"xp")); var current=data.professions.get(id);
                if(current.type()==type && (current.level().value()!=progress.level() || current.experience()!=progress.xp())) throw new IllegalArgumentException("Conflicting career progress");
                data.careers.computeIfAbsent(id,key -> new EnumMap<>(ProfessionType.class)).put(type,progress);
            }
        } else for(var current:data.professions.values()) if(current.type()==ProfessionType.UNASSIGNED && current.experience()>0)
            data.careers.computeIfAbsent(current.citizenId(),key -> new EnumMap<>(ProfessionType.class)).put(ProfessionType.FARMER,new Career(current.level().value(),current.experience()));
        data.setDirty(false); return data;
    }
    @Override public CompoundTag save(CompoundTag tag,HolderLookup.Provider registries) {
        authority(); tag.putInt("schema_version",3); var bs=new ListTag(); var ps=new ListTag(); var fs=new ListTag();
        for(var b:buildings.values()) {
            var e=new CompoundTag(); e.putUUID("id",b.id()); e.putUUID("settlement",b.settlementId()); e.putString("dimension",b.dimension()); e.putString("kind",b.kind().name()); e.putString("template",b.template().toString());
            putPos(e,"origin",b.origin()); putPos(e,"entrance",b.entrance()); e.putString("rotation",b.rotation().name()); e.putInt("max_x",b.bounds().maxX()); e.putInt("max_z",b.bounds().maxZ()); e.putInt("slots",b.workplaceSlots()); e.putBoolean("active",b.active()); bs.add(e);
        }
        for(var p:professions.values()) {
            var e=new CompoundTag(); e.putLong("employment_revision",employmentRevision(p.citizenId())); e.putUUID("citizen",p.citizenId()); e.putUUID("settlement",p.settlementId()); e.putString("type",p.type().name()); e.putInt("level",p.level().value()); e.putLong("xp",p.experience());
            if(p.workplaceId()!=null) e.putUUID("workplace",p.workplaceId()); e.putBoolean("active",p.active()); e.putString("work_state",p.workState().name()); e.putLong("next_work",p.nextWorkAt()); e.putInt("cursor",p.cropCursor()); e.putInt("failures",p.navigationFailures());
            var traits=new ListTag(); for(var t:p.traits()) { var v=new CompoundTag(); v.putString("trait",t.name()); traits.add(v); } e.put("traits",traits); ps.add(e);
        }
        food.forEach((id,f) -> {var e=new CompoundTag(); e.putUUID("settlement",id); e.putInt("stock",f.stock()); e.putInt("capacity",f.capacity()); e.putLong("produced",f.produced()); fs.add(e);});
        var cs=new ListTag(); careers.forEach((id,history) -> history.forEach((type,c) -> {
            var e=new CompoundTag(); e.putUUID("citizen",id); e.putString("type",type.name()); e.putInt("level",c.level()); e.putLong("xp",c.xp()); cs.add(e);
        })); tag.put("careers",cs);
        tag.put("buildings",bs); tag.put("professions",ps); tag.put("food",fs); return tag;
    }
    private static void require(CompoundTag e,String key,int type) { if(!e.contains(key,type)) throw new IllegalArgumentException("Invalid profession field: "+key); }
    private static UUID uuid(CompoundTag e,String key) { if(!e.hasUUID(key)) throw new IllegalArgumentException("Invalid profession UUID"); return e.getUUID(key); }
    private static String string(CompoundTag e,String key) { require(e,key,Tag.TAG_STRING); return e.getString(key); }
    private static int integer(CompoundTag e,String key) { require(e,key,Tag.TAG_INT); return e.getInt(key); }
    private static long number(CompoundTag e,String key) { require(e,key,Tag.TAG_LONG); return e.getLong(key); }
    private static boolean bool(CompoundTag e,String key) { require(e,key,Tag.TAG_BYTE); byte value=e.getByte(key); if(value!=0 && value!=1) throw new IllegalArgumentException("Invalid flag"); return value==1; }
    private static ListTag list(CompoundTag e,String key,int limit) { require(e,key,Tag.TAG_LIST); var list=(ListTag)e.get(key); if(list.size()>limit || !list.isEmpty() && list.getElementType()!=Tag.TAG_COMPOUND) throw new IllegalArgumentException("Invalid profession list"); return list; }
    private static void putPos(CompoundTag e,String key,BlockPos pos) { var p=new CompoundTag(); p.putInt("x",pos.getX()); p.putInt("y",pos.getY()); p.putInt("z",pos.getZ()); e.put(key,p); }
    private static BlockPos pos(CompoundTag e,String key) { require(e,key,Tag.TAG_COMPOUND); var p=e.getCompound(key); return new BlockPos(integer(p,"x"),integer(p,"y"),integer(p,"z")); }
}
