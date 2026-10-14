package dev.livingkingdoms.commerce;

import dev.livingkingdoms.citizen.persistence.CitizenSavedData;
import dev.livingkingdoms.config.*;
import dev.livingkingdoms.economy.*;
import dev.livingkingdoms.profession.ProfessionService;
import dev.livingkingdoms.profession.domain.*;
import dev.livingkingdoms.profession.persistence.ProfessionSavedData;
import dev.livingkingdoms.settlement.domain.SettlementLifecycle;
import dev.livingkingdoms.settlement.persistence.SettlementSavedData;
import net.minecraft.nbt.*;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.Items;
import net.neoforged.bus.api.*;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import java.util.*;

/** Player-bound, short-lived capability. Every quote is consumed once; all mutations stay in one server-thread call. */
public final class CommerceService {
    private record Session(UUID token,UUID quote,UUID citizen,UUID merchant,UUID settlement,String dimension,long expires,
                           long employment,long marketRevision,long economicRevision,FoodStock food,long lastRequest,Map<StockResource,Integer> buy,Map<StockResource,Integer> sell) {}
    private static final Map<ServerPlayer,Session> SESSIONS=new WeakHashMap<>();
    private CommerceService() {}
    public static void register(IEventBus bus) {
        bus.addListener(EventPriority.HIGHEST,CommerceService::onInteract);
        bus.addListener(EventPriority.HIGHEST,CommerceService::onSpecific);
    }
    public static void dismiss(ServerPlayer player){SESSIONS.remove(player);}
    public static void onSpecific(PlayerInteractEvent.EntityInteractSpecific event) {
        if(event.getTarget() instanceof Villager v && MerchantWork.profile(v).isPresent() && !event.getEntity().isShiftKeyDown()) {
            event.setCanceled(true);event.setCancellationResult(InteractionResult.PASS);
        }
    }
    public static void onInteract(PlayerInteractEvent.EntityInteract event) {
        if(!(event.getTarget() instanceof Villager worker) || MerchantWork.profile(worker).isEmpty() || event.getEntity().isShiftKeyDown())return;
        event.setCanceled(true);event.setCancellationResult(InteractionResult.SUCCESS);
        if(event.getHand()==InteractionHand.MAIN_HAND && event.getEntity() instanceof ServerPlayer player && !open(player,worker))
            player.displayClientMessage(Component.translatable("commerce.livingkingdoms.unavailable"),true);
    }
    public static boolean open(ServerPlayer player,Villager worker) {
        if(!player.server.isSameThread())throw new IllegalStateException("Commerce requires server thread");
        var p=MerchantWork.profile(worker).orElse(null);if(p==null || !authorized(player,worker,p))return false;
        var old=SESSIONS.get(player);long now=KingdomEconomy.now(player.server);
        if(old!=null && now-old.lastRequest()<5)return false;
        if(old==null && SESSIONS.size()>=256)return false;
        var market=CommerceSavedData.get(player.server).ensure(p.settlementId(),now);var economy=KingdomEconomy.ensure(player.server,p.settlementId());
        var s=new Session(UUID.randomUUID(),UUID.randomUUID(),p.citizenId(),worker.getUUID(),p.settlementId(),player.level().dimension().location().toString(),now+1200,
                ProfessionSavedData.get(player.server).employmentRevision(p.citizenId()),market.revision(),economy.revision(),ProfessionService.food(player.server,p.settlementId()),now,Map.of(),Map.of());
        dev.livingkingdoms.ui.VillageUiService.dismiss(player);dev.livingkingdoms.ui.QuestJournalService.dismiss(player);
        SESSIONS.put(player,s);send(player,s,"");return true;
    }
    private static boolean authorized(ServerPlayer player,Villager worker,Profession p) {
        return worker.level()==player.level() && player.distanceToSqr(worker)<=64 && player.containerMenu==player.inventoryMenu
                && KingdomEconomy.authorized(player,p.settlementId()) && MerchantWork.available(worker)
                && SettlementSavedData.get(player.server).get(p.settlementId()).filter(s->s.lifecycle()==SettlementLifecycle.ESTABLISHED).isPresent();
    }
    /** For UI/testing: immutable server-issued quote, never trusts client price/count/entity IDs. */
    public static Optional<CompoundTag> snapshot(ServerPlayer player) {
        var s=SESSIONS.get(player);return s==null?Optional.empty():Optional.of(buildSnapshot(player,s,""));
    }
    public static boolean handle(ServerPlayer player,CommercePayloads.Request request) {
        if(!player.server.isSameThread())throw new IllegalStateException("Commerce requires server thread");
        var s=SESSIONS.get(player);if(s==null || !s.token().equals(request.session()))return false;
        if(request.action()==CommercePayloads.Action.CLOSE){dismiss(player);return true;}
        long now=KingdomEconomy.now(player.server);
        if(now>s.expires() || now<s.expires()-1200 || !s.dimension().equals(player.level().dimension().location().toString())
                || !(player.serverLevel().getEntity(s.merchant()) instanceof Villager worker)) {close(player,s);return false;}
        var p=MerchantWork.profile(worker).orElse(null);var jobs=ProfessionSavedData.get(player.server);
        if(p==null || !p.citizenId().equals(s.citizen()) || !p.settlementId().equals(s.settlement())
                || jobs.employmentRevision(s.citizen())!=s.employment() || !authorized(player,worker,p)){close(player,s);return false;}
        if(!s.quote().equals(request.offer()))return false;
        if(request.action()==CommercePayloads.Action.REFRESH){send(player,withRequestTime(s,now),"");return true;}
        var market=CommerceSavedData.get(player.server).ensure(s.settlement(),now);var economy=KingdomEconomy.ensure(player.server,s.settlement());
        boolean buy=request.action()==CommercePayloads.Action.BUY;var resource=request.resource();int price=CommerceConfig.price(resource,buy);
        if(market.revision()!=s.marketRevision() || economy.revision()!=s.economicRevision()
                || !ProfessionService.food(player.server,s.settlement()).equals(s.food()) || price!=(buy?s.buy():s.sell()).getOrDefault(resource,-1)) {
            send(player,withRequestTime(s,now),"stale");return false;
        }
        var next=market.trade(resource,buy,price,CommerceConfig.STOCK_LIMIT.get());
        var inventory=CommerceInventory.plan(player.getInventory(),buy?Items.EMERALD:resource.item(),buy?price:1,buy?resource.item():Items.EMERALD,buy?1:price);
        int units=resource.unitsForItems(1,ProfessionConfig.WHEAT_FOOD.get());
        if(next.isEmpty() || inventory.isEmpty() || buy && availableUnits(player,resource)<units) {send(player,withRequestTime(s,now),"rejected");return false;}
        var transfer=KingdomEconomy.plan(player.server,s.settlement(),buy?Map.of(resource,units):Map.of(),buy?Map.of():Map.of(resource,units),false).orElse(null);
        if(transfer==null){send(player,withRequestTime(s,now),"rejected");return false;}
        if(buy)transfer=new KingdomEconomy.Transfer(transfer.settlement(),transfer.before(),transfer.after(),transfer.foodBefore(),transfer.foodSpent(),transfer.foodGained(),true);
        if(!KingdomEconomy.valid(player.server,transfer) || !CommerceSavedData.get(player.server).replace(s.settlement(),market,next.orElseThrow()))return false;
        if(!KingdomEconomy.commit(player.server,transfer))throw new IllegalStateException("Commerce changed inside atomic server-thread exchange");
        inventory.orElseThrow().apply(player.getInventory());KingdomEconomy.sync(player);
        long xp=Math.min(1_000_000_000L,p.experience()+1);
        jobs.replace(p,new Profession(p.citizenId(),p.settlementId(),p.type(),new dev.livingkingdoms.progression.domain.LevelValue(FarmerProgression.level(xp,ProfessionConfig.LEVEL_CAP.get(),ProfessionConfig.XP_STEP.get())),
                xp,p.workplaceId(),true,p.workState(),p.nextWorkAt(),p.cropCursor(),p.navigationFailures(),p.traits()));
        dev.livingkingdoms.chronicle.ChronicleAdvancements.grant(player,"first_trade");
        worker.swing(InteractionHand.MAIN_HAND);send(player,withRequestTime(s,now),"success");return true;
    }
    private static Session withRequestTime(Session s,long now){return new Session(s.token(),s.quote(),s.citizen(),s.merchant(),s.settlement(),s.dimension(),s.expires(),s.employment(),s.marketRevision(),s.economicRevision(),s.food(),now,s.buy(),s.sell());}
    private static int availableUnits(ServerPlayer player,StockResource r) {
        var s=SESSIONS.get(player);return r==StockResource.FOOD?ProfessionService.food(player.server,s.settlement()).surplus(ProfessionConfig.HEALTHY_FOOD.get()):KingdomEconomy.count(player.server,s.settlement(),r);
    }
    private static void send(ServerPlayer player,Session s,String notice) {
        var market=CommerceSavedData.get(player.server).ensure(s.settlement(),KingdomEconomy.now(player.server));var economy=KingdomEconomy.ensure(player.server,s.settlement());
        var buy=new EnumMap<StockResource,Integer>(StockResource.class);var sell=new EnumMap<StockResource,Integer>(StockResource.class);
        for(var r:StockResource.values()){buy.put(r,CommerceConfig.price(r,true));sell.put(r,CommerceConfig.price(r,false));}
        var next=new Session(s.token(),UUID.randomUUID(),s.citizen(),s.merchant(),s.settlement(),s.dimension(),s.expires(),s.employment(),market.revision(),economy.revision(),ProfessionService.food(player.server,s.settlement()),s.lastRequest(),Map.copyOf(buy),Map.copyOf(sell));
        SESSIONS.put(player,next);PacketDistributor.sendToPlayer(player,new CommercePayloads.Snapshot(buildSnapshot(player,next,notice)));
    }
    private static CompoundTag buildSnapshot(ServerPlayer player,Session s,String notice) {
        var tag=new CompoundTag();tag.putUUID("session",s.token());tag.putUUID("offer",s.quote());tag.putString("notice",notice);
        var market=CommerceSavedData.get(player.server).account(s.settlement()).orElseThrow();tag.putInt("treasury",market.emeralds());
        tag.putLong("restock",Math.max(0,market.restockAt()-KingdomEconomy.now(player.server)));var rows=new ListTag();
        for(var r:StockResource.values()) {
            var row=new CompoundTag();row.putString("resource",r.name());row.putInt("buy",s.buy().get(r));row.putInt("sell",s.sell().get(r));
            int units=r.unitsPerItem(ProfessionConfig.WHEAT_FOOD.get());int quotaBuy=market.remaining(r,true,CommerceConfig.STOCK_LIMIT.get()),quotaSell=market.remaining(r,false,CommerceConfig.STOCK_LIMIT.get());
            row.putInt("stock",Math.min(quotaBuy,availableUnits(player,r)/units));row.putInt("demand",quotaSell);
            row.putBoolean("can_buy",row.getInt("stock")>0 && market.emeralds()+s.buy().get(r)<=1_000_000 && CommerceInventory.plan(player.getInventory(),Items.EMERALD,s.buy().get(r),r.item(),1).isPresent());
            int room=r==StockResource.FOOD?ProfessionService.food(player.server,s.settlement()).capacity()-ProfessionService.food(player.server,s.settlement()).stock():KingdomEconomy.ensure(player.server,s.settlement()).room(r);
            row.putBoolean("can_sell",quotaSell>0 && room>=units && market.emeralds()>=s.sell().get(r) && CommerceInventory.plan(player.getInventory(),r.item(),1,Items.EMERALD,s.sell().get(r)).isPresent());rows.add(row);
        }
        tag.put("offers",rows);return tag;
    }
    private static void close(ServerPlayer player,Session s){dismiss(player);var tag=new CompoundTag();tag.putUUID("session",s.token());tag.putBoolean("closed",true);PacketDistributor.sendToPlayer(player,new CommercePayloads.Snapshot(tag));}
}
