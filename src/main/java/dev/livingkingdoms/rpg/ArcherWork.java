package dev.livingkingdoms.rpg;

import dev.livingkingdoms.economy.BuildingDevelopment;
import dev.livingkingdoms.encounter.EncounterMember;
import dev.livingkingdoms.profession.GuardWork;
import dev.livingkingdoms.profession.persistence.ProfessionSavedData;
import dev.livingkingdoms.rpg.persistence.RpgSavedData;
import dev.livingkingdoms.settlement.domain.Settlement;
import dev.livingkingdoms.structure.BuildingKind;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.entity.projectile.Arrow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ProjectileWeaponItem;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.entity.ProjectileImpactEvent;
import java.util.*;

/**
 * Guards holding a bow or crossbow man the kingdom's high ground: watchtower tops first, then wall posts.
 * They walk (or, if no path is found within ten seconds, hop) up to a free post, hold it day and night and loose arrows at hostiles within range. Tower level
 * (or wall tier) raises arrow damage; level/tier 3 lights them on fire. Archer arrows never hurt citizens or
 * players. Post claims are transient and bounded; they are re-derived after a restart.
 */
public final class ArcherWork {
    public static final double RANGE = 24;
    public static final int SHOT_TICKS = 30, SEARCH_TICKS = 10, MAX_CLAIMS = 512, WALK_TICKS = 200;
    public static final String ARROW_TAG = "livingkingdoms:archer_arrow";
    public record Post(BlockPos stand, double damage, boolean fire) {}
    private record Cache(long at, List<Post> posts) {}
    private static final Map<UUID, BlockPos> CLAIMS = new HashMap<>();
    private static final Map<UUID, Cache> POSTS = new HashMap<>();
    private static final Map<Villager, int[]> TIMERS = new WeakHashMap<>();
    private ArcherWork() {}

    public static boolean archer(LivingEntity entity) { return entity.getMainHandItem().getItem() instanceof ProjectileWeaponItem; }

    /** Every post a kingdom offers right now: watchtower tops then wall posts (cached for 10 seconds). */
    public static List<Post> posts(ServerLevel level, Settlement settlement) {
        var cached = POSTS.get(settlement.id());
        if (cached != null && level.getGameTime() - cached.at < 200) return cached.posts;
        var posts = new ArrayList<Post>();
        for (var building : ProfessionSavedData.get(level.getServer()).buildings(settlement.id())) {
            if (!building.active() || building.kind() != BuildingKind.WATCHTOWER) continue;
            int towerLevel = BuildingDevelopment.level(level.getServer(), settlement.id(), building.id());
            var b = building.bounds();
            int cx = (b.minX() + b.maxX()) / 2, cz = (b.minZ() + b.maxZ()) / 2;
            int added = 0;
            for (int[] o : new int[][]{{0, 0}, {1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {-1, -1}}) {
                var column = new BlockPos(cx + o[0], building.origin().getY(), cz + o[1]);
                if (!level.hasChunkAt(column)) continue;
                var stand = new BlockPos(column.getX(), level.getHeight(Heightmap.Types.MOTION_BLOCKING, column.getX(), column.getZ()), column.getZ());
                if (stand.getY() < building.origin().getY() + 5 || !standable(level, stand)) continue;
                posts.add(new Post(stand, 2.5 + towerLevel * 1.5, towerLevel >= 3));
                if (++added >= 2) break;
            }
        }
        int tier = RpgSavedData.get(level.getServer()).wall(settlement.id()).map(w -> w.tier()).orElse(0);
        for (var stand : WallService.posts(level, settlement)) posts.add(new Post(stand, 2 + tier, tier >= 3));
        var list = List.copyOf(posts);
        POSTS.put(settlement.id(), new Cache(level.getGameTime(), list));
        return list;
    }

    private static boolean standable(ServerLevel level, BlockPos stand) {
        return level.getBlockState(stand).isAir() && level.getBlockState(stand.above()).isAir()
                && level.getBlockState(stand.below()).isFaceSturdy(level, stand.below(), net.minecraft.core.Direction.UP);
    }

    /** The post this archer holds, claiming the nearest free one; null when the kingdom has none. */
    public static Post post(Villager v, ServerLevel level, Settlement settlement) {
        var posts = posts(level, settlement);
        var claimed = CLAIMS.get(v.getUUID());
        if (claimed != null) for (var p : posts) if (p.stand.equals(claimed)) return p;
        CLAIMS.remove(v.getUUID());
        if (CLAIMS.size() >= MAX_CLAIMS) CLAIMS.clear();
        var taken = new HashSet<>(CLAIMS.values());
        Post best = null;
        for (var p : posts) if (!taken.contains(p.stand) && (best == null || p.stand.distSqr(v.blockPosition()) < best.stand.distSqr(v.blockPosition()))) best = p;
        if (best != null) CLAIMS.put(v.getUUID(), best.stand);
        return best;
    }

    public static void release(Villager v) { CLAIMS.remove(v.getUUID()); TIMERS.remove(v); }

    /** Runs the archer's duty for this tick. Returns false when the guard should fight on foot instead. */
    public static boolean tick(Villager v, ServerLevel level, Settlement settlement) {
        if (!archer(v)) { if (CLAIMS.containsKey(v.getUUID())) release(v); return false; }
        if (v.isPassenger() || v.isLeashed()) return false;
        var post = post(v, level, settlement);
        if (post == null) return false;
        var stand = post.stand;
        var timers = TIMERS.computeIfAbsent(v, k -> new int[]{0, 0, -1});
        if (v.distanceToSqr(stand.getX() + .5, stand.getY(), stand.getZ() + .5) > 2.25) {
            if (!level.hasChunkAt(stand) || !standable(level, stand)) { CLAIMS.remove(v.getUUID()); POSTS.remove(settlement.id()); return false; }
            if (v.isSleeping()) v.stopSleeping();
            // Walk up (stairs, ladders) when a path exists; hop up only after ~10 s without arriving or from far away.
            if (timers[2] < 0) timers[2] = v.tickCount;
            boolean stuck = v.tickCount - timers[2] > WALK_TICKS || v.distanceToSqr(stand.getX() + .5, stand.getY(), stand.getZ() + .5) > 48 * 48;
            if (!stuck) {
                if (v.tickCount % 20 == 0) v.getNavigation().moveTo(stand.getX() + .5, stand.getY(), stand.getZ() + .5, .7);
                return true;
            }
            level.sendParticles(ParticleTypes.CLOUD, v.getX(), v.getY() + 1, v.getZ(), 6, .3, .5, .3, 0);
            v.teleportTo(stand.getX() + .5, stand.getY(), stand.getZ() + .5);
            level.playSound(null, stand, SoundEvents.ARMOR_EQUIP_LEATHER.value(), SoundSource.NEUTRAL, .7F, 1F);
        }
        timers[2] = -1;
        v.getNavigation().stop();
        LivingEntity target = v.getTarget();
        if (target != null && (!target.isAlive() || target.level() != level || v.distanceToSqr(target) > RANGE * RANGE || !shootable(target))) { v.setTarget(null); target = null; }
        if (target == null && v.tickCount >= timers[0]) {
            timers[0] = v.tickCount + SEARCH_TICKS;
            target = level.getEntitiesOfClass(Mob.class, v.getBoundingBox().inflate(RANGE, 12, RANGE), m -> shootable(m) && v.hasLineOfSight(m))
                    .stream().min(Comparator.comparingDouble(v::distanceToSqr)).orElse(null);
            v.setTarget(target);
        }
        if (target != null) {
            v.getLookControl().setLookAt(target, 30, 30);
            if (v.tickCount >= timers[1] && v.hasLineOfSight(target)) {
                timers[1] = v.tickCount + SHOT_TICKS;
                shoot(level, v, target, post.damage, post.fire);
            }
        }
        return true;
    }

    /** Hostile invaders and ordinary monsters; never citizens, traders, players or neutral encounter members. */
    public static boolean shootable(LivingEntity e) {
        if (!e.isAlive()) return false;
        if (GuardWork.hostile(e)) return true;
        return e instanceof Enemy && e instanceof Mob && EncounterMember.read(e).isEmpty() && dev.livingkingdoms.npc.NpcIdentity.read(e).isEmpty();
    }

    public static Arrow shoot(ServerLevel level, Villager v, LivingEntity target, double damage, boolean fire) {
        var arrow = new Arrow(level, v, new ItemStack(Items.ARROW), v.getMainHandItem().copy());
        arrow.setPos(v.getX(), v.getEyeY() - .1, v.getZ());
        double dx = target.getX() - arrow.getX(), dy = target.getY(.33) - arrow.getY(), dz = target.getZ() - arrow.getZ();
        double flat = Math.sqrt(dx * dx + dz * dz);
        arrow.shoot(dx, dy + flat * .2, dz, 1.7F, 2F);
        arrow.setBaseDamage(damage);
        arrow.pickup = AbstractArrow.Pickup.DISALLOWED;
        arrow.addTag(ARROW_TAG);
        if (fire) arrow.igniteForSeconds(5);
        v.swing(InteractionHand.MAIN_HAND);
        level.addFreshEntity(arrow);
        level.playSound(null, v.blockPosition(), SoundEvents.ARROW_SHOOT, SoundSource.NEUTRAL, 1F, 1F / (level.random.nextFloat() * .4F + .8F));
        return arrow;
    }

    /** Archer arrows pass harmlessly through anything that is not a valid target. */
    public static void onImpact(ProjectileImpactEvent event) {
        if (!(event.getProjectile() instanceof AbstractArrow arrow) || !arrow.getTags().contains(ARROW_TAG)) return;
        if (event.getRayTraceResult() instanceof EntityHitResult hit && !(hit.getEntity() instanceof LivingEntity living && shootable(living))) event.setCanceled(true);
    }

    public static Optional<BlockPos> claim(UUID villager) { return Optional.ofNullable(CLAIMS.get(villager)); }
    public static void invalidate(UUID settlement) { POSTS.remove(settlement); }
    public static void clear() { CLAIMS.clear(); POSTS.clear(); TIMERS.clear(); }
}
