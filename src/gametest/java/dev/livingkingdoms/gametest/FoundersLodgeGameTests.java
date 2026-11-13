package dev.livingkingdoms.gametest;

import dev.livingkingdoms.construction.ConstructionService;
import dev.livingkingdoms.economy.FoundersLodge;
import dev.livingkingdoms.settlement.persistence.SettlementSavedData;
import dev.livingkingdoms.structure.BuildingKind;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** 0.38: converting the camp into the Founders' Lodge moves residents and players to verified standable ground. */
@GameTestHolder(KingdomGameTests.MOD_ID)
@PrefixGameTestTemplate(false)
public final class FoundersLodgeGameTests {
    @GameTest(template = "empty", timeoutTicks = 400)
    public static void conversionMovesNpcsAndPlayersToSafeGround(GameTestHelper h) {
        var f = ConstructionGameTests.found(h, 1130000);
        var level = h.getLevel(); var server = level.getServer();
        var layout = SettlementSavedData.get(server).layout(f.settlement().id()).orElseThrow();
        var origin = layout.buildings().stream().filter(b -> b.kind() == BuildingKind.FOUNDING_CAMP).findFirst().orElseThrow().origin();
        Villager resident = null;
        boolean stored = false;
        for (int i = 0; i < 2 && !FoundersLodge.converted(server, f.settlement().id()); i++) {
            // The old fixed doorstep point is walled in, so only a real search can find safe ground.
            level.setBlock(origin.offset(4, 1, 10), Blocks.STONE.defaultBlockState(), 2);
            level.setBlock(origin.offset(4, 2, 10), Blocks.STONE.defaultBlockState(), 2);
            if (resident == null) {
                resident = EntityType.VILLAGER.create(level); resident.setNoAi(true);
                resident.setPos(origin.getX() + 2.5, origin.getY() + 1, origin.getZ() + 2.5); level.addFreshEntity(resident);
                // Something the player stored in the camp's container must survive the template swap.
                for (var pos : BlockPos.betweenClosed(origin, origin.offset(8, 8, 8))) if (level.getBlockEntity(pos) instanceof net.minecraft.world.Container c) {
                    c.setItem(0, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.DIAMOND, 5)); stored = true; break;
                }
            }
            var entry = ConstructionService.current(server, f.settlement().id()).orElseThrow();
            h.assertTrue(ConstructionService.debugAdvance(server, entry.project().id(), 4, f.player()), "Founding project " + i + " completes");
        }
        h.assertTrue(FoundersLodge.converted(server, f.settlement().id()), "The camp became the lodge");
        assertOutsideOnSafeGround(h, resident, origin, "Resident");
        h.assertTrue(stored, "The camp has a container to store items in");
        int diamonds = level.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class, new net.minecraft.world.phys.AABB(origin).inflate(24),
                i -> i.getItem().is(net.minecraft.world.item.Items.DIAMOND)).stream().mapToInt(i -> i.getItem().getCount()).sum();
        h.assertTrue(diamonds == 5, "Items stored in the camp are dropped outside, not deleted; found " + diamonds);
        // A FakePlayer is not in the level's entity lookup (lesson 2), so the player path is exercised through the shared helper.
        var player = f.player(); player.setPos(origin.getX() + 4.5, origin.getY() + 1, origin.getZ() + 3.5);
        float yaw = 37F; player.setYRot(yaw);
        FoundersLodge.moveToSafeGround(level, layout, origin, player);
        assertOutsideOnSafeGround(h, player, origin, "Player");
        h.assertTrue(player.getYRot() == yaw, "Player keeps its facing");
        h.succeed();
    }

    private static void assertOutsideOnSafeGround(GameTestHelper h, Entity e, BlockPos origin, String who) {
        var level = h.getLevel(); var feet = e.blockPosition();
        h.assertTrue(feet.getX() < origin.getX() || feet.getX() > origin.getX() + 8 || feet.getZ() < origin.getZ() || feet.getZ() > origin.getZ() + 8, who + " stands outside the lodge footprint");
        h.assertTrue(level.getBlockState(feet).getCollisionShape(level, feet).isEmpty() && level.getBlockState(feet.above()).getCollisionShape(level, feet.above()).isEmpty(), who + " is not inside a block");
        h.assertTrue(level.getBlockState(feet.below()).isFaceSturdy(level, feet.below(), Direction.UP), who + " has solid floor below");
    }
}
