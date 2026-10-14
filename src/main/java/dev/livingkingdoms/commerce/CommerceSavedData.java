package dev.livingkingdoms.commerce;

import dev.livingkingdoms.config.CommerceConfig;
import dev.livingkingdoms.economy.StockResource;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.storage.LevelResource;
import java.nio.file.Files;
import java.util.*;

public final class CommerceSavedData extends SavedData {
    public static final String DATA_NAME="livingkingdoms_commerce";
    private final Map<UUID,MarketAccount> accounts=new LinkedHashMap<>();
    private Thread owner;
    public static CommerceSavedData get(MinecraftServer server) {
        if(!server.isSameThread()) throw new IllegalStateException("Commerce requires server thread");
        var data=server.overworld().getDataStorage().computeIfAbsent(new Factory<>(()-> {
            if(!Files.notExists(server.getWorldPath(LevelResource.ROOT).resolve("data").resolve(DATA_NAME+".dat"))) throw new IllegalStateException("Unreadable commerce data; refusing overwrite");
            return new CommerceSavedData();
        },CommerceSavedData::load),DATA_NAME);data.owner=Thread.currentThread();return data;
    }
    private void authority() {if(owner!=null && owner!=Thread.currentThread()) throw new IllegalStateException("Commerce requires server thread");}
    public Optional<MarketAccount> account(UUID id) {authority();return Optional.ofNullable(accounts.get(id));}
    public MarketAccount ensure(UUID id,long now) {
        authority();Objects.requireNonNull(id,"Missing market owner");var old=accounts.get(id);
        if(old==null && accounts.size()>=65536) throw new IllegalStateException("Too many markets");
        var next=old==null?MarketAccount.empty(CommerceConfig.INITIAL_EMERALDS.get(),now,CommerceConfig.RESTOCK_TICKS.get()):old.restock(now,CommerceConfig.RESTOCK_TICKS.get());
        if(!next.equals(old)){accounts.put(id,next);setDirty();}return next;
    }
    public boolean replace(UUID id,MarketAccount expected,MarketAccount next) {
        authority();if(!expected.equals(accounts.get(id)))return false;
        if(next.revision()!=expected.revision()+1)throw new IllegalArgumentException("Invalid commerce revision");
        accounts.put(id,next);setDirty();return true;
    }
    public static CommerceSavedData load(CompoundTag tag,HolderLookup.Provider registries) {
        if(!tag.contains("schema",Tag.TAG_INT) || tag.getInt("schema")!=1 || !tag.contains("accounts",Tag.TAG_LIST))throw new IllegalArgumentException("Invalid commerce schema");
        var list=(ListTag)tag.get("accounts");
        if(list.size()>65536 || !list.isEmpty() && list.getElementType()!=Tag.TAG_COMPOUND)throw new IllegalArgumentException("Invalid commerce accounts");
        var result=new CommerceSavedData();
        for(var value:list) {
            var e=(CompoundTag)value;if(!e.hasUUID("id"))throw new IllegalArgumentException("Missing market owner");
            var bought=new EnumMap<StockResource,Integer>(StockResource.class);var sold=new EnumMap<StockResource,Integer>(StockResource.class);
            for(var r:StockResource.values()){bought.put(r,integer(e,"buy_"+r));sold.put(r,integer(e,"sell_"+r));}
            var account=new MarketAccount(integer(e,"emeralds"),number(e,"revision"),number(e,"restock"),bought,sold);
            if(result.accounts.putIfAbsent(e.getUUID("id"),account)!=null)throw new IllegalArgumentException("Duplicate market owner");
        }return result;
    }
    @Override public CompoundTag save(CompoundTag tag,HolderLookup.Provider registries) {
        authority();tag.putInt("schema",1);var list=new ListTag();
        accounts.forEach((id,a)->{var e=new CompoundTag();e.putUUID("id",id);e.putInt("emeralds",a.emeralds());e.putLong("revision",a.revision());e.putLong("restock",a.restockAt());
            for(var r:StockResource.values()){e.putInt("buy_"+r,a.bought().getOrDefault(r,0));e.putInt("sell_"+r,a.sold().getOrDefault(r,0));}list.add(e);});
        tag.put("accounts",list);return tag;
    }
    private static int integer(CompoundTag e,String key){if(!e.contains(key,Tag.TAG_INT))throw new IllegalArgumentException("Invalid market integer");return e.getInt(key);}
    private static long number(CompoundTag e,String key){if(!e.contains(key,Tag.TAG_LONG))throw new IllegalArgumentException("Invalid market timestamp");return e.getLong(key);}
}
