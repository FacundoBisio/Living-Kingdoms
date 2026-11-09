package dev.livingkingdoms.rpg;

import dev.livingkingdoms.LivingKingdoms;
import dev.livingkingdoms.citizen.CitizenService;
import dev.livingkingdoms.citizen.domain.Citizen;
import dev.livingkingdoms.citizen.domain.CitizenRole;
import dev.livingkingdoms.citizen.domain.CitizenState;
import dev.livingkingdoms.citizen.persistence.CitizenSavedData;
import dev.livingkingdoms.onboarding.KingdomOnboardingService;
import dev.livingkingdoms.profession.domain.ProfessionType;
import dev.livingkingdoms.profession.persistence.ProfessionSavedData;
import dev.livingkingdoms.rpg.domain.Bond;
import dev.livingkingdoms.rpg.domain.LootRarity;
import dev.livingkingdoms.rpg.domain.PersonalRequest;
import dev.livingkingdoms.rpg.persistence.RpgSavedData;
import dev.livingkingdoms.settlement.persistence.SettlementSavedData;
import net.minecraft.ChatFormatting;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import java.util.ArrayList;
import java.util.Optional;

/**
 * Friendship with citizens. Right-click a resident (sneak-right-click a Merchant) to talk: once per day it
 * raises the bond, shows today's personal wish, and if you hold the wished items they are delivered.
 * Loyal residents give a daily gift and unlock a perk (Builders work faster, Guards gain health).
 */
public final class BondService {
    private static final ResourceLocation LOYAL_GUARD = ResourceLocation.fromNamespaceAndPath(LivingKingdoms.MOD_ID, "loyal_guard");
    public static final double LOYAL_BUILDER_SPEED = 1.2;
    private BondService() {}

    public static PersonalRequest request(net.minecraft.server.MinecraftServer server, Citizen citizen) {
        return PersonalRequest.of(citizen.id(), RpgClock.today(), profession(server, citizen));
    }

    static ProfessionType profession(net.minecraft.server.MinecraftServer server, Citizen citizen) {
        return ProfessionSavedData.get(server).profession(citizen.id()).filter(p -> p.active()).map(p -> p.type()).orElse(ProfessionType.UNASSIGNED);
    }

    public static Optional<Item> item(PersonalRequest request) {
        return BuiltInRegistries.ITEM.getOptional(ResourceLocation.parse(request.item())).filter(item -> item != Items.AIR);
    }

    public static boolean loyal(net.minecraft.server.MinecraftServer server, java.util.UUID citizen) {
        return RpgSavedData.get(server).bond(citizen).loyal();
    }

    /** Fires before Merchant commerce; consumes the interaction only when this service handled it. */
    public static void onInteract(PlayerInteractEvent.EntityInteract event) {
        if (event.getHand() != InteractionHand.MAIN_HAND || !(event.getEntity() instanceof ServerPlayer player)
                || !(event.getTarget() instanceof Villager villager) || event.isCanceled()) return;
        var citizen = CitizenSavedData.get(player.server).byEntity(villager.getUUID()).orElse(null);
        if (citizen == null || citizen.role() == CitizenRole.MAYOR || citizen.state() != CitizenState.ACTIVE) return;
        boolean merchant = profession(player.server, citizen) == ProfessionType.MERCHANT;
        // Sneak + tool is the equip gesture; merchants open the market on a plain click.
        if (player.isShiftKeyDown() && dev.livingkingdoms.npc.CitizenEquipment.giftable(player.getMainHandItem())) return;
        if (merchant != player.isShiftKeyDown()) return;
        if (talk(player, villager, citizen)) { event.setCanceled(true); event.setCancellationResult(InteractionResult.SUCCESS); }
    }

    public static void onSpecific(PlayerInteractEvent.EntityInteractSpecific event) {
        if (!(event.getTarget() instanceof Villager villager) || event.getLevel().isClientSide()) return;
        if (event.getEntity() instanceof ServerPlayer player && CitizenSavedData.get(player.server).byEntity(villager.getUUID())
                .filter(c -> c.role() != CitizenRole.MAYOR).isPresent() && profession(player.server,
                CitizenSavedData.get(player.server).byEntity(villager.getUUID()).orElseThrow()) != ProfessionType.MERCHANT && !player.isShiftKeyDown()) {
            event.setCanceled(true); event.setCancellationResult(InteractionResult.PASS);
        }
    }

    public static boolean talk(ServerPlayer player, Villager villager, Citizen citizen) {
        var settlement = SettlementSavedData.get(player.server).get(citizen.settlementId()).orElse(null);
        if (settlement == null || !settlement.faction().isAllied() || !KingdomOnboardingService.canHelp(player, settlement)) return false;
        if (ExpeditionService.away(villager)) return false;
        var data = RpgSavedData.get(player.server); long day = RpgClock.today();
        var before = data.bond(citizen.id()); var bond = before.talked(day);
        var request = PersonalRequest.of(citizen.id(), day, profession(player.server, citizen));
        var wanted = item(request).orElse(Items.BREAD);
        var held = player.getMainHandItem();
        var name = Component.literal(citizen.name()).withStyle(ChatFormatting.GOLD);
        var rewards = new ArrayList<ItemStack>();
        boolean delivered = false;
        if (bond.canFulfill(day) && held.is(wanted) && held.getCount() >= request.count()) {
            if (!player.getAbilities().instabuild) held.shrink(request.count());
            bond = bond.fulfilled(day); delivered = true;
            rewards.add(new ItemStack(Items.EMERALD, 3));
            if (player.getRandom().nextFloat() < 0.15F) rewards.add(LootChests.chest(LootRarity.COMMON));
        }
        if (bond.giftReady(day)) { rewards.add(gift(profession(player.server, citizen))); bond = bond.gifted(day); }
        if (bond.devotedRewardReady()) { rewards.add(LootChests.chest(LootRarity.EPIC)); bond = bond.devotedPaid(); }
        data.putBond(citizen.id(), bond);
        // Dialogue line: greeting by bond level, then today's wish or thanks.
        var level = bond.level();
        player.sendSystemMessage(Component.translatable("bond.livingkingdoms.greeting." + level.id(), name).withStyle(ChatFormatting.WHITE)
                .append(Component.literal("  " + hearts(bond.value())).withStyle(ChatFormatting.RED)));
        if (delivered) player.sendSystemMessage(Component.translatable("bond.livingkingdoms.thanks", name).withStyle(ChatFormatting.GREEN));
        else if (bond.canFulfill(day)) player.sendSystemMessage(Component.translatable("bond.livingkingdoms.wish", name, request.count(), wanted.getDescription())
                .withStyle(ChatFormatting.GRAY));
        else player.sendSystemMessage(Component.translatable("bond.livingkingdoms.content", name).withStyle(ChatFormatting.GRAY));
        var world = (ServerLevel) villager.level();
        if (bond.value() > before.value()) world.sendParticles(ParticleTypes.HEART, villager.getX(), villager.getY() + 2.1, villager.getZ(), delivered ? 6 : 2, .3, .2, .3, 0);
        villager.playAmbientSound();
        if (level != before.level()) {
            player.sendSystemMessage(Component.translatable("bond.livingkingdoms.level_up", name, Component.translatable("bond.livingkingdoms.level." + level.id()))
                    .withStyle(ChatFormatting.LIGHT_PURPLE));
            if (bond.loyal() && !before.loyal())
                player.sendSystemMessage(Component.translatable("bond.livingkingdoms.perk." + profession(player.server, citizen).name().toLowerCase(java.util.Locale.ROOT))
                        .withStyle(ChatFormatting.AQUA));
            applyPerks(villager, citizen, bond);
        }
        if (delivered || !rewards.isEmpty()) {
            RewardCeremony.celebrate(player, "quest", Component.translatable("bond.livingkingdoms.reward_title", citizen.name()),
                    delivered ? 3 : 0, 0, delivered ? 20 : 0, rewards, false);
            if (delivered) KingdomProgressService.award(player, settlement.id(), 15);
        }
        return true;
    }

    private static ItemStack gift(ProfessionType profession) {
        return switch (profession) {
            case FARMER -> new ItemStack(Items.BREAD, 4);
            case BUILDER -> new ItemStack(Items.TORCH, 12);
            case GUARD -> new ItemStack(Items.ARROW, 12);
            case LUMBERJACK -> new ItemStack(Items.OAK_LOG, 8);
            case MINER -> new ItemStack(Items.COAL, 8);
            case BLACKSMITH -> new ItemStack(Items.IRON_NUGGET, 12);
            case MERCHANT -> new ItemStack(Items.EMERALD, 2);
            default -> new ItemStack(Items.COOKIE, 4);
        };
    }

    static String hearts(int value) {
        int full = value / 20; var builder = new StringBuilder();
        for (int i = 0; i < 5; i++) builder.append(i < full ? '❤' : '♡');
        return builder.toString();
    }

    /** Loyal Guards gain +6 max health; the modifier is removed for anyone else. */
    public static void applyPerks(Villager villager, Citizen citizen, Bond bond) {
        if (!(villager.level() instanceof ServerLevel level)) return;
        var health = villager.getAttribute(Attributes.MAX_HEALTH);
        if (health == null) return;
        boolean guard = profession(level.getServer(), citizen) == ProfessionType.GUARD;
        if (guard && bond.loyal()) health.addOrReplacePermanentModifier(new AttributeModifier(LOYAL_GUARD, 6, AttributeModifier.Operation.ADD_VALUE));
        else if (health.getModifier(LOYAL_GUARD) != null) { health.removeModifier(LOYAL_GUARD); villager.setHealth(Math.min(villager.getHealth(), villager.getMaxHealth())); }
    }

    public static void onJoin(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide() || !(event.getEntity() instanceof Villager villager)
                || !villager.getPersistentData().hasUUID(CitizenService.CITIZEN_ID_KEY) || !(event.getLevel() instanceof ServerLevel level)) return;
        level.getServer().execute(() -> CitizenSavedData.get(level.getServer()).byEntity(villager.getUUID())
                .ifPresent(c -> applyPerks(villager, c, RpgSavedData.get(level.getServer()).bond(c.id()))));
    }
}
