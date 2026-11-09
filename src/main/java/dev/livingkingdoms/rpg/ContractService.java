package dev.livingkingdoms.rpg;

import dev.livingkingdoms.item.KingdomItems;
import dev.livingkingdoms.onboarding.KingdomOnboardingService;
import dev.livingkingdoms.quest.DeliveryInventory;
import dev.livingkingdoms.quest.expansion.domain.QuestTemplate;
import dev.livingkingdoms.quest.expansion.domain.ResourceKind;
import dev.livingkingdoms.quest.expansion.domain.ResourceRequirement;
import dev.livingkingdoms.quest.persistence.QuestSavedData;
import dev.livingkingdoms.rpg.domain.*;
import dev.livingkingdoms.rpg.persistence.RpgSavedData;
import dev.livingkingdoms.settlement.persistence.SettlementSavedData;
import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;

/**
 * Daily/weekly contracts. Boards belong to the player and pay into their main kingdom. Progress comes from
 * existing events (kills, regional claims, construction supply, trial wins); deliveries are checked at claim
 * time against the inventory. Claims are one-time receipts in {@link RpgSavedData}.
 */
public final class ContractService {
    public static final int DAILY_EMERALDS = 6, DAILY_KINGDOM_XP = 30, DAILY_PLAYER_XP = 25;
    public static final int WEEKLY_EMERALDS = 32, WEEKLY_WHETSTONES = 3, WEEKLY_KINGDOM_XP = 150;
    public static final int SLOT_WEEKLY = -1, SLOT_BONUS = -2;
    private ContractService() {}

    /** The player's main kingdom, or before it is anchored, the allied kingdom they stand in and serve. */
    static Optional<UUID> kingdom(ServerPlayer player) {
        var settlements = SettlementSavedData.get(player.server);
        var main = QuestSavedData.get(player.server).mainSettlement(player.getUUID())
                .filter(id -> settlements.get(id).filter(s -> s.faction().isAllied()).isPresent());
        if (main.isPresent()) return main;
        return settlements.alliedAt(player.level().dimension().location().toString(), player.getBlockX(), player.getBlockZ())
                .filter(s -> KingdomOnboardingService.canHelp(player, s)).map(dev.livingkingdoms.settlement.domain.Settlement::id);
    }

    /** Refreshed board for today; persisted only when it changed. */
    public static Optional<ContractBoard> board(ServerPlayer player) {
        var kingdom = kingdom(player).orElse(null);
        if (kingdom == null) return Optional.empty();
        var data = RpgSavedData.get(player.server);
        int level = KingdomProgressService.level(player.server, kingdom);
        boolean trials = QuestSavedData.get(player.server).completedMain(player.getUUID(), kingdom).contains(QuestTemplate.MAIN_IRON);
        var board = data.board(player.getUUID()).refresh(RpgClock.today(), RpgClock.week(), seed(player.getUUID()),
                KingdomRank.dailyContracts(level), KingdomRank.weeklyUnlocked(level), trials);
        data.putBoard(player.getUUID(), board);
        return Optional.of(board);
    }

    private static long seed(UUID player) { return player.getMostSignificantBits() ^ player.getLeastSignificantBits(); }

    public static void progress(ServerPlayer player, Contract.Kind kind, int amount) {
        var before = board(player).orElse(null);
        if (before == null) return;
        var after = before.progress(kind, amount);
        if (after.equals(before)) return;
        RpgSavedData.get(player.server).putBoard(player.getUUID(), after);
        for (int i = 0; i < after.daily().size(); i++) notify(player, before.daily().get(i), after.daily().get(i));
        if (after.weekly() != null) notify(player, before.weekly(), after.weekly());
    }

    private static void notify(ServerPlayer player, Contract before, Contract after) {
        if (before == null || before.equals(after)) return;
        if (after.complete() && !before.complete())
            player.displayClientMessage(Component.translatable("contract.livingkingdoms.ready", describe(after)).withStyle(ChatFormatting.GREEN), true);
        else player.displayClientMessage(Component.translatable("contract.livingkingdoms.progress", describe(after), after.progress(), after.required()), true);
    }

    public static Component describe(Contract contract) {
        return contract.kind() == Contract.Kind.DELIVER
                ? Component.translatable("contract.livingkingdoms.kind.deliver", contract.required(), Component.translatable("contract.livingkingdoms.resource." + contract.target()))
                : Component.translatable("contract.livingkingdoms.kind." + contract.kind().name().toLowerCase(java.util.Locale.ROOT), contract.required());
    }

    public static void onKill(LivingDeathEvent event) {
        if (event.isCanceled() || !(event.getSource().getEntity() instanceof ServerPlayer player) || !(event.getEntity() instanceof Enemy)) return;
        progress(player, Contract.Kind.SLAY, 1);
    }

    /** Snapshot for the contract screen and the board. Empty-but-valid when the player has no kingdom. */
    public static CompoundTag snapshot(ServerPlayer player) {
        var tag = new CompoundTag();
        var kingdom = kingdom(player).orElse(null);
        var board = board(player).orElse(null);
        tag.putBoolean("available", board != null);
        if (board == null) return tag;
        long xp = RpgSavedData.get(player.server).kingdomXp(kingdom);
        tag.putInt("rank", KingdomRank.level(xp)); tag.putLong("rank_progress", KingdomRank.progress(xp)); tag.putLong("rank_span", KingdomRank.span(xp));
        tag.putInt("streak", board.streak()); tag.putInt("multiplier", (int) Math.round((board.multiplier() - 1) * 100));
        tag.putLong("reset", RpgClock.secondsUntilTomorrow());
        var daily = new ListTag();
        for (var c : board.daily()) daily.add(view(player, c, dailyEmeralds(player, board)));
        tag.put("daily", daily);
        if (board.weekly() != null) tag.put("weekly", view(player, board.weekly(), WEEKLY_EMERALDS));
        tag.putBoolean("weekly_locked", board.weekly() == null);
        tag.putBoolean("bonus_ready", board.allDailyClaimed() && !board.dailyBonusClaimed());
        tag.putBoolean("bonus_claimed", board.dailyBonusClaimed());
        tag.putBoolean("inside", inside(player, kingdom));
        var known = new ListTag();
        for (var technique : RpgSavedData.get(player.server).techniques(player.getUUID())) known.add(net.minecraft.nbt.StringTag.valueOf(technique.id()));
        tag.put("techniques", known);
        tag.putBoolean("festival", EventService.festival(player.server, kingdom));
        return tag;
    }

    private static CompoundTag view(ServerPlayer player, Contract c, int emeralds) {
        var v = new CompoundTag();
        v.putString("kind", c.kind().name().toLowerCase(java.util.Locale.ROOT)); v.putString("target", c.target());
        v.putInt("required", c.required()); v.putBoolean("claimed", c.claimed());
        int progress = c.tracked() ? c.progress() : Math.min(c.required(), DeliveryInventory.count(player.getInventory(), ResourceKind.fromId(c.target())));
        v.putInt("progress", c.claimed() ? c.required() : progress);
        v.putBoolean("ready", !c.claimed() && progress >= c.required());
        v.putInt("emeralds", emeralds);
        return v;
    }

    private static int dailyEmeralds(ServerPlayer player, ContractBoard board) {
        int bonus = kingdom(player).map(k -> KingdomRank.emeraldBonusPercent(KingdomProgressService.level(player.server, k))).orElse(0);
        var kingdom = kingdom(player).orElse(null);
        double festival = kingdom != null && EventService.festival(player.server, kingdom) ? 2 : 1;
        double haggling = TechniqueService.knows(player, dev.livingkingdoms.rpg.domain.Technique.HAGGLING) ? 1.15 : 1;
        return (int) Math.round(DAILY_EMERALDS * board.multiplier() * (100 + bonus) / 100.0 * festival * haggling);
    }

    private static boolean inside(ServerPlayer player, UUID kingdom) {
        String dimension = player.level().dimension().location().toString();
        return SettlementSavedData.get(player.server).alliedAt(dimension, player.getBlockX(), player.getBlockZ())
                .filter(s -> s.id().equals(kingdom) && KingdomOnboardingService.canHelp(player, s)).isPresent();
    }

    /** {@code slot} ≥ 0 daily index, {@link #SLOT_WEEKLY}, or {@link #SLOT_BONUS}. Server thread only. */
    public static boolean claim(ServerPlayer player, int slot) {
        var kingdom = kingdom(player).orElse(null);
        var board = board(player).orElse(null);
        if (kingdom == null || board == null || !inside(player, kingdom)) return refuse(player, "outside");
        var data = RpgSavedData.get(player.server);
        var items = new ArrayList<ItemStack>();
        Component title; long kingdomXp; int playerXp = 0, emeralds; boolean epic = false; LootRarity rarity = null;
        if (slot == SLOT_BONUS) {
            if (!board.allDailyClaimed() || board.dailyBonusClaimed()) return refuse(player, "bonus");
            var next = board.claimDailyBonus();
            rarity = LootRarity.roll(new Random(player.getRandom().nextLong()), KingdomProgressService.luck(player.server, kingdom) + next.streak() * 2, LootRarity.COMMON);
            items.add(LootChests.chest(rarity)); items.add(new ItemStack(KingdomItems.ROYAL_WHETSTONE.get()));
            data.putBoard(player.getUUID(), next);
            title = Component.translatable("contract.livingkingdoms.bonus_title", next.streak()); kingdomXp = 40; emeralds = 0; epic = next.streak() >= 7;
        } else {
            boolean weekly = slot == SLOT_WEEKLY;
            if (!weekly && (slot < 0 || slot >= board.daily().size())) return refuse(player, "missing");
            var contract = weekly ? board.weekly() : board.daily().get(slot);
            if (contract == null || contract.claimed()) return refuse(player, "missing");
            List<ResourceRequirement> cost = List.of();
            if (contract.kind() == Contract.Kind.DELIVER) cost = List.of(new ResourceRequirement(ResourceKind.fromId(contract.target()), contract.required()));
            else if (!contract.complete()) return refuse(player, "pending");
            var exchange = DeliveryInventory.plan(player.getInventory(), cost, 0);
            if (exchange.isEmpty()) return refuse(player, "pending");
            // Receipt first, then inventory: a refused write never consumes items.
            if (!data.putBoard(player.getUUID(), weekly ? board.claimWeekly() : board.claimDaily(slot))) return refuse(player, "missing");
            exchange.orElseThrow().apply(player.getInventory());
            if (weekly) {
                emeralds = WEEKLY_EMERALDS; kingdomXp = WEEKLY_KINGDOM_XP; playerXp = 80; epic = true;
                rarity = LootRarity.roll(new Random(player.getRandom().nextLong()), KingdomProgressService.luck(player.server, kingdom) + board.streak(), LootRarity.RARE);
                items.add(new ItemStack(KingdomItems.ROYAL_WHETSTONE.get(), WEEKLY_WHETSTONES)); items.add(LootChests.chest(rarity));
            } else { emeralds = dailyEmeralds(player, board); kingdomXp = DAILY_KINGDOM_XP; playerXp = DAILY_PLAYER_XP; }
            items.addFirst(new ItemStack(Items.EMERALD, emeralds));
            title = describe(contract);
        }
        RewardCeremony.celebrate(player, "contract", title, emeralds, 0, playerXp, items, epic, rarity);
        KingdomProgressService.award(player, kingdom, kingdomXp);
        PacketDistributor.sendToPlayer(player, new RpgPayloads.Contracts(snapshot(player)));
        return true;
    }

    private static boolean refuse(ServerPlayer player, String reason) {
        player.displayClientMessage(Component.translatable("contract.livingkingdoms.refused." + reason), true);
        PacketDistributor.sendToPlayer(player, new RpgPayloads.Contracts(snapshot(player)));
        return false;
    }
}
