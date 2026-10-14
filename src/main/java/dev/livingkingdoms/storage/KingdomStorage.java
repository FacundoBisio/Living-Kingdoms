package dev.livingkingdoms.storage;

import dev.livingkingdoms.LivingKingdoms;
import dev.livingkingdoms.block.KingdomBlocks;
import dev.livingkingdoms.economy.KingdomEconomy;
import dev.livingkingdoms.settlement.persistence.SettlementSavedData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.inventory.MenuType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.extensions.IMenuTypeExtension;
import net.neoforged.neoforge.registries.DeferredRegister;
import java.util.function.Supplier;

/** Physical access point to the one kingdom ledger. No block inventory or dropped economic contents. */
public final class KingdomStorage {
    private static final DeferredRegister<MenuType<?>> MENUS=DeferredRegister.create(Registries.MENU,LivingKingdoms.MOD_ID);
    public static final Supplier<MenuType<StorageMenu>> MENU=MENUS.register("kingdom_storage",()->IMenuTypeExtension.create(
            (id,inventory,buf)->new StorageMenu(id,inventory,buf.readBlockPos(),buf.readUUID())));
    private KingdomStorage() {}
    public static void register(IEventBus bus) {MENUS.register(bus);}
    public static boolean open(ServerPlayer player,BlockPos pos) {
        if(!player.serverLevel().hasChunkAt(pos) || !player.serverLevel().getBlockState(pos).is(KingdomBlocks.KINGDOM_STORAGE)
                || player.distanceToSqr(net.minecraft.world.phys.Vec3.atCenterOf(pos))>64) return false;
        var kingdom=SettlementSavedData.get(player.server).alliedAt(player.level().dimension().location().toString(),pos.getX(),pos.getZ()).orElse(null);
        if(kingdom==null || !KingdomEconomy.authorized(player,kingdom.id())) {
            player.displayClientMessage(Component.translatable("storage.livingkingdoms.unavailable"),true);return false;
        }
        dev.livingkingdoms.ui.VillageUiService.dismiss(player);dev.livingkingdoms.ui.QuestJournalService.dismiss(player);
        player.openMenu(new SimpleMenuProvider((id,inventory,p)->new StorageMenu(id,inventory,pos,kingdom.id()),
                Component.translatable("block.livingkingdoms.kingdom_storage")),buf->{buf.writeBlockPos(pos);buf.writeUUID(kingdom.id());});return true;
    }
}
