package dev.livingkingdoms.rpg;

import com.mojang.logging.LogUtils;
import dev.livingkingdoms.LivingKingdoms;
import dev.livingkingdoms.defense.DefenseService;
import dev.livingkingdoms.defense.domain.SettlementSafety;
import dev.livingkingdoms.item.KingdomItems;
import dev.livingkingdoms.onboarding.KingdomOnboardingService;
import dev.livingkingdoms.quest.expansion.domain.QuestTemplate;
import dev.livingkingdoms.quest.persistence.QuestSavedData;
import dev.livingkingdoms.rpg.domain.ChallengeTier;
import dev.livingkingdoms.settlement.domain.Settlement;
import dev.livingkingdoms.settlement.persistence.SettlementSavedData;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.BossEvent;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.entity.raid.Raider;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.neoforge.event.EventHooks;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import java.util.*;

/**
 * Player-chosen wave trials ("Desafíos del reino"). Runs are transient and bounded: at most
 * {@link #MAX_RUNS} at once, each tracking only its own spawned members by UUID, checked every 10 ticks.
 * Members carry a run tag; after a restart orphaned members are refused on join, so a crash never leaves
 * a hostile horde in a village. Rewards are granted once, on the server thread, at victory.
 */
public final class ChallengeService {
    public static final String RUN_TAG = "livingkingdoms:challenge_run";
    private static final int MAX_RUNS = 8, MAX_COOLDOWNS = 512, ARENA_RADIUS = 48;
    private static final ResourceLocation HEALTH_ID = ResourceLocation.fromNamespaceAndPath(LivingKingdoms.MOD_ID, "challenge_health");
    private static final ResourceLocation BOSS_HEALTH_ID = ResourceLocation.fromNamespaceAndPath(LivingKingdoms.MOD_ID, "challenge_boss_health");
    private static final Map<UUID, Run> RUNS = new LinkedHashMap<>();
    private static final Map<UUID, Long> COOLDOWNS = new LinkedHashMap<>();

    private static final class Run {
        final UUID id = UUID.randomUUID(), player, settlement; final ChallengeTier tier; final String dimension;
        final BlockPos center; final long deadline; final ServerBossEvent bar;
        final java.lang.ref.WeakReference<ServerPlayer> handle;
        final Set<UUID> alive = new LinkedHashSet<>(); int wave = -1, waveSize, pending, spawnedInWave; boolean boss;
        BlockPos gate;
        Run(ServerPlayer owner, UUID settlement, ChallengeTier tier, String dimension, BlockPos center, long deadline) {
            this.player = owner.getUUID(); this.handle = new java.lang.ref.WeakReference<>(owner); this.settlement = settlement; this.tier = tier; this.dimension = dimension;
            this.center = center; this.deadline = deadline;
            this.bar = new ServerBossEvent(Component.empty(), BossEvent.BossBarColor.RED, BossEvent.BossBarOverlay.NOTCHED_10);
        }
    }
    private ChallengeService() {}

    /** Board snapshot fragment: unlock state per tier, cooldown and whether a run is active. */
    public static CompoundTag status(ServerPlayer player, Settlement settlement) {
        var tag = new CompoundTag(); var tiers = new ListTag();
        long now = now(player.serverLevel());
        long cooldown = Math.max(0, COOLDOWNS.getOrDefault(player.getUUID(), 0L) - now);
        boolean any = false;
        for (var tier : ChallengeTier.values()) {
            var t = new CompoundTag(); t.putString("id", tier.id());
            boolean unlocked = unlocked(player, settlement, tier); any |= unlocked;
            t.putBoolean("unlocked", unlocked); t.putString("requires", required(tier).titleKey());
            t.putInt("waves", tier.waves()); t.putInt("enemies", tier.totalEnemies()); t.putBoolean("boss", tier.boss());
            t.putInt("emeralds", tier.emeralds()); t.putInt("whetstones", tier.whetstones()); t.putInt("tomes", tier.tomes());
            t.putString("reward", tier.reward().name().toLowerCase(Locale.ROOT)); t.putInt("minutes", (int) (tier.timeLimitTicks() / 1200));
            tiers.add(t);
        }
        tag.put("tiers", tiers); tag.putBoolean("unlocked", any);
        tag.putLong("cooldown", cooldown / 20); tag.putBoolean("active", RUNS.containsKey(player.getUUID()));
        return tag;
    }

    private static QuestTemplate required(ChallengeTier tier) {
        return switch (tier) { case SKIRMISH -> QuestTemplate.MAIN_IRON; case SIEGE -> QuestTemplate.MAIN_PATROL; case WARLORD -> QuestTemplate.MAIN_RETURN; };
    }
    private static boolean unlocked(ServerPlayer player, Settlement settlement, ChallengeTier tier) {
        return QuestSavedData.get(player.server).completedMain(player.getUUID(), settlement.id()).contains(required(tier));
    }

    public static boolean start(ServerPlayer player, ChallengeTier tier, boolean debug) {
        Objects.requireNonNull(tier, "tier");
        if (!player.server.isSameThread() || !player.isAlive() || player.isSpectator()) return false;
        var level = player.serverLevel(); String dimension = level.dimension().location().toString();
        var settlement = SettlementSavedData.get(player.server).alliedAt(dimension, player.getBlockX(), player.getBlockZ()).orElse(null);
        if (settlement == null || !KingdomOnboardingService.canHelp(player, settlement)) return refuse(player, "outside");
        if (RUNS.containsKey(player.getUUID())) return refuse(player, "running");
        if (!debug && !unlocked(player, settlement, tier)) return refuse(player, "locked");
        if (!debug && COOLDOWNS.getOrDefault(player.getUUID(), 0L) > now(level)) return refuse(player, "cooldown");
        if (DefenseService.status(player.server, settlement.id()) == SettlementSafety.THREATENED) return refuse(player, "threatened");
        if (RUNS.size() >= MAX_RUNS) return refuse(player, "busy");
        var run = new Run(player, settlement.id(), tier, dimension, player.blockPosition(), now(level) + tier.timeLimitTicks());
        RUNS.put(player.getUUID(), run);
        run.bar.addPlayer(player);
        level.playSound(null, player.blockPosition(), SoundEvents.RAID_HORN.value(), SoundSource.HOSTILE, 1.5F, 1.0F);
        player.sendSystemMessage(Component.translatable("challenge.livingkingdoms.started", Component.translatable("challenge.livingkingdoms.tier." + tier.id()))
                .withStyle(ChatFormatting.GOLD));
        nextWave(level, player, run);
        return true;
    }

    private static boolean refuse(ServerPlayer player, String reason) {
        player.displayClientMessage(Component.translatable("challenge.livingkingdoms.refused." + reason), true);
        return false;
    }

    public static void onServerTick(ServerTickEvent.Post event) {
        if (RUNS.isEmpty() || event.getServer().getTickCount() % 10 != 0) return;
        for (var run : List.copyOf(RUNS.values())) {
            try { tick(event.getServer(), run); }
            catch (RuntimeException failure) {
                LogUtils.getLogger().warn("Challenge run {} aborted: {}", run.id, failure.toString());
                end(event.getServer(), run, false, "error");
            }
        }
    }

    private static void tick(net.minecraft.server.MinecraftServer server, Run run) {
        var player = owner(server, run);
        if (player == null || !player.isAlive()) { end(server, run, false, "fallen"); return; }
        var level = player.serverLevel();
        if (!level.dimension().location().toString().equals(run.dimension)
                || player.blockPosition().distSqr(run.center) > (double) ARENA_RADIUS * ARENA_RADIUS) { end(server, run, false, "fled"); return; }
        if (now(level) > run.deadline) { end(server, run, false, "timeout"); return; }
        run.alive.removeIf(id -> !(level.getEntity(id) instanceof Mob mob) || !mob.isAlive());
        for (var id : run.alive) if (level.getEntity(id) instanceof Mob mob && mob.getTarget() != player) mob.setTarget(player);
        // Like a village raid, wave members march in from one side a few at a time.
        if (run.pending > 0) {
            if (level.getGameTime() % 30 < 10) trickle(level, run, run.tier.rank() >= 3 ? 2 : 1);
            updateBar(run, level); return;
        }
        if (run.alive.isEmpty()) {
            if (run.wave + 1 < run.tier.waves()) nextWave(level, player, run);
            else if (run.tier.boss() && !run.boss) spawnBoss(level, player, run);
            else { end(server, run, true, "victory"); return; }
        }
        updateBar(run, level);
    }

    private static void nextWave(ServerLevel level, ServerPlayer player, Run run) {
        run.wave++;
        run.pending = run.tier.enemies(run.wave); run.spawnedInWave = 0;
        run.waveSize = Math.max(1, run.pending);
        double angle = level.getRandom().nextDouble() * Math.PI * 2;
        run.gate = run.center.offset((int) Math.round(Math.cos(angle) * 20), 0, (int) Math.round(Math.sin(angle) * 20));
        trickle(level, run, 2);
        player.displayClientMessage(Component.translatable("challenge.livingkingdoms.wave", run.wave + 1, run.tier.waves()).withStyle(ChatFormatting.RED), true);
        level.playSound(null, player.blockPosition(), SoundEvents.RAID_HORN.value(), SoundSource.HOSTILE, 0.8F, 1.2F + run.wave * 0.1F);
    }

    /** Spawns up to {@code count} pending members near the wave's gate; members that cannot be placed are dropped. */
    private static void trickle(ServerLevel level, Run run, int count) {
        for (int i = 0; i < count && run.pending > 0; i++) {
            run.pending--;
            var mob = member(level, run.tier, run.wave, run.spawnedInWave++);
            if (mob != null && placeNear(level, run, mob)) prepare(level, run, mob, false);
        }
    }

    private static boolean placeNear(ServerLevel level, Run run, Mob mob) {
        var random = level.getRandom();
        for (int attempt = 0; attempt < 10; attempt++) {
            int x = run.gate.getX() + random.nextInt(7) - 3, z = run.gate.getZ() + random.nextInt(7) - 3;
            if (!level.hasChunkAt(new BlockPos(x, run.center.getY(), z))) continue;
            var ground = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, new BlockPos(x, 0, z));
            if (Math.abs(ground.getY() - run.center.getY()) > 10 || !level.getFluidState(ground.below()).isEmpty()) continue;
            mob.moveTo(ground.getX() + .5, ground.getY(), ground.getZ() + .5, random.nextFloat() * 360F, 0F);
            if (level.noCollision(mob)) return true;
        }
        // The gate side is blocked (water, cliff): fall back to the ring around the arena.
        return place(level, run, mob, run.spawnedInWave, Math.max(1, run.waveSize));
    }

    private static void spawnBoss(ServerLevel level, ServerPlayer player, Run run) {
        run.boss = true;
        var mob = EntityType.VINDICATOR.create(level);
        if (mob != null && place(level, run, mob, 0, 1)) {
            prepare(level, run, mob, true);
            mob.setCustomName(Component.translatable("challenge.livingkingdoms.boss." + run.tier.id()).withStyle(ChatFormatting.DARK_RED, ChatFormatting.BOLD));
            mob.setCustomNameVisible(true);
            mob.setGlowingTag(true);
            mob.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(run.tier.rank() >= 3 ? Items.NETHERITE_AXE : Items.DIAMOND_AXE));
            mob.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.DIAMOND_CHESTPLATE));
            for (var slot : EquipmentSlot.values()) mob.setDropChance(slot, 0F);
            var health = mob.getAttribute(Attributes.MAX_HEALTH);
            if (health != null) {
                health.addOrReplacePermanentModifier(new AttributeModifier(BOSS_HEALTH_ID, run.tier.rank() >= 3 ? 3.0 : 1.5, AttributeModifier.Operation.ADD_MULTIPLIED_BASE));
                mob.setHealth(mob.getMaxHealth());
            }
        }
        run.waveSize = Math.max(1, run.alive.size());
        player.displayClientMessage(Component.translatable("challenge.livingkingdoms.boss_arrives").withStyle(ChatFormatting.DARK_RED), true);
        level.playSound(null, player.blockPosition(), SoundEvents.EVOKER_PREPARE_WOLOLO, SoundSource.HOSTILE, 1.2F, 0.6F);
    }

    private static Mob member(ServerLevel level, ChallengeTier tier, int wave, int index) {
        return switch (tier) {
            case SKIRMISH -> index % 2 == 0 ? EntityType.ZOMBIE.create(level) : EntityType.SKELETON.create(level);
            case SIEGE -> switch (index % 4) { case 0 -> EntityType.VINDICATOR.create(level); case 1 -> EntityType.PILLAGER.create(level);
                case 2 -> EntityType.ZOMBIE.create(level); default -> EntityType.SKELETON.create(level); };
            case WARLORD -> switch (index % 4) { case 0, 2 -> EntityType.VINDICATOR.create(level); case 1 -> EntityType.PILLAGER.create(level);
                default -> wave % 2 == 0 ? EntityType.STRAY.create(level) : EntityType.HUSK.create(level); };
        };
    }

    /** Ring around the arena center on loaded surface ground near the starting height. Never loads chunks. */
    private static boolean place(ServerLevel level, Run run, Mob mob, int index, int count) {
        var random = level.getRandom();
        for (int attempt = 0; attempt < 8; attempt++) {
            double angle = (Math.PI * 2 * index / Math.max(1, count)) + random.nextDouble() * 0.6 + attempt * 0.4;
            int radius = 24 + random.nextInt(9); // far enough to see them coming
            int x = run.center.getX() + (int) Math.round(Math.cos(angle) * radius), z = run.center.getZ() + (int) Math.round(Math.sin(angle) * radius);
            if (!level.hasChunkAt(new BlockPos(x, run.center.getY(), z))) continue;
            var ground = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, new BlockPos(x, 0, z));
            if (Math.abs(ground.getY() - run.center.getY()) > 8 || !level.getFluidState(ground.below()).isEmpty()) continue;
            mob.moveTo(ground.getX() + .5, ground.getY(), ground.getZ() + .5, random.nextFloat() * 360F, 0F);
            if (!level.noCollision(mob)) continue;
            return true;
        }
        return false;
    }

    private static void prepare(ServerLevel level, Run run, Mob mob, boolean boss) {
        EventHooks.finalizeMobSpawn(mob, level, level.getCurrentDifficultyAt(mob.blockPosition()), MobSpawnType.EVENT,
                mob instanceof Zombie ? new Zombie.ZombieGroupData(false, false) : null);
        if (mob instanceof Raider raider) { raider.setPatrolLeader(false); raider.setCanJoinRaid(false); }
        // Helmets stop undead burning in daylight; equipment never drops, so trials cannot be farmed for armor.
        var helmet = switch (run.tier.armorTier()) { case 0 -> Items.LEATHER_HELMET; case 1 -> Items.IRON_HELMET; default -> Items.DIAMOND_HELMET; };
        if (mob.getItemBySlot(EquipmentSlot.HEAD).isEmpty()) mob.setItemSlot(EquipmentSlot.HEAD, new ItemStack(helmet));
        if (run.tier.armorTier() >= 1 && mob.getItemBySlot(EquipmentSlot.CHEST).isEmpty())
            mob.setItemSlot(EquipmentSlot.CHEST, new ItemStack(run.tier.armorTier() >= 2 ? Items.IRON_CHESTPLATE : Items.CHAINMAIL_CHESTPLATE));
        for (var slot : EquipmentSlot.values()) mob.setDropChance(slot, 0F);
        if (!boss && run.tier.healthBonus() > 0) {
            var health = mob.getAttribute(Attributes.MAX_HEALTH);
            if (health != null) {
                health.addOrReplacePermanentModifier(new AttributeModifier(HEALTH_ID, run.tier.healthBonus(), AttributeModifier.Operation.ADD_MULTIPLIED_BASE));
                mob.setHealth(mob.getMaxHealth());
            }
        }
        mob.getPersistentData().putUUID(RUN_TAG, run.id);
        mob.setPersistenceRequired();
        if (level.addFreshEntity(mob)) {
            run.alive.add(mob.getUUID());
            var player = owner(level.getServer(), run);
            if (player != null) mob.setTarget(player);
        }
    }

    private static void updateBar(Run run, ServerLevel level) {
        Component name = run.boss
                ? Component.translatable("challenge.livingkingdoms.bar.boss", Component.translatable("challenge.livingkingdoms.boss." + run.tier.id()))
                : Component.translatable("challenge.livingkingdoms.bar.wave", Component.translatable("challenge.livingkingdoms.tier." + run.tier.id()),
                        run.wave + 1, run.tier.waves(), run.alive.size() + run.pending);
        run.bar.setName(name);
        if (run.boss) {
            float hp = 0, max = 0;
            for (var id : run.alive) if (level.getEntity(id) instanceof Mob mob) { hp += mob.getHealth(); max += mob.getMaxHealth(); }
            run.bar.setProgress(max <= 0 ? 0 : Math.clamp(hp / max, 0F, 1F));
            run.bar.setColor(BossEvent.BossBarColor.PURPLE);
        } else run.bar.setProgress(Math.clamp((float) (run.alive.size() + run.pending) / run.waveSize, 0F, 1F));
    }

    private static void end(net.minecraft.server.MinecraftServer server, Run run, boolean victory, String reason) {
        RUNS.remove(run.player);
        run.bar.removeAllPlayers();
        var player = owner(server, run);
        var level = server.getLevel(ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse(run.dimension)));
        if (!victory && level != null) for (var id : run.alive) { var entity = level.getEntity(id); if (entity != null) entity.discard(); }
        long now = server.overworld().getGameTime();
        if (!COOLDOWNS.containsKey(run.player) && COOLDOWNS.size() >= MAX_COOLDOWNS) COOLDOWNS.remove(COOLDOWNS.keySet().iterator().next());
        COOLDOWNS.put(run.player, now + (victory ? run.tier.cooldownTicks() : run.tier.cooldownTicks() / 4));
        if (player == null) return;
        if (!victory) {
            player.sendSystemMessage(Component.translatable("challenge.livingkingdoms.failed." + reason).withStyle(ChatFormatting.RED));
            return;
        }
        var items = new ArrayList<ItemStack>();
        items.add(new ItemStack(Items.EMERALD, run.tier.emeralds()));
        items.add(new ItemStack(KingdomItems.ROYAL_WHETSTONE.get(), run.tier.whetstones()));
        for (int i = 0; i < run.tier.tomes(); i++) items.add(KingdomTomes.roll(player.serverLevel(), player.getRandom()));
        var trophy = trophy(player.serverLevel(), run.tier.reward());
        if (!trophy.isEmpty()) items.add(trophy);
        var minimum = switch (run.tier) { case SKIRMISH -> dev.livingkingdoms.rpg.domain.LootRarity.COMMON;
            case SIEGE -> dev.livingkingdoms.rpg.domain.LootRarity.RARE; case WARLORD -> dev.livingkingdoms.rpg.domain.LootRarity.EPIC; };
        // Skirmish usually pays common (small chance of better), Siege rare, Warlord epic with a real legendary shot.
        var rolled = dev.livingkingdoms.rpg.domain.LootRarity.roll(new java.util.Random(player.getRandom().nextLong()),
                KingdomProgressService.luck(server, run.settlement), minimum);
        if (run.tier == dev.livingkingdoms.rpg.domain.ChallengeTier.WARLORD && player.getRandom().nextInt(4) == 0) rolled = dev.livingkingdoms.rpg.domain.LootRarity.LEGENDARY;
        items.add(LootChests.chest(rolled));
        RewardCeremony.celebrate(player, "challenge", Component.translatable("challenge.livingkingdoms.victory",
                Component.translatable("challenge.livingkingdoms.tier." + run.tier.id())), run.tier.emeralds(), 0,
                40 * run.tier.rank(), items, true);
        dev.livingkingdoms.chronicle.ChronicleAdvancements.grant(player, "first_challenge");
        ContractService.progress(player, dev.livingkingdoms.rpg.domain.Contract.Kind.TRIAL, 1);
        KingdomProgressService.award(player, run.settlement, (long) dev.livingkingdoms.rpg.domain.KingdomRank.TRIAL_XP_PER_RANK * run.tier.rank());
    }

    /** Unique trophy weapons. Refinement raises base damage: a +5 diamond sword deals 9 instead of 7. */
    static ItemStack trophy(ServerLevel level, ChallengeTier.Reward reward) {
        var lookup = level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT);
        return switch (reward) {
            case NONE -> ItemStack.EMPTY;
            case CHAMPION_AXE -> {
                var axe = new ItemStack(Items.DIAMOND_AXE);
                enchant(axe, lookup.getOrThrow(Enchantments.SHARPNESS), 2); enchant(axe, lookup.getOrThrow(Enchantments.UNBREAKING), 3);
                RefinementService.apply(axe, 3);
                axe.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME, Component.translatable("item.livingkingdoms.champion_axe").withStyle(ChatFormatting.AQUA));
                yield axe;
            }
            case CHAMPION_SWORD -> {
                var sword = new ItemStack(Items.DIAMOND_SWORD);
                enchant(sword, lookup.getOrThrow(Enchantments.LOOTING), 5); enchant(sword, lookup.getOrThrow(Enchantments.SHARPNESS), 3);
                enchant(sword, lookup.getOrThrow(Enchantments.UNBREAKING), 3);
                RefinementService.apply(sword, 5);
                sword.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME, Component.translatable("item.livingkingdoms.warlord_blade").withStyle(ChatFormatting.GOLD));
                yield sword;
            }
        };
    }
    private static void enchant(ItemStack stack, Holder<Enchantment> enchantment, int level) { stack.enchant(enchantment, level); }

    /** Restart safety: members whose run no longer exists are refused instead of lingering in a village. */
    public static void onJoin(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide() || event.loadedFromDisk() == false) return;
        var data = event.getEntity().getPersistentData();
        if (!data.hasUUID(RUN_TAG)) return;
        UUID id = data.getUUID(RUN_TAG);
        if (RUNS.values().stream().noneMatch(run -> run.id.equals(id))) event.setCanceled(true);
    }

    public static void onPlayerDeath(LivingDeathEvent event) {
        if (event.getEntity() instanceof ServerPlayer player && RUNS.containsKey(player.getUUID()))
            end(player.server, RUNS.get(player.getUUID()), false, "fallen");
    }

    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player && RUNS.containsKey(player.getUUID()))
            end(player.server, RUNS.get(player.getUUID()), false, "fled");
    }

    public static void abandon(ServerPlayer player) {
        var run = RUNS.get(player.getUUID());
        if (run != null) end(player.server, run, false, "abandoned");
    }

    /** Server stop: discard live members and forget cooldowns; nothing about a trial outlives the session. */
    public static void shutdown(net.minecraft.server.MinecraftServer server) {
        for (var run : List.copyOf(RUNS.values())) end(server, run, false, "abandoned");
        RUNS.clear(); COOLDOWNS.clear();
    }

    public static boolean running(UUID player) { return RUNS.containsKey(player); }
    /** The same ServerPlayer object survives dimension changes; a disconnected one is resolved again by UUID. */
    private static ServerPlayer owner(net.minecraft.server.MinecraftServer server, Run run) {
        var held = run.handle.get();
        return held != null && !held.hasDisconnected() ? held : server.getPlayerList().getPlayer(run.player);
    }
    private static long now(ServerLevel level) { return level.getServer().overworld().getGameTime(); }
}
