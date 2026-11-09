package dev.livingkingdoms.npc;

import dev.livingkingdoms.citizen.persistence.CitizenSavedData;
import dev.livingkingdoms.item.KingdomItems;
import dev.livingkingdoms.onboarding.KingdomOnboardingService;
import dev.livingkingdoms.profession.GuardEquipment;
import dev.livingkingdoms.profession.domain.ProfessionType;
import dev.livingkingdoms.profession.persistence.ProfessionSavedData;
import dev.livingkingdoms.rpg.KingdomProgressService;
import dev.livingkingdoms.rpg.RewardCeremony;
import dev.livingkingdoms.settlement.persistence.SettlementSavedData;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.ItemStack;
import java.util.List;

/**
 * Sneak-use a sword, axe or pickaxe on one of your Guards or Builders to hand it over. Builders use pickaxes
 * to clear obstacles faster and swords to strike monsters in a construction site; Guards fight with it.
 * Player-given gear always drops on death, and service-issued gear is never handed back (no duplication).
 */
public final class CitizenEquipment {
    private CitizenEquipment() {}

    public static boolean giftable(ItemStack stack) {
        return !stack.isEmpty() && (stack.is(ItemTags.SWORDS) || stack.is(ItemTags.AXES) || stack.is(ItemTags.PICKAXES));
    }

    public static boolean equip(ServerPlayer player, Villager villager) {
        var held = player.getMainHandItem();
        if (!giftable(held)) return false;
        var citizen = CitizenSavedData.get(player.server).byEntity(villager.getUUID()).orElse(null);
        if (citizen == null) return false;
        var settlement = SettlementSavedData.get(player.server).get(citizen.settlementId()).orElse(null);
        if (settlement == null || !KingdomOnboardingService.canHelp(player, settlement)) return false;
        var job = ProfessionSavedData.get(player.server).profession(citizen.id()).filter(p -> p.active()).orElse(null);
        if (job == null || job.type() != ProfessionType.GUARD && job.type() != ProfessionType.BUILDER) {
            player.displayClientMessage(Component.translatable("citizen.livingkingdoms.equip.only_workers"), true);
            return true;
        }
        var previous = villager.getMainHandItem();
        var gift = held.copyWithCount(1);
        if (!player.getAbilities().instabuild) held.shrink(1);
        if (!previous.isEmpty() && !GuardEquipment.issued(previous) && !player.getInventory().add(previous.copy())) player.drop(previous.copy(), false);
        villager.setItemSlot(EquipmentSlot.MAINHAND, gift);
        villager.setDropChance(EquipmentSlot.MAINHAND, 2.0F);
        villager.level().playSound(null, villager.blockPosition(), SoundEvents.ARMOR_EQUIP_IRON.value(), SoundSource.NEUTRAL, 1F, 1F);
        player.displayClientMessage(Component.translatable("citizen.livingkingdoms.equip.done", citizen.name(), gift.getHoverName()), true);
        // One-time kingdom task: arming your first Guard with a sword.
        if (job.type() == ProfessionType.GUARD && gift.is(ItemTags.SWORDS)
                && dev.livingkingdoms.chronicle.ChronicleAdvancements.grant(player, "armed_guard")) {
            RewardCeremony.celebrate(player, "quest", Component.translatable("advancement.livingkingdoms.armed_guard.title"), 0, 0, 40,
                    List.of(new ItemStack(KingdomItems.ROYAL_WHETSTONE.get())), false);
            KingdomProgressService.award(player, settlement.id(), 40);
        }
        return true;
    }
}
