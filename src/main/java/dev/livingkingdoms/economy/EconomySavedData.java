package dev.livingkingdoms.economy;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.storage.DimensionDataStorage;
import net.minecraft.world.level.storage.LevelResource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/** Separate guarded store; existing food, citizens, quests and construction schemas stay intact. */
public final class EconomySavedData extends SavedData {
    public static final String DATA_NAME="livingkingdoms_economy";
    private final Map<UUID,StockAccount> accounts=new LinkedHashMap<>();
    private Thread owner;
    public static EconomySavedData get(MinecraftServer server) {
        if(!server.isSameThread()) throw new IllegalStateException("Economy requires server thread");
        var data=getOrCreate(server.overworld().getDataStorage(),server.getWorldPath(LevelResource.ROOT).resolve("data"));
        data.owner=Thread.currentThread();return data;
    }
    public static EconomySavedData getOrCreate(DimensionDataStorage storage,Path directory) {
        return storage.computeIfAbsent(new Factory<>(()-> {
            if(!Files.notExists(directory.resolve(DATA_NAME+".dat"))) throw new IllegalStateException("Unreadable economy data; refusing to overwrite");
            return new EconomySavedData();
        },EconomySavedData::load),DATA_NAME);
    }
    private void authority() { if(owner!=null && owner!=Thread.currentThread()) throw new IllegalStateException("Economy requires server thread"); }
    public Optional<StockAccount> account(UUID settlement) { authority();return Optional.ofNullable(accounts.get(settlement)); }
    public StockAccount ensure(UUID settlement,int capacity,long now) {
        authority();var old=accounts.get(settlement);
        if(old==null && accounts.size()>=65536) throw new IllegalStateException("Too many economic accounts");
        var next=old==null?StockAccount.empty(capacity,now):old.capacity(capacity);
        if(!next.equals(old)) {accounts.put(settlement,next);setDirty();}return next;
    }
    public boolean replace(UUID settlement,StockAccount expected,StockAccount next) {
        authority();if(!expected.equals(accounts.get(settlement))) return false;
        if(next.revision()!=expected.revision()+1) throw new IllegalArgumentException("Invalid economic revision");
        accounts.put(settlement,next);setDirty();return true;
    }
    public static EconomySavedData load(CompoundTag tag,HolderLookup.Provider registries) {
        if(!tag.contains("schema_version",Tag.TAG_INT) || tag.getInt("schema_version")<1 || tag.getInt("schema_version")>2 || !tag.contains("accounts",Tag.TAG_LIST))
            throw new IllegalArgumentException("Unsupported or corrupt economy schema");
        var data=new EconomySavedData();var list=(ListTag)tag.get("accounts");
        if(list.size()>65536 || !list.isEmpty() && list.getElementType()!=Tag.TAG_COMPOUND) throw new IllegalArgumentException("Invalid account list");
        for(var value:list) {
            var record=(CompoundTag)value;
            if(!record.hasUUID("settlement")) throw new IllegalArgumentException("Invalid economy owner");
            var balances=new EnumMap<StockResource,Integer>(StockResource.class);var produced=new EnumMap<StockResource,Long>(StockResource.class);
            for(var kind:StockResource.values()) if(kind!=StockResource.FOOD) {
                balances.put(kind,integer(record,"balance_"+kind.name()));produced.put(kind,number(record,"produced_"+kind.name()));
            }
            var levels=new HashMap<UUID,Integer>();var repairs=new HashSet<UUID>();
            if(tag.getInt("schema_version")>=2) {
                for(var element:records(record,"building_levels")) {
                    var level=(CompoundTag)element;
                    if(!level.hasUUID("building") || levels.putIfAbsent(level.getUUID("building"),integer(level,"level"))!=null) throw new IllegalArgumentException("Invalid development identity");
                }
                for(var element:records(record,"repair_orders")) {
                    var order=(CompoundTag)element;if(!order.hasUUID("building") || !repairs.add(order.getUUID("building"))) throw new IllegalArgumentException("Invalid repair identity");
                }
            }
            var account=new StockAccount(balances,produced,integer(record,"capacity"),number(record,"revision"),number(record,"consumed_at"),number(record,"consumed_food"),levels,repairs);
            if(data.accounts.putIfAbsent(record.getUUID("settlement"),account)!=null) throw new IllegalArgumentException("Duplicate economy owner");
        }
        data.setDirty(false);return data;
    }
    @Override public CompoundTag save(CompoundTag tag,HolderLookup.Provider registries) {
        authority();tag.putInt("schema_version",2);var records=new ListTag();
        accounts.forEach((id,account)-> {
            var value=new CompoundTag();value.putUUID("settlement",id);value.putInt("capacity",account.capacity());
            value.putLong("revision",account.revision());value.putLong("consumed_at",account.consumedAt());value.putLong("consumed_food",account.consumedFood());
            for(var kind:StockResource.values()) if(kind!=StockResource.FOOD) {
                value.putInt("balance_"+kind.name(),account.balance(kind));value.putLong("produced_"+kind.name(),account.produced().getOrDefault(kind,0L));
            }
            var levels=new ListTag();account.buildingLevels().forEach((building,level)-> {var entry=new CompoundTag();entry.putUUID("building",building);entry.putInt("level",level);levels.add(entry);});
            var repairs=new ListTag();account.repairOrders().forEach(building-> {var entry=new CompoundTag();entry.putUUID("building",building);repairs.add(entry);});
            value.put("building_levels",levels);value.put("repair_orders",repairs);
            records.add(value);
        });tag.put("accounts",records);return tag;
    }
    private static int integer(CompoundTag tag,String key) { if(!tag.contains(key,Tag.TAG_INT)) throw new IllegalArgumentException("Invalid economy integer: "+key);return tag.getInt(key); }
    private static long number(CompoundTag tag,String key) { if(!tag.contains(key,Tag.TAG_LONG)) throw new IllegalArgumentException("Invalid economy number: "+key);return tag.getLong(key); }
    private static ListTag records(CompoundTag tag,String key) {
        if(!tag.contains(key,Tag.TAG_LIST)) throw new IllegalArgumentException("Invalid development list");
        var list=(ListTag)tag.get(key);if(list.size()>512 || !list.isEmpty() && list.getElementType()!=Tag.TAG_COMPOUND) throw new IllegalArgumentException("Invalid development records");return list;
    }
}
