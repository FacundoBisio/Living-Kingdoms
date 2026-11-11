package dev.livingkingdoms.rpg;

import dev.livingkingdoms.citizen.domain.CitizenState;
import dev.livingkingdoms.citizen.persistence.CitizenSavedData;
import dev.livingkingdoms.economy.BuildingDevelopment;
import dev.livingkingdoms.item.KingdomItems;
import dev.livingkingdoms.onboarding.KingdomOnboardingService;
import dev.livingkingdoms.profession.persistence.ProfessionSavedData;
import dev.livingkingdoms.rpg.domain.ChapterProgress;
import dev.livingkingdoms.rpg.domain.KingdomMission;
import dev.livingkingdoms.rpg.persistence.RpgSavedData;
import dev.livingkingdoms.settlement.domain.Settlement;
import dev.livingkingdoms.settlement.domain.SettlementLifecycle;
import dev.livingkingdoms.settlement.persistence.SettlementSavedData;
import dev.livingkingdoms.structure.BuildingKind;
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
import java.util.UUID;

/**
 * Kingdom chapters: main missions that must be accepted, one at a time and in order, once the kingdom is
 * established. Goals are read from real kingdom state (Town Hall level, walls, residents...) or, for hunts, from
 * hostile kills inside your kingdom after accepting. Claiming pays emeralds, kingdom XP, a chest and whetstones.
 */
public final class ChapterService {
    private ChapterService() {}
    static final KingdomMission[] MISSIONS = KingdomMission.values();

    static Settlement kingdom(ServerPlayer player) {
        return SettlementSavedData.get(player.server).alliedAt(player.level().dimension().location().toString(), player.getBlockX(), player.getBlockZ())
                .filter(s -> s.lifecycle() == SettlementLifecycle.ESTABLISHED && KingdomOnboardingService.canHelp(player, s)).orElse(null);
    }

    /** Current value toward the mission's goal for this kingdom (or player, for kills). */
    public static int progress(ServerPlayer player, Settlement s, KingdomMission m, ChapterProgress p) {
        var server = player.server; var rpg = RpgSavedData.get(server);
        var buildings = ProfessionSavedData.get(server).buildings(s.id());
        return switch (m.goal()) {
            case KILLS -> p.kills();
            case HALL_LEVEL -> buildings.stream().filter(b -> b.active() && (b.kind() == BuildingKind.TOWN_HALL || b.kind() == BuildingKind.CORE))
                    .mapToInt(b -> BuildingDevelopment.level(server, s.id(), b.id())).max().orElse(0);
            case WALL_TIER -> rpg.wall(s.id()).filter(w -> w.complete()).map(w -> w.tier()).orElse(0);
            case TAVERN -> (int) buildings.stream().filter(b -> b.active() && b.kind() == BuildingKind.TAVERN).count();
            case RESIDENTS -> (int) CitizenSavedData.get(server).citizens(s.id()).stream().filter(c -> c.state() == CitizenState.ACTIVE).count();
            case TOWERS -> rpg.design(s.id()).towers() ? 1 : 0;
            case GATEHOUSES -> rpg.design(s.id()).gatehouses() ? 1 : 0;
            case UPGRADED -> (int) buildings.stream().filter(b -> b.active() && BuildingDevelopment.level(server, s.id(), b.id()) >= 2).count();
            case RANK -> KingdomProgressService.level(server, s.id());
        };
    }

    public static CompoundTag snapshot(ServerPlayer player) {
        var tag = new CompoundTag(); var s = kingdom(player);
        var p = RpgSavedData.get(player.server).chapter(player.getUUID());
        tag.putBoolean("available", s != null); tag.putInt("next", p.next(MISSIONS.length)); tag.putInt("accepted", p.accepted());
        var list = new ListTag();
        for (int i = 0; i < MISSIONS.length; i++) {
            var m = MISSIONS[i]; var row = new CompoundTag();
            row.putString("id", m.id()); row.putInt("target", m.target()); row.putBoolean("claimed", p.claimed(i));
            row.putInt("emeralds", m.emeralds()); row.putInt("xp", m.kingdomXp()); row.putString("chest", m.chest().id()); row.putInt("whetstones", m.whetstones());
            if (s != null && (i == p.accepted() || p.claimed(i))) row.putInt("progress", p.claimed(i) ? m.target() : Math.min(m.target(), progress(player, s, m, p)));
            list.add(row);
        }
        tag.put("missions", list);
        return tag;
    }

    public static boolean accept(ServerPlayer player, int index) {
        var s = kingdom(player); var data = RpgSavedData.get(player.server); var p = data.chapter(player.getUUID());
        if (s == null || index != p.next(MISSIONS.length) || index >= MISSIONS.length || p.accepted() == index) return false;
        data.putChapter(player.getUUID(), p.accept(index));
        player.displayClientMessage(Component.translatable("chapter.livingkingdoms.accepted", Component.translatable("chapter.livingkingdoms." + MISSIONS[index].id() + ".title")), true);
        return true;
    }

    public static boolean claim(ServerPlayer player, int index) {
        var s = kingdom(player); var data = RpgSavedData.get(player.server); var p = data.chapter(player.getUUID());
        if (s == null || index < 0 || index >= MISSIONS.length || p.accepted() != index) return false;
        var m = MISSIONS[index];
        if (progress(player, s, m, p) < m.target()) return false;
        data.putChapter(player.getUUID(), p.claim(index));
        var items = new ArrayList<ItemStack>();
        items.add(new ItemStack(Items.EMERALD, m.emeralds()));
        items.add(LootChests.chest(m.chest()));
        if (m.whetstones() > 0) items.add(new ItemStack(KingdomItems.ROYAL_WHETSTONE.get(), m.whetstones()));
        RewardCeremony.celebrate(player, "quest", Component.translatable("chapter.livingkingdoms." + m.id() + ".title"), m.emeralds(), 0, 40, items, index == MISSIONS.length - 1, m.chest());
        KingdomProgressService.award(player, s.id(), m.kingdomXp());
        return true;
    }

    public static boolean handle(ServerPlayer player, int index, boolean claim) {
        boolean result = claim ? claim(player, index) : accept(player, index);
        PacketDistributor.sendToPlayer(player, new RpgPayloads.Chapters(snapshot(player)));
        return result;
    }

    /** Hostile kills inside the player's own kingdom count toward an accepted hunt. */
    public static void onDeath(LivingDeathEvent event) {
        if (event.isCanceled() || !(event.getSource().getEntity() instanceof ServerPlayer player) || !(event.getEntity() instanceof Enemy)) return;
        var data = RpgSavedData.get(player.server); var p = data.chapter(player.getUUID());
        if (p.accepted() < 0 || MISSIONS[p.accepted()].goal() != KingdomMission.Goal.KILLS) return;
        var victim = event.getEntity();
        var s = SettlementSavedData.get(player.server).alliedAt(victim.level().dimension().location().toString(), victim.getBlockX(), victim.getBlockZ()).orElse(null);
        if (s == null || !KingdomOnboardingService.canHelp(player, s)) return;
        data.putChapter(player.getUUID(), p.kill());
        int now = p.kills() + 1, target = MISSIONS[p.accepted()].target();
        if (now == target) player.displayClientMessage(Component.translatable("chapter.livingkingdoms.ready").withStyle(net.minecraft.ChatFormatting.GREEN), false);
        else if (now % 5 == 0) player.displayClientMessage(Component.translatable("chapter.livingkingdoms.kills", now, target), true);
    }
}
