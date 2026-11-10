package dev.livingkingdoms.rpg;

import dev.livingkingdoms.item.KingdomItems;
import dev.livingkingdoms.onboarding.KingdomOnboardingService;
import dev.livingkingdoms.rpg.domain.LootRarity;
import dev.livingkingdoms.settlement.domain.Settlement;
import dev.livingkingdoms.settlement.persistence.SettlementSavedData;
import dev.livingkingdoms.ui.VillageNames;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.npc.WanderingTrader;
import net.minecraft.world.entity.projectile.FireworkRocketEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.FireworkExplosion;
import net.minecraft.world.item.component.Fireworks;
import net.minecraft.world.item.trading.ItemCost;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import java.util.*;

/**
 * Timed kingdom events, announced a minute ahead with a HUD countdown: a Festival (double quest bonus XP and
 * contract emeralds, fireworks), a Merchant Caravan (rare wares for emeralds) and a Treasure Hunt (a chest
 * hidden in the territory; the HUD points the way). Transient by design: a restart simply reschedules.
 */
public final class EventService {
    public enum Kind { FESTIVAL, CARAVAN, TREASURE; public String id() { return name().toLowerCase(Locale.ROOT); } }
    public static final long WARNING = 1200, DURATION = 6000, MIN_GAP = 9600, MAX_GAP = 18000;
    public static final String CARAVAN_TAG = "livingkingdoms:caravan";
    private static final int MAX_SETTLEMENTS = 256;
    private static final class State {
        Kind kind; long startsAt, endsAt; boolean announced, started; UUID trader; BlockPos treasure; boolean found;
    }
    private static final Map<UUID, State> STATES = new LinkedHashMap<>();
    private EventService() {}

    public static Optional<Kind> active(MinecraftServer server, UUID settlement) {
        var state = STATES.get(settlement); long now = now(server);
        return state != null && state.started && now < state.endsAt ? Optional.of(state.kind) : Optional.empty();
    }
    public static boolean festival(MinecraftServer server, UUID settlement) { return active(server, settlement).filter(k -> k == Kind.FESTIVAL).isPresent(); }

    /** HUD fragment: kind, phase and seconds; for a hunt, the direction to the chest. */
    public static CompoundTag hud(ServerPlayer player, UUID settlement) {
        var tag = new CompoundTag(); var state = STATES.get(settlement); long now = now(player.server);
        if (state == null || !state.announced) return tag;
        tag.putString("kind", state.kind.id());
        tag.putString("phase", state.started ? "active" : "upcoming");
        tag.putLong("seconds", Math.max(0, ((state.started ? state.endsAt : state.startsAt) - now) / 20));
        if (state.started && state.kind == Kind.TREASURE && state.treasure != null && !state.found) {
            double dx = state.treasure.getX() - player.getX(), dz = state.treasure.getZ() - player.getZ();
            tag.putInt("distance", (int) Math.sqrt(dx * dx + dz * dz));
            tag.putString("direction", compass(dx, dz));
        }
        return tag;
    }

    static String compass(double dx, double dz) {
        double angle = Math.toDegrees(Math.atan2(dx, -dz)); // 0 = north
        String[] names = {"n", "ne", "e", "se", "s", "sw", "w", "nw"};
        return names[(int) Math.floorMod(Math.round(angle / 45), 8)];
    }

    /** Forces an event to begin now (development command and tests). */
    /** A fuzzy area around the hidden treasure (never its exact spot) for the kingdom map. */
    public static Optional<net.minecraft.core.BlockPos> treasureHint(UUID settlement) {
        var state = STATES.get(settlement);
        if (state == null || !state.started || state.kind != Kind.TREASURE || state.treasure == null || state.found) return Optional.empty();
        int seed = state.treasure.hashCode();
        return Optional.of(state.treasure.offset(Math.floorMod(seed, 17) - 8, 0, Math.floorMod(seed >> 8, 17) - 8));
    }

    public static boolean trigger(ServerLevel level, Settlement settlement, Kind kind) {
        var state = new State(); long now = now(level.getServer());
        state.kind = kind; state.startsAt = now; state.endsAt = now + DURATION; state.announced = true;
        if (!STATES.containsKey(settlement.id()) && STATES.size() >= MAX_SETTLEMENTS) return false;
        var old = STATES.put(settlement.id(), state);
        if (old != null && old.started) finish(level, settlement, old);
        start(level, settlement, state);
        return true;
    }

    public static void onServerTick(ServerTickEvent.Post event) {
        var server = event.getServer();
        if (server.getTickCount() % 20 != 0) return;
        long now = now(server);
        var settlements = SettlementSavedData.get(server);
        // Only kingdoms with a member present get events; bounded by online players.
        var present = new LinkedHashSet<Settlement>();
        for (var player : server.getPlayerList().getPlayers())
            settlements.alliedAt(player.level().dimension().location().toString(), player.getBlockX(), player.getBlockZ())
                    .filter(s -> KingdomOnboardingService.canHelp(player, s)).ifPresent(present::add);
        for (var settlement : present) {
            var state = STATES.get(settlement.id());
            if (state == null) {
                if (STATES.size() >= MAX_SETTLEMENTS) continue;
                state = new State(); state.kind = Kind.values()[server.overworld().getRandom().nextInt(Kind.values().length)];
                state.startsAt = now + MIN_GAP + server.overworld().getRandom().nextInt((int) (MAX_GAP - MIN_GAP));
                state.endsAt = state.startsAt + DURATION; STATES.put(settlement.id(), state);
            }
        }
        for (var entry : List.copyOf(STATES.entrySet())) {
            var settlement = settlements.get(entry.getKey()).orElse(null); var state = entry.getValue();
            if (settlement == null) { STATES.remove(entry.getKey()); continue; }
            var level = level(server, settlement);
            if (level == null) continue;
            if (!state.announced && now >= state.startsAt - WARNING && present.contains(settlement)) {
                state.announced = true;
                broadcast(level, settlement, Component.translatable("event.livingkingdoms.upcoming", Component.translatable("event.livingkingdoms." + state.kind.id())).withStyle(ChatFormatting.GOLD));
                level.playSound(null, center(settlement), SoundEvents.BELL_BLOCK, SoundSource.BLOCKS, 2F, 1.2F);
            }
            if (!state.started && now >= state.startsAt) {
                if (!state.announced) { state.startsAt = now + WARNING; state.endsAt = state.startsAt + DURATION; continue; }
                start(level, settlement, state);
            }
            if (state.started && now < state.endsAt) tickActive(level, settlement, state, now);
            if (state.started && now >= state.endsAt) { finish(level, settlement, state); STATES.remove(entry.getKey()); }
        }
    }

    private static void start(ServerLevel level, Settlement settlement, State state) {
        state.started = true;
        broadcast(level, settlement, Component.translatable("event.livingkingdoms.started." + state.kind.id(), VillageNames.display(settlement)).withStyle(ChatFormatting.GOLD));
        var center = center(settlement);
        switch (state.kind) {
            case CARAVAN -> {
                var trader = EntityType.WANDERING_TRADER.create(level);
                if (trader != null) {
                    var at = surface(level, center.offset(3, 0, 3));
                    trader.moveTo(at.getX() + .5, at.getY(), at.getZ() + .5, 0, 0);
                    trader.getPersistentData().putBoolean(CARAVAN_TAG, true);
                    trader.setDespawnDelay((int) DURATION + 200);
                    trader.setCustomName(Component.translatable("event.livingkingdoms.caravan_name").withStyle(ChatFormatting.GOLD));
                    var offers = trader.getOffers(); offers.clear();
                    offers.add(new MerchantOffer(new ItemCost(Items.EMERALD, 8), new ItemStack(KingdomItems.ROYAL_WHETSTONE.get()), 6, 5, 0.05F));
                    offers.add(new MerchantOffer(new ItemCost(Items.EMERALD, 20), LootChests.chest(LootRarity.RARE), 2, 10, 0.05F));
                    offers.add(new MerchantOffer(new ItemCost(Items.EMERALD, 45), LootChests.chest(LootRarity.EPIC), 1, 20, 0.05F));
                    offers.add(new MerchantOffer(new ItemCost(Items.EMERALD, 14), KingdomTomes.roll(level, level.getRandom()), 2, 10, 0.05F));
                    offers.add(new MerchantOffer(new ItemCost(Items.EMERALD, 4), new ItemStack(Items.GOLDEN_CARROT, 8), 8, 2, 0.05F));
                    offers.add(new MerchantOffer(new ItemCost(Items.EMERALD, 24), new ItemStack(Items.TOTEM_OF_UNDYING), 1, 15, 0.05F));
                    level.addFreshEntity(trader); state.trader = trader.getUUID();
                }
            }
            case TREASURE -> {
                var random = level.getRandom(); int radius = Math.max(16, settlement.territory().radius() - 8);
                for (int attempt = 0; attempt < 16 && state.treasure == null; attempt++) {
                    double angle = random.nextDouble() * Math.PI * 2; int near = Math.min(radius - 4, Math.max(28, radius * 3 / 5)); int distance = near + random.nextInt(Math.max(1, radius - near));
                    var column = center.offset((int) (Math.cos(angle) * distance), 0, (int) (Math.sin(angle) * distance));
                    if (!level.hasChunkAt(column)) continue;
                    var at = surface(level, column);
                    if (!level.getBlockState(at).isAir() || !level.getBlockState(at.below()).isSolidRender(level, at.below())) continue;
                    level.setBlock(at, Blocks.CHEST.defaultBlockState(), 3);
                    if (level.getBlockEntity(at) instanceof ChestBlockEntity chest) {
                        chest.setItem(4, LootChests.chest(LootRarity.RARE));
                        chest.setItem(13, new ItemStack(Items.EMERALD, 10 + random.nextInt(11)));
                        chest.setItem(22, new ItemStack(KingdomItems.ROYAL_WHETSTONE.get(), 1 + random.nextInt(2)));
                        state.treasure = at.immutable();
                    }
                }
            }
            case FESTIVAL -> level.playSound(null, center, SoundEvents.FIREWORK_ROCKET_LAUNCH, SoundSource.AMBIENT, 2F, 1F);
        }
    }

    private static void tickActive(ServerLevel level, Settlement settlement, State state, long now) {
        if (state.kind == Kind.FESTIVAL && now % 200 == 0) {
            var center = center(settlement); var random = level.getRandom();
            var rocket = new ItemStack(Items.FIREWORK_ROCKET);
            rocket.set(net.minecraft.core.component.DataComponents.FIREWORKS, new Fireworks(1, List.of(new FireworkExplosion(
                    FireworkExplosion.Shape.values()[random.nextInt(FireworkExplosion.Shape.values().length)],
                    it.unimi.dsi.fastutil.ints.IntList.of(0xFFD966, 0x6DA862, 0x4FA3E0), it.unimi.dsi.fastutil.ints.IntList.of(0xFFFFFF), true, true))));
            var at = center.offset(random.nextInt(9) - 4, 2, random.nextInt(9) - 4);
            if (level.hasChunkAt(at)) level.addFreshEntity(new FireworkRocketEntity(level, at.getX() + .5, at.getY(), at.getZ() + .5, rocket));
        }
        if (state.kind == Kind.TREASURE && state.treasure != null && !state.found && now % 40 == 0 && level.hasChunkAt(state.treasure))
            level.sendParticles(net.minecraft.core.particles.ParticleTypes.END_ROD, state.treasure.getX() + .5, state.treasure.getY() + 1.5, state.treasure.getZ() + .5, 3, .2, .6, .2, 0.01);
    }

    private static void finish(ServerLevel level, Settlement settlement, State state) {
        if (state.trader != null && level.getEntity(state.trader) instanceof WanderingTrader trader) trader.discard();
        if (state.treasure != null && !state.found && level.hasChunkAt(state.treasure) && level.getBlockState(state.treasure).is(Blocks.CHEST)
                && level.getBlockEntity(state.treasure) instanceof ChestBlockEntity chest) { chest.clearContent(); level.setBlock(state.treasure, Blocks.AIR.defaultBlockState(), 3); }
        broadcast(level, settlement, Component.translatable("event.livingkingdoms.ended", Component.translatable("event.livingkingdoms." + state.kind.id())).withStyle(ChatFormatting.GRAY));
    }

    /** Opening the hunt's chest celebrates the finder; the loot itself is in the chest. */
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        for (var entry : STATES.entrySet()) {
            var state = entry.getValue();
            if (state.kind != Kind.TREASURE || state.found || state.treasure == null || !state.treasure.equals(event.getPos())) continue;
            state.found = true;
            RewardCeremony.celebrate(player, "event", Component.translatable("event.livingkingdoms.treasure_found"), 0, 0, 30, List.of(), true);
            KingdomProgressService.award(player, entry.getKey(), 40);
            SettlementSavedData.get(player.server).get(entry.getKey()).ifPresent(s -> broadcast(player.serverLevel(), s,
                    Component.translatable("event.livingkingdoms.treasure_claimed", player.getDisplayName()).withStyle(ChatFormatting.GOLD)));
        }
    }

    /** Restart safety: an orphaned caravan trader leaves. */
    public static void onJoin(EntityJoinLevelEvent event) {
        if (!event.getLevel().isClientSide() && event.loadedFromDisk() && event.getEntity() instanceof WanderingTrader trader
                && trader.getPersistentData().getBoolean(CARAVAN_TAG)
                && STATES.values().stream().noneMatch(s -> trader.getUUID().equals(s.trader))) event.setCanceled(true);
    }

    public static void clear() { STATES.clear(); }

    private static void broadcast(ServerLevel level, Settlement settlement, Component message) {
        for (var player : level.players())
            if (settlement.territory().contains(settlement.territory().dimension(), player.getBlockX(), player.getBlockZ())) player.sendSystemMessage(message);
    }
    private static BlockPos center(Settlement s) { return new BlockPos(s.territory().x(), s.territory().y(), s.territory().z()); }
    private static BlockPos surface(ServerLevel level, BlockPos column) { return level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, column); }
    private static ServerLevel level(MinecraftServer server, Settlement s) {
        return server.getLevel(net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION,
                net.minecraft.resources.ResourceLocation.parse(s.territory().dimension())));
    }
    private static long now(MinecraftServer server) { return server.overworld().getGameTime(); }
}
