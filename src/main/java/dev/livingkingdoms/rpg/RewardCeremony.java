package dev.livingkingdoms.rpg;

import dev.livingkingdoms.item.KingdomItems;
import dev.livingkingdoms.rpg.domain.QuestBonus;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;
import java.util.ArrayList;
import java.util.List;

/**
 * Grants the derived RPG bonus after a one-time quest/challenge receipt has already been committed, then
 * celebrates it: sound, particles and a client banner. Items that do not fit are dropped at the player's
 * feet (never voided). Call only from the server thread, after the authoritative completion succeeded.
 */
public final class RewardCeremony {
    private RewardCeremony() {}

    public static void questClaimed(ServerPlayer player, java.util.UUID settlement, boolean regional, String titleKey,
                                    int emeralds, int reputation, QuestBonus bonus) {
        if (settlement != null) KingdomProgressService.award(player, settlement, Math.max(10, bonus.experience()));
        if (regional) ContractService.progress(player, dev.livingkingdoms.rpg.domain.Contract.Kind.QUEST, 1);
        var items = new ArrayList<ItemStack>();
        // Variable reward: regional requests have a small chance of a bonus chest.
        if (regional && player.getRandom().nextFloat() < 0.12F)
            items.add(LootChests.chest(dev.livingkingdoms.rpg.domain.LootRarity.roll(new java.util.Random(player.getRandom().nextLong()),
                    settlement == null ? 0 : KingdomProgressService.luck(player.server, settlement), dev.livingkingdoms.rpg.domain.LootRarity.COMMON)));
        if (bonus.whetstones() > 0) items.add(new ItemStack(KingdomItems.ROYAL_WHETSTONE.get(), bonus.whetstones()));
        for (int i = 0; i < bonus.tomes(); i++) items.add(KingdomTomes.roll(player.serverLevel(), player.getRandom()));
        celebrate(player, "quest", Component.translatable(titleKey), emeralds, reputation, bonus.experience(), items, false);
    }

    /** Gives items/experience and sends the banner. {@code emeralds}/{@code reputation} are display-only here. */
    public static void celebrate(ServerPlayer player, String kind, Component title, int emeralds, int reputation,
                                 int experience, List<ItemStack> items, boolean epic) {
        celebrate(player, kind, title, emeralds, reputation, experience, items, epic, null);
    }

    /** {@code rarity} (nullable) colors the banner and particles for loot chests. */
    public static void celebrate(ServerPlayer player, String kind, Component title, int emeralds, int reputation,
                                 int experience, List<ItemStack> items, boolean epic, dev.livingkingdoms.rpg.domain.LootRarity rarity) {
        if (!player.server.isSameThread()) throw new IllegalStateException("Rewards are granted on the server thread");
        if (experience > 0) player.giveExperiencePoints(experience);
        var names = new ListTag();
        for (var stack : items) {
            if (stack.isEmpty()) continue;
            names.add(StringTag.valueOf(Component.Serializer.toJson(
                    Component.literal(stack.getCount() + "× ").append(stack.getHoverName()), player.registryAccess())));
            var copy = stack.copy();
            if (!player.getInventory().add(copy) && !copy.isEmpty()) player.drop(copy, false);
        }
        player.inventoryMenu.broadcastChanges();
        var level = player.serverLevel();
        level.playSound(null, player.blockPosition(), epic ? SoundEvents.UI_TOAST_CHALLENGE_COMPLETE : SoundEvents.PLAYER_LEVELUP,
                SoundSource.PLAYERS, epic ? 0.9F : 0.7F, epic ? 1.0F : 1.25F);
        level.playSound(null, player.blockPosition(), SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.PLAYERS, 1F, 1.4F);
        level.sendParticles(ParticleTypes.HAPPY_VILLAGER, player.getX(), player.getY() + 1.1, player.getZ(), 16, 0.6, 0.6, 0.6, 0.02);
        level.sendParticles(epic ? ParticleTypes.TOTEM_OF_UNDYING : ParticleTypes.WAX_OFF, player.getX(), player.getY() + 1.4, player.getZ(),
                epic ? 40 : 12, 0.5, 0.7, 0.5, epic ? 0.35 : 0.05);
        if (rarity != null) {
            int c = rarity.color();
            level.sendParticles(new net.minecraft.core.particles.DustParticleOptions(new org.joml.Vector3f(((c >> 16) & 255) / 255F, ((c >> 8) & 255) / 255F, (c & 255) / 255F), 1.4F),
                    player.getX(), player.getY() + 1.2, player.getZ(), 30 + rarity.ordinal() * 20, 0.6, 0.6, 0.6, 0.1);
        }
        var tag = new CompoundTag();
        tag.putString("kind", kind);
        if (rarity != null) tag.putString("rarity", rarity.id());
        tag.putString("title", Component.Serializer.toJson(title, player.registryAccess()));
        tag.putInt("emeralds", emeralds); tag.putInt("reputation", reputation); tag.putInt("experience", experience);
        tag.putBoolean("epic", epic); tag.put("items", names);
        PacketDistributor.sendToPlayer(player, new RpgPayloads.RewardBanner(tag));
    }
}
