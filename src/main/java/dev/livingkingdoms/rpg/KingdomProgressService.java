package dev.livingkingdoms.rpg;

import dev.livingkingdoms.rpg.domain.KingdomRank;
import dev.livingkingdoms.rpg.persistence.RpgSavedData;
import dev.livingkingdoms.settlement.persistence.SettlementSavedData;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import java.util.ArrayList;
import java.util.UUID;

/**
 * Shared kingdom rank. Every quest, contract, construction and trial feeds XP into the allied settlement;
 * each level-up pays a loot chest to the player who earned it and announces new perks to members nearby.
 */
public final class KingdomProgressService {
    private KingdomProgressService() {}

    public static int level(MinecraftServer server, UUID settlement) { return KingdomRank.level(RpgSavedData.get(server).kingdomXp(settlement)); }
    public static int luck(MinecraftServer server, UUID settlement) { return KingdomRank.chestLuck(level(server, settlement)); }

    /** Adds XP on behalf of a player; returns the number of levels gained. */
    public static int award(ServerPlayer player, UUID settlement, long xp) {
        var server = player.server;
        if (SettlementSavedData.get(server).get(settlement).filter(s -> s.faction().isAllied()).isEmpty()) return 0;
        var data = RpgSavedData.get(server);
        int before = KingdomRank.level(data.kingdomXp(settlement));
        int after = KingdomRank.level(data.addKingdomXp(settlement, xp));
        for (int level = before + 1; level <= after; level++) levelUp(player, settlement, level);
        return after - before;
    }

    /** XP without a player (e.g. a Builder finishing alone); level-up chests go to the nearest member online. */
    public static void awardKingdom(MinecraftServer server, UUID settlement, long xp, ServerPlayer witness) {
        if (witness != null) { award(witness, settlement, xp); return; }
        if (SettlementSavedData.get(server).get(settlement).filter(s -> s.faction().isAllied()).isPresent())
            RpgSavedData.get(server).addKingdomXp(settlement, xp);
    }

    private static void levelUp(ServerPlayer player, UUID settlement, int level) {
        var items = new ArrayList<net.minecraft.world.item.ItemStack>();
        var rarity = KingdomRank.levelUpChest(level);
        items.add(LootChests.chest(rarity));
        var notes = new ArrayList<String>();
        for (var unlock : KingdomRank.unlocks(level)) notes.add("rank.livingkingdoms.unlock." + unlock);
        for (var decoration : dev.livingkingdoms.rpg.domain.TownDecor.tiersAt(level)) notes.add("decor.livingkingdoms.tier." + decoration.id());
        RewardCeremony.celebrate(player, "rank", Component.translatable("rank.livingkingdoms.level_up", level), 0, 0, 0, items, true, rarity, notes);
        TownDecorService.beautify(player.serverLevel(), settlement, level);
        var name = SettlementSavedData.get(player.server).get(settlement).map(dev.livingkingdoms.ui.VillageNames::display).orElse("");
        var message = Component.translatable("rank.livingkingdoms.announce", name, level).withStyle(ChatFormatting.GOLD);
        for (var unlock : KingdomRank.unlocks(level))
            message.append(Component.literal("\n • ").append(Component.translatable("rank.livingkingdoms.unlock." + unlock)).withStyle(ChatFormatting.YELLOW));
        player.sendSystemMessage(message);
    }
}
