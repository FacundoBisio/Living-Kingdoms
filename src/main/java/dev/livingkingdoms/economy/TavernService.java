package dev.livingkingdoms.economy;

import dev.livingkingdoms.citizen.ImmigrationService;
import dev.livingkingdoms.citizen.persistence.CitizenSavedData;
import dev.livingkingdoms.config.CitizenConfig;
import dev.livingkingdoms.profession.persistence.ProfessionSavedData;
import dev.livingkingdoms.settlement.persistence.SettlementSavedData;
import dev.livingkingdoms.structure.BuildingKind;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Items;
import java.util.UUID;

/**
 * The Tavern draws travellers: each tavern level makes regular immigration more likely, and a member can hire a
 * traveller on the spot for a few emeralds (cheaper at higher tavern levels). Hiring still needs a free bed.
 */
public final class TavernService {
    private TavernService() {}

    /** Sum of tavern levels in the kingdom (0 without a tavern). */
    public static int levels(MinecraftServer server, UUID settlement) {
        int total = 0;
        for (var b : ProfessionSavedData.get(server).buildings(settlement))
            if (b.active() && b.kind() == BuildingKind.TAVERN) total += BuildingDevelopment.level(server, settlement, b.id());
        // The Founders' Lodge doubles as a small inn.
        if (FoundersLodge.converted(server, settlement)) total += 1;
        return total;
    }
    /** Multiplier on the regular immigration chance: ×1 without a tavern, ×2 / ×2.5 / ×3 with one. */
    public static double boost(MinecraftServer server, UUID settlement) {
        int levels = levels(server, settlement);
        return levels == 0 ? 1 : 1.5 + .5 * Math.min(levels, 3);
    }
    public static int price(int tavernLevel) { return Math.max(3, 10 - 2 * tavernLevel); }

    public static boolean hire(ServerPlayer player, UUID settlementId, UUID building) {
        var settlement = SettlementSavedData.get(player.server).get(settlementId).orElse(null);
        var tavern = ProfessionSavedData.get(player.server).building(building).filter(b -> b.active() && (b.kind() == BuildingKind.TAVERN || b.kind() == BuildingKind.FOUNDING_CAMP && FoundersLodge.converted(player.server, settlementId)) && b.settlementId().equals(settlementId)).orElse(null);
        if (settlement == null || tavern == null || !KingdomEconomy.authorized(player, settlementId)) return false;
        int price = price(BuildingDevelopment.level(player.server, settlementId, building));
        if (!player.getAbilities().instabuild && player.getInventory().countItem(Items.EMERALD) < price) {
            player.displayClientMessage(Component.translatable("tavern.livingkingdoms.no_emeralds", price), true); return false;
        }
        var people = CitizenSavedData.get(player.server);
        if (people.summary(settlementId).free() < CitizenConfig.MIN_FREE_HOUSING.get()) {
            player.displayClientMessage(Component.translatable("tavern.livingkingdoms.no_bed"), true); return false;
        }
        var candidate = people.candidates(settlementId).stream().findFirst()
                .or(() -> ImmigrationService.attempt(player.serverLevel(), settlement, true)).orElse(null);
        if (candidate == null || !ImmigrationService.accept(player, settlementId, candidate.id())) return false;
        if (!player.getAbilities().instabuild) {
            int left = price;
            for (var stack : player.getInventory().items) {
                if (left == 0) break;
                if (!stack.is(Items.EMERALD)) continue;
                int take = Math.min(left, stack.getCount()); stack.shrink(take); left -= take;
            }
        }
        player.displayClientMessage(Component.translatable("tavern.livingkingdoms.hired", candidate.name(), price), false);
        return true;
    }
}
