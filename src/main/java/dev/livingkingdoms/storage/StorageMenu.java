package dev.livingkingdoms.storage;

import dev.livingkingdoms.block.KingdomBlocks;
import dev.livingkingdoms.config.ProfessionConfig;
import dev.livingkingdoms.economy.*;
import dev.livingkingdoms.profession.ProfessionService;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.*;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import java.util.UUID;

/** Read-only resource icons plus ordinary player slots. Ghost stacks can never enter an inventory/cursor. */
public final class StorageMenu extends AbstractContainerMenu {
    public static final int RESOURCES=5,FIELDS=5;
    private final SimpleContainer icons=new SimpleContainer(RESOURCES);
    private final ContainerData values=new SimpleContainerData(RESOURCES*FIELDS*2+2);
    private final Player owner;
    private final BlockPos pos;
    private final UUID kingdom;
    private long nextRefresh;
    public StorageMenu(int id,Inventory inventory,BlockPos pos,UUID kingdom) {
        super(KingdomStorage.MENU.get(),id);this.owner=inventory.player;this.pos=pos.immutable();this.kingdom=kingdom;
        for(int i=0;i<RESOURCES;i++) {
            icons.setItem(i,new ItemStack(StockResource.values()[i].item()));
            addSlot(new Slot(icons,i,26+i*40,30) {
                @Override public boolean mayPlace(ItemStack stack) {return false;}
                @Override public boolean mayPickup(Player player) {return false;}
            });
        }
        for(int row=0;row<3;row++) for(int col=0;col<9;col++) addSlot(new Slot(inventory,col+row*9+9,34+col*18,128+row*18));
        for(int col=0;col<9;col++) addSlot(new Slot(inventory,col,34+col*18,186));
        addDataSlots(values);refresh();
    }
    public int value(int resource,int field) {int i=(resource*FIELDS+field)*2;return StorageAccounting.join(values.get(i),values.get(i+1));}
    private void value(int resource,int field,int value) {int i=(resource*FIELDS+field)*2;values.set(i,StorageAccounting.low(value));values.set(i+1,StorageAccounting.high(value));}
    public int foodReserve() {return StorageAccounting.join(values.get(RESOURCES*FIELDS*2),values.get(RESOURCES*FIELDS*2+1));}
    public UUID kingdom() {return kingdom;}
    private void refresh() {
        if(!(owner instanceof ServerPlayer player)) return;
        var account=KingdomEconomy.ensure(player.server,kingdom);var food=ProfessionService.food(player.server,kingdom);
        for(var resource:StockResource.values()) {
            int i=resource.ordinal();var construction=StorageAccounting.construction(player.server,kingdom,resource);
            value(i,0,KingdomEconomy.count(player.server,kingdom,resource));value(i,1,resource==StockResource.FOOD?food.capacity():account.capacity());
            value(i,2,construction.reserved());value(i,3,construction.consumed());
            value(i,4,resource==StockResource.FOOD?food.surplus(ProfessionConfig.HEALTHY_FOOD.get()):account.balance(resource));
        }
        int reserve=food.reserve(ProfessionConfig.HEALTHY_FOOD.get());values.set(RESOURCES*FIELDS*2,StorageAccounting.low(reserve));values.set(RESOURCES*FIELDS*2+1,StorageAccounting.high(reserve));
        nextRefresh=owner.level().getGameTime()+20;
    }
    @Override public void broadcastChanges() {
        if(owner.level().getGameTime()>=nextRefresh && stillValid(owner)) refresh();
        super.broadcastChanges();
    }
    @Override public boolean stillValid(Player player) {
        return player==owner && player.isAlive() && !player.isSpectator() && player.level().hasChunkAt(pos)
                && player.level().getBlockState(pos).is(KingdomBlocks.KINGDOM_STORAGE)
                && player.distanceToSqr(net.minecraft.world.phys.Vec3.atCenterOf(pos))<=64;
    }
    @Override public boolean clickMenuButton(Player player,int action) {
        if(!(player instanceof ServerPlayer serverPlayer) || !stillValid(player) || action<0 || action>=RESOURCES*3) return false;
        var resource=StockResource.values()[action%RESOURCES];int mode=action/RESOURCES;
        int moved=mode==0?KingdomEconomy.deposit(serverPlayer,kingdom,resource,64):KingdomEconomy.withdraw(serverPlayer,kingdom,resource,mode==1 && resource!=StockResource.EQUIPMENT?16:1);
        refresh();super.broadcastChanges();return moved>0;
    }
    @Override public void clicked(int slotId,int mouseButton,ClickType type,Player player) {
        if(slotId>=0 && slotId<RESOURCES) {
            if(type==ClickType.PICKUP || type==ClickType.QUICK_MOVE) clickMenuButton(player,slotId+(mouseButton==1?2:1)*RESOURCES);
            return;
        }
        super.clicked(slotId,mouseButton,type,player);
    }
    @Override public boolean canTakeItemForPickAll(ItemStack stack,Slot slot) {return slot.container!=icons && super.canTakeItemForPickAll(stack,slot);}
    @Override public ItemStack quickMoveStack(Player player,int index) {
        if(index<0 || index>=slots.size()) return ItemStack.EMPTY;
        if(index<RESOURCES) {clickMenuButton(player,index+RESOURCES);return ItemStack.EMPTY;}
        var stack=slots.get(index).getItem();
        if(player instanceof ServerPlayer serverPlayer && stillValid(player)) for(var resource:StockResource.values()) if(matches(stack,resource)) {
            KingdomEconomy.depositSlot(serverPlayer,kingdom,resource,slots.get(index).getContainerSlot(),Math.min(64,stack.getCount()));refresh();super.broadcastChanges();break;
        }
        return ItemStack.EMPTY;
    }
    private static boolean matches(ItemStack stack,StockResource resource) {
        return switch(resource) {case FOOD->stack.is(Items.WHEAT);case WOOD->stack.is(ItemTags.LOGS);case STONE->stack.is(Items.STONE);case IRON->stack.is(Items.IRON_INGOT);case EQUIPMENT->false;};
    }
}
