package dev.livingkingdoms.gametest;

import dev.livingkingdoms.block.KingdomBlocks;
import dev.livingkingdoms.citizen.domain.CitizenRole;
import dev.livingkingdoms.citizen.persistence.CitizenSavedData;
import dev.livingkingdoms.economy.KingdomEconomy;
import dev.livingkingdoms.economy.StockResource;
import dev.livingkingdoms.profession.CitizenRoutine;
import dev.livingkingdoms.rpg.AlarmService;
import dev.livingkingdoms.rpg.ArcherWork;
import dev.livingkingdoms.rpg.EventService;
import dev.livingkingdoms.rpg.TechniqueService;
import dev.livingkingdoms.rpg.TownDecorService;
import dev.livingkingdoms.rpg.WallService;
import dev.livingkingdoms.rpg.domain.Fortification;
import dev.livingkingdoms.rpg.domain.Technique;
import dev.livingkingdoms.rpg.domain.TownDecor;
import dev.livingkingdoms.rpg.persistence.RpgSavedData;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** 0.30: town beautification, events, techniques, walls, archers, the alarm bell and spike traps. */
@GameTestHolder(KingdomGameTests.MOD_ID)
@PrefixGameTestTemplate(false)
public final class KingdomDefenseGameTests {
    private static void stock(ConstructionGameTests.Founded f, StockResource resource, int stacks) {
        var player = f.player();
        for (int i = 0; i < stacks; i++) {
            player.getInventory().setItem(0, new ItemStack(resource.item(), 64));
            KingdomEconomy.deposit(player, f.settlement().id(), resource, 64);
        }
        player.getInventory().clearContent();
    }

    private static dev.livingkingdoms.settlement.domain.Settlement establish(GameTestHelper h, ConstructionGameTests.Founded f) {
        var server = h.getLevel().getServer();
        for (int i = 0; i < 2; i++) {
            var entry = dev.livingkingdoms.construction.ConstructionService.current(server, f.settlement().id()).orElseThrow();
            h.assertTrue(dev.livingkingdoms.construction.ConstructionService.debugAdvance(server, entry.project().id(), 4, f.player()), "Founding project " + i + " completes");
        }
        var s = dev.livingkingdoms.settlement.persistence.SettlementSavedData.get(server).get(f.settlement().id()).orElseThrow();
        h.assertTrue(s.lifecycle() == dev.livingkingdoms.settlement.domain.SettlementLifecycle.ESTABLISHED, "Established");
        return s;
    }

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void rankDecorationsApplyEveryUnlockedTierOnce(GameTestHelper h) {
        var f = ConstructionGameTests.found(h, 211008);
        var level = h.getLevel(); var id = f.settlement().id();
        TownDecorService.beautify(level, id, 10);
        int all = 0; for (var tier : TownDecor.values()) all |= tier.bit();
        h.assertTrue(RpgSavedData.get(level.getServer()).decor(id) == all, "Every tier up to rank 10 is marked applied");
        h.assertTrue(TownDecorService.beautify(level, id, 20).isEmpty(), "Applied tiers never re-flood the town");
        h.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void wallsRiseWithDoorsAndPostsAndAreRepairedFromStorage(GameTestHelper h) {
        var f = ConstructionGameTests.found(h, 212032);
        var level = h.getLevel(); var server = level.getServer();
        h.assertTrue(WallService.blocked(server, f.settlement()).isPresent(), "A founding camp cannot raise walls yet");
        var s = establish(h, f);
        h.assertTrue(!WallService.fortify(f.player(), s.id()), "An empty storage cannot pay for walls");
        stock(f, StockResource.WOOD, 4);
        int wood = KingdomEconomy.count(server, s.id(), StockResource.WOOD);
        h.assertTrue(WallService.fortify(f.player(), s.id()), "Storage pays for the palisade: " + WallService.blocked(server, s) + " wood " + wood + " cost " + WallService.nextCost(server, s));
        h.assertTrue(KingdomEconomy.count(server, s.id(), StockResource.WOOD) < wood, "Wood was spent");
        h.assertTrue(!WallService.fortify(f.player(), s.id()), "No second order while the wall is rising");
        WallService.buildAll(level, s.id());
        var wall = RpgSavedData.get(server).wall(s.id()).orElseThrow();
        h.assertTrue(wall.complete() && wall.tier() == 1, "Palisade finished: " + wall);
        var t = s.territory(); var ring = WallService.ring(wall.radius());
        int logs = 0; BlockPos piece = null;
        for (int[] o : ring) for (int y = -2; y <= 6; y++) {
            var pos = new BlockPos(t.x() + o[0], t.y() + y, t.z() + o[1]);
            if (level.getBlockState(pos).is(Blocks.STRIPPED_SPRUCE_LOG)) { logs++; if (piece == null) piece = pos; }
        }
        h.assertTrue(logs >= ring.size(), "Most ring columns carry timber (" + logs + " logs for " + ring.size() + " columns)");
        h.assertTrue(!WallService.posts(level, s).isEmpty(), "Archer posts exist on the wall");
        h.assertTrue(!ArcherWork.posts(level, s).isEmpty(), "Archers can find wall posts");
        WallService.breach(level, s.id(), piece);
        h.assertTrue(level.getBlockState(piece).isAir() && WallService.breaches(s.id()) == 1, "An invader breach is recorded");
        h.assertTrue(WallService.repair(level, s.id(), 8) == 1 && level.getBlockState(piece).is(Blocks.STRIPPED_SPRUCE_LOG), "Repaired from storage");
        h.assertTrue(WallService.breaches(s.id()) == 0, "No breaches left");
        // Upgrading replaces our timber with cobblestone without touching anything else.
        stock(f, StockResource.STONE, 4); stock(f, StockResource.IRON, 1);
        h.assertTrue(WallService.fortify(f.player(), s.id()), "Stone tier ordered: " + WallService.blocked(server, s) + " cost " + WallService.nextCost(server, s));
        WallService.buildAll(level, s.id());
        h.assertTrue(level.getBlockState(piece).is(Blocks.COBBLESTONE) || level.getBlockState(piece).getBlock() instanceof DoorBlock, "Timber upgraded to stone");
        RpgSavedData.get(server).putWall(s.id(), Fortification.none(wall.radius()));
        WallService.clear(); ArcherWork.clear();
        h.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void bowGuardsManPostsAndShootOnlyMonsters(GameTestHelper h) {
        var f = ConstructionGameTests.found(h, 213056);
        var level = h.getLevel(); var server = level.getServer(); var s = f.settlement();
        RpgSavedData.get(server).putWall(s.id(), Fortification.none(WallService.radiusFor(s)).upgrade());
        WallService.buildAll(level, s.id());
        var archer = EntityType.VILLAGER.create(level);
        archer.setPos(s.territory().x() + .5, s.territory().y(), s.territory().z() + .5);
        archer.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.BOW));
        level.addFreshEntity(archer);
        h.assertTrue(ArcherWork.archer(archer), "Bow holders are archers");
        h.assertTrue(ArcherWork.tick(archer, level, s), "The archer heads for a post");
        // No path up within the walking window: the archer hops onto the post.
        archer.tickCount += ArcherWork.WALK_TICKS + 20;
        h.assertTrue(ArcherWork.tick(archer, level, s), "The archer takes the post");
        var post = ArcherWork.claim(archer.getUUID()).orElseThrow();
        h.assertTrue(archer.blockPosition().equals(post), "Archer stands on the post");
        var zombie = EntityType.ZOMBIE.create(level); zombie.setPos(post.getX() + 6.5, post.getY(), post.getZ() + .5); zombie.setNoAi(true);
        level.addFreshEntity(zombie);
        h.assertTrue(ArcherWork.shootable(zombie) && !ArcherWork.shootable(archer), "Monsters are targets, villagers never");
        var arrow = ArcherWork.shoot(level, archer, zombie, 3.5, true);
        h.assertTrue(arrow.getTags().contains(ArcherWork.ARROW_TAG) && arrow.pickup == AbstractArrow.Pickup.DISALLOWED
                && arrow.getBaseDamage() == 3.5 && arrow.isOnFire(), "Archer arrows: tagged, no pickup, tower damage, fire at max level");
        var sword = EntityType.VILLAGER.create(level); sword.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_SWORD));
        h.assertTrue(!ArcherWork.archer(sword) && !ArcherWork.tick(sword, level, s), "Sword guards keep fighting on foot");
        ArcherWork.release(archer);
        RpgSavedData.get(server).putWall(s.id(), Fortification.none(WallService.radiusFor(s)));
        WallService.clear(); ArcherWork.clear(); zombie.discard(); arrow.discard();
        h.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void alarmBellSheltersCiviliansAndRevealsMonsters(GameTestHelper h) {
        var f = ConstructionGameTests.found(h, 214080);
        var level = h.getLevel(); var server = level.getServer(); var s = f.settlement();
        var citizen = CitizenSavedData.get(server).citizens(s.id()).stream().filter(c -> c.role() != CitizenRole.MAYOR).findFirst().orElseThrow();
        var villager = (Villager) level.getEntity(citizen.entityId());
        var zombie = EntityType.ZOMBIE.create(level); zombie.setPos(s.territory().x() + 6.5, s.territory().y(), s.territory().z() + .5); zombie.setNoAi(true);
        zombie.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.IRON_HELMET)); level.addFreshEntity(zombie);
        h.assertTrue(!AlarmService.active(server, s.id()), "Quiet before the bell");
        h.assertTrue(AlarmService.sound(level, s), "The bell sounds the alarm");
        h.assertTrue(AlarmService.active(server, s.id()) && CitizenRoutine.threatened(villager, citizen), "Civilians treat the alarm as a threat");
        h.assertTrue(zombie.hasEffect(MobEffects.GLOWING), "Monsters in the territory glow");
        h.assertTrue(!AlarmService.sound(level, s), "Ringing again only extends it");
        AlarmService.clear(); zombie.discard();
        h.assertTrue(!AlarmService.active(server, s.id()), "Transient: cleared alarms are gone");
        h.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void techniquesGrantTheirPerk(GameTestHelper h) {
        var f = ConstructionGameTests.found(h, 215104);
        var player = f.player(); var server = h.getLevel().getServer();
        double health = player.getAttributeValue(Attributes.MAX_HEALTH);
        RpgSavedData.get(server).learn(player.getUUID(), Technique.HEARTY_MEALS);
        RpgSavedData.get(server).learn(player.getUUID(), Technique.DRILL);
        TechniqueService.apply(player); TechniqueService.apply(player);
        h.assertTrue(player.getAttributeValue(Attributes.MAX_HEALTH) == health + 4, "Hearty Meals adds two hearts once");
        h.assertTrue(TechniqueService.knows(player, Technique.DRILL) && !TechniqueService.knows(player, Technique.TIMBER), "Known techniques");
        h.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void eventsBringACaravanAndATreasureWithAHudCountdown(GameTestHelper h) {
        var f = ConstructionGameTests.found(h, 216128);
        var level = h.getLevel(); var s = f.settlement();
        h.assertTrue(EventService.trigger(level, s, EventService.Kind.CARAVAN), "Caravan arrives");
        var t = s.territory();
        var traders = level.getEntitiesOfClass(net.minecraft.world.entity.npc.WanderingTrader.class,
                new net.minecraft.world.phys.AABB(t.x() - 16, t.y() - 8, t.z() - 16, t.x() + 16, t.y() + 16, t.z() + 16), w -> w.getPersistentData().getBoolean(EventService.CARAVAN_TAG));
        h.assertTrue(traders.size() == 1 && !traders.getFirst().getOffers().isEmpty(), "Caravan master with custom offers");
        h.assertTrue(EventService.active(level.getServer(), s.id()).orElseThrow() == EventService.Kind.CARAVAN, "Caravan is the active event");
        h.assertTrue(EventService.trigger(level, s, EventService.Kind.TREASURE), "A new event replaces the caravan");
        h.assertTrue(traders.getFirst().isRemoved(), "The caravan leaves when its event ends");
        var hud = EventService.hud(f.player(), s.id());
        h.assertTrue(hud.getString("kind").equals("treasure") && hud.getString("phase").equals("active") && hud.getLong("seconds") > 0 && hud.contains("direction"),
                "HUD shows the hunt with a countdown and a direction: " + hud);
        h.assertTrue(EventService.trigger(level, s, EventService.Kind.FESTIVAL) && EventService.festival(level.getServer(), s.id()), "Festival multiplier active");
        EventService.clear();
        h.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 120)
    public static void spikeTrapsHurtMonstersButNotVillagers(GameTestHelper h) {
        h.setBlock(0, 0, 0, Blocks.STONE); h.setBlock(2, 0, 2, Blocks.STONE);
        h.setBlock(0, 1, 0, KingdomBlocks.SPIKE_TRAP.get()); h.setBlock(2, 1, 2, KingdomBlocks.SPIKE_TRAP.get());
        // NoAI mobs never move, so drive the block's entity hook directly each tick.
        var zombie = h.spawn(EntityType.ZOMBIE, new BlockPos(0, 1, 0)); zombie.setNoAi(true);
        zombie.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.IRON_HELMET));
        var villager = h.spawn(EntityType.VILLAGER, new BlockPos(2, 1, 2)); villager.setNoAi(true);
        var trap = h.absolutePos(new BlockPos(0, 1, 0)); var villagerTrap = h.absolutePos(new BlockPos(2, 1, 2));
        h.onEachTick(() -> {
            h.getLevel().getBlockState(trap).entityInside(h.getLevel(), trap, zombie);
            h.getLevel().getBlockState(villagerTrap).entityInside(h.getLevel(), villagerTrap, villager);
        });
        h.succeedWhen(() -> {
            h.assertTrue(zombie.getLastDamageSource() != null && zombie.getLastDamageSource().is(DamageTypes.CACTUS), "Zombie pricked by spikes");
            h.assertTrue(zombie.hasEffect(MobEffects.MOVEMENT_SLOWDOWN), "Zombie slowed");
            h.assertTrue(villager.getHealth() == villager.getMaxHealth() && !villager.hasEffect(MobEffects.MOVEMENT_SLOWDOWN),
                    "Villager unharmed: " + villager.getHealth() + " " + villager.getLastDamageSource());
        });
    }
}
