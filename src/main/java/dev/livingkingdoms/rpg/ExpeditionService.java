package dev.livingkingdoms.rpg;

import dev.livingkingdoms.citizen.CitizenService;
import dev.livingkingdoms.citizen.ImmigrationService;
import dev.livingkingdoms.citizen.domain.CitizenRole;
import dev.livingkingdoms.citizen.domain.CitizenState;
import dev.livingkingdoms.citizen.persistence.CitizenSavedData;
import dev.livingkingdoms.onboarding.KingdomOnboardingService;
import dev.livingkingdoms.profession.domain.ProfessionType;
import dev.livingkingdoms.profession.persistence.ProfessionSavedData;
import dev.livingkingdoms.rpg.domain.Bond;
import dev.livingkingdoms.rpg.domain.Expedition;
import dev.livingkingdoms.rpg.domain.LootRarity;
import dev.livingkingdoms.rpg.persistence.RpgSavedData;
import dev.livingkingdoms.settlement.persistence.SettlementSavedData;
import net.minecraft.ChatFormatting;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Send an unassigned resident away to forage, mine or explore. While away the villager is hidden in place
 * (invisible, frozen, invulnerable) so identity, home and saves stay intact. On return the sender receives the
 * loot with a banner — or on their next login — and explorations may bring back a survivor who asks to join.
 */
public final class ExpeditionService {
    public static final String AWAY_TAG = "livingkingdoms:expedition";
    private ExpeditionService() {}

    public static boolean away(Villager villager) { return villager.getPersistentData().getBoolean(AWAY_TAG); }

    public static boolean eligible(MinecraftServer server, UUID citizenId) {
        var citizen = CitizenSavedData.get(server).citizen(citizenId).orElse(null);
        if (citizen == null || citizen.role() == CitizenRole.MAYOR || citizen.state() != CitizenState.ACTIVE) return false;
        var job = ProfessionSavedData.get(server).profession(citizenId).orElse(null);
        return (job == null || !job.active() && job.type() != ProfessionType.MAYOR)
                && RpgSavedData.get(server).expedition(citizenId).isEmpty();
    }

    public static boolean start(ServerPlayer player, UUID settlementId, UUID citizenId, Expedition.Kind kind) {
        var settlement = SettlementSavedData.get(player.server).get(settlementId).orElse(null);
        var citizen = CitizenSavedData.get(player.server).citizen(citizenId).orElse(null);
        if (settlement == null || citizen == null || !citizen.settlementId().equals(settlementId)
                || !KingdomOnboardingService.canHelp(player, settlement) || !eligible(player.server, citizenId)) return false;
        if (!(player.serverLevel().getEntity(citizen.entityId()) instanceof Villager villager) || villager.isBaby()) return false;
        var data = RpgSavedData.get(player.server);
        if (!data.startExpedition(Expedition.start(citizenId, settlementId, player.getUUID(), kind, now(player.server)))) return false;
        player.serverLevel().sendParticles(ParticleTypes.CLOUD, villager.getX(), villager.getY() + 1, villager.getZ(), 12, .3, .5, .3, .02);
        hide(villager, true);
        player.sendSystemMessage(Component.translatable("expedition.livingkingdoms.departed", citizen.name(),
                Component.translatable("expedition.livingkingdoms.kind." + kind.id()), kind.duration() / 1200).withStyle(ChatFormatting.GOLD));
        return true;
    }

    private static void hide(Villager villager, boolean away) {
        if (away) villager.getPersistentData().putBoolean(AWAY_TAG, true); else villager.getPersistentData().remove(AWAY_TAG);
        villager.setInvisible(away); villager.setNoAi(away); villager.setInvulnerable(away); villager.setSilent(away);
        villager.getNavigation().stop();
    }

    public static void onServerTick(ServerTickEvent.Post event) {
        var server = event.getServer();
        if (server.getTickCount() % 100 != 0) return;
        long now = now(server);
        for (var expedition : RpgSavedData.get(server).expeditions()) if (expedition.due(now)) finish(server, expedition);
    }

    /** Ends an expedition now (also the operator development shortcut). */
    public static boolean finish(MinecraftServer server, Expedition expedition) {
        var data = RpgSavedData.get(server);
        if (!data.endExpedition(expedition.citizen())) return false;
        var citizen = CitizenSavedData.get(server).citizen(expedition.citizen()).orElse(null);
        var level = SettlementSavedData.get(server).get(expedition.settlement())
                .map(s -> server.getLevel(net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION,
                        net.minecraft.resources.ResourceLocation.parse(s.territory().dimension())))).orElse(null);
        if (citizen != null && level != null && level.getEntity(citizen.entityId()) instanceof Villager villager) {
            hide(villager, false);
            level.sendParticles(ParticleTypes.HAPPY_VILLAGER, villager.getX(), villager.getY() + 1, villager.getZ(), 12, .4, .5, .4, .02);
        }
        if (citizen != null) data.putBond(citizen.id(), data.bond(citizen.id()).add(Bond.EXPEDITION));
        var random = level != null ? level.getRandom() : net.minecraft.util.RandomSource.create();
        var loot = loot(level, random, expedition.kind());
        boolean rescued = false;
        if (expedition.kind().rescuePercent() > 0 && level != null && random.nextInt(100) < expedition.kind().rescuePercent()) {
            var settlement = SettlementSavedData.get(server).get(expedition.settlement()).orElse(null);
            rescued = settlement != null && ImmigrationService.attempt(level, settlement, true).isPresent();
            if (!rescued) loot.add(LootChests.chest(LootRarity.RARE));
        }
        String name = citizen == null ? "?" : citizen.name();
        var player = server.getPlayerList().getPlayer(expedition.player());
        if (player != null) {
            RewardCeremony.celebrate(player, "expedition", Component.translatable("expedition.livingkingdoms.returned", name,
                    Component.translatable("expedition.livingkingdoms.kind." + expedition.kind().id())), 0, 0, 0, loot, rescued);
            if (rescued) player.sendSystemMessage(Component.translatable("expedition.livingkingdoms.rescued", name).withStyle(ChatFormatting.LIGHT_PURPLE));
            KingdomProgressService.award(player, expedition.settlement(), 8);
        } else {
            var stacks = new ArrayList<CompoundTag>();
            for (var stack : loot) if (!stack.isEmpty()) stacks.add((CompoundTag) stack.save(server.registryAccess()));
            data.addPending(expedition.player(), stacks);
            KingdomProgressService.awardKingdom(server, expedition.settlement(), 20, null);
        }
        return true;
    }

    static ArrayList<ItemStack> loot(ServerLevel level, net.minecraft.util.RandomSource random, Expedition.Kind kind) {
        var items = new ArrayList<ItemStack>();
        switch (kind) {
            case FORAGE -> {
                items.add(new ItemStack(Items.OAK_LOG, 12 + random.nextInt(13)));
                items.add(new ItemStack(Items.WHEAT, 10 + random.nextInt(11)));
                items.add(new ItemStack(random.nextBoolean() ? Items.APPLE : Items.SWEET_BERRIES, 4 + random.nextInt(5)));
                if (random.nextFloat() < 0.3F) items.add(new ItemStack(Items.HONEYCOMB, 2 + random.nextInt(3)));
            }
            case MINE -> {
                items.add(new ItemStack(Items.COBBLESTONE, 24 + random.nextInt(25)));
                items.add(new ItemStack(Items.IRON_INGOT, 4 + random.nextInt(5)));
                items.add(new ItemStack(Items.COAL, 8 + random.nextInt(9)));
                if (random.nextFloat() < 0.35F) items.add(new ItemStack(Items.GOLD_INGOT, 2 + random.nextInt(3)));
                if (random.nextFloat() < 0.15F) items.add(new ItemStack(Items.DIAMOND, 1));
            }
            case EXPLORE -> {
                items.add(new ItemStack(Items.EMERALD, 6 + random.nextInt(7)));
                items.add(new ItemStack(Items.MAP, 1));
                if (random.nextFloat() < 0.4F && level != null) items.add(KingdomTomes.roll(level, random));
                if (random.nextFloat() < 0.25F) items.add(new ItemStack(dev.livingkingdoms.item.KingdomItems.ROYAL_WHETSTONE.get()));
            }
        }
        return items;
    }

    /** Offline senders receive their loot on login. */
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        var stacks = RpgSavedData.get(player.server).takePending(player.getUUID());
        if (stacks.isEmpty()) return;
        var items = new ArrayList<ItemStack>();
        for (var tag : stacks) ItemStack.parse(player.registryAccess(), tag).ifPresent(items::add);
        RewardCeremony.celebrate(player, "expedition", Component.translatable("expedition.livingkingdoms.while_away"), 0, 0, 0, items, false);
    }

    /** Restart/unload safety: a hidden villager whose expedition no longer exists becomes visible again. */
    public static void onJoin(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide() || !(event.getEntity() instanceof Villager villager) || !away(villager)
                || !(event.getLevel() instanceof ServerLevel level)) return;
        level.getServer().execute(() -> {
            var citizen = CitizenSavedData.get(level.getServer()).byEntity(villager.getUUID()).orElse(null);
            if (citizen == null || RpgSavedData.get(level.getServer()).expedition(citizen.id()).isEmpty()) hide(villager, false);
        });
    }

    public static List<Expedition> active(MinecraftServer server, UUID settlement) {
        return RpgSavedData.get(server).expeditions().stream().filter(e -> e.settlement().equals(settlement)).toList();
    }

    static long now(MinecraftServer server) { return server.overworld().getGameTime(); }
}
