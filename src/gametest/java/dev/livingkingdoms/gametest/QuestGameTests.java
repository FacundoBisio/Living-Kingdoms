package dev.livingkingdoms.gametest;

import com.mojang.authlib.GameProfile;
import dev.livingkingdoms.block.KingdomBlocks;
import dev.livingkingdoms.quest.domain.QuestState;
import dev.livingkingdoms.quest.domain.QuestTerms;
import dev.livingkingdoms.quest.persistence.QuestSavedData;
import dev.livingkingdoms.settlement.domain.Settlement;
import dev.livingkingdoms.settlement.domain.Territory;
import dev.livingkingdoms.settlement.persistence.SettlementSavedData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.storage.DimensionDataStorage;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.IOUtilities;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Server interactions, inventory changes and real world SavedData, with isolated test-only fixtures. */
@GameTestHolder(KingdomGameTests.MOD_ID)
@PrefixGameTestTemplate(false)
public final class QuestGameTests {
    private static final int FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;

    @GameTest(template = "empty", timeoutTicks = 400)
    public static void deliveryIsExactAndRewardsOnlyOnce(GameTestHelper helper) {
        Fixture fixture = fixture(helper, 6144);
        ServerLevel level = helper.getLevel();
        QuestSavedData data = QuestSavedData.get(level.getServer());
        RecordingPlayer player = player(level, fixture.board());
        UUID playerId = player.getUUID();

        interact(helper, player, fixture.board(), false, false);
        helper.assertTrue(data.progress(playerId, fixture.settlement().id()).state() == QuestState.AVAILABLE,
                "Inspecting the quest must not accept it");
        helper.assertTrue(player.messages.stream().anyMatch(message -> hasKey(message, "quest.livingkingdoms.header")),
                "Quest inspection must show a localized header");

        player.getInventory().items.set(0, new ItemStack(Items.IRON_INGOT, 7));
        player.setShiftKeyDown(true);
        BlockHitResult hit = hit(fixture.board());
        InteractionResult acceptance = player.gameMode.useItemOn(player, level, player.getMainHandItem(), InteractionHand.MAIN_HAND, hit);
        helper.assertTrue(acceptance.consumesAction(), "Crouching with held iron must reach the board through the real use-item pipeline");
        dev.livingkingdoms.quest.QuestService.interact(player, fixture.board(), true);
        var active = data.progress(playerId, fixture.settlement().id());
        helper.assertTrue(active.state() == QuestState.ACTIVE && active.terms() != null,
                "Crouching accepts one quest with frozen delivery terms");
        helper.assertTrue(count(player, Items.IRON_INGOT) == 7, "Accepting must not consume resources");
        interact(helper, player, fixture.board(), true, true);
        helper.assertTrue(data.progress(playerId, fixture.settlement().id()).equals(active)
                && count(player, Items.IRON_INGOT) == 7 && count(player, Items.EMERALD) == 0,
                "Insufficient delivery must preserve the single active quest and all resources");

        player.getInventory().items.set(4, new ItemStack(Items.IRON_INGOT, 6));
        player.getInventory().offhand.set(0, new ItemStack(Items.IRON_INGOT, 8));
        player.getInventory().armor.set(0, new ItemStack(Items.IRON_INGOT, 64));
        player.getInventory().items.set(9, new ItemStack(Items.DIAMOND, 3));
        InteractionResult delivery = player.gameMode.useItemOn(player, level, player.getMainHandItem(), InteractionHand.MAIN_HAND, hit);
        helper.assertTrue(delivery.consumesAction(), "Crouching held-item delivery must be consumed by the board");
        dev.livingkingdoms.quest.QuestService.interact(player, fixture.board(), true);
        var completed = data.progress(playerId, fixture.settlement().id());
        helper.assertTrue(completed.state() == QuestState.COMPLETED && completed.reputation() == 10,
                "Successful delivery completes the quest and adds settlement reputation once");
        helper.assertTrue(count(player, Items.IRON_INGOT) == 5 && count(player, Items.EMERALD) == 8,
                "Delivery must remove exactly 16 iron across main inventory and offhand, then grant exactly eight emeralds");
        helper.assertTrue(player.getInventory().armor.get(0).getCount() == 64
                && player.getInventory().items.get(9).is(Items.DIAMOND) && player.getInventory().items.get(9).getCount() == 3,
                "Delivery must ignore armor and preserve unrelated inventory contents");

        for (int i = 0; i < 4; i++) interact(helper, player, fixture.board(), true, i % 2 == 0);
        interact(helper, player, fixture.board(), false, false);
        helper.assertTrue(data.progress(playerId, fixture.settlement().id()).equals(completed)
                && count(player, Items.IRON_INGOT) == 5 && count(player, Items.EMERALD) == 8,
                "Repeated inspection and delivery must not grant any second reward or reputation");

        player.messages.clear();
        var state = level.getBlockState(fixture.board());
        helper.assertTrue(state.useItemOn(player.getOffhandItem(), level, player, InteractionHand.OFF_HAND, hit)
                == ItemInteractionResult.CONSUME && player.messages.isEmpty(),
                "The offhand callback must not duplicate dialogue or mutate quest state");
        helper.assertTrue(data.progress(playerId, fixture.settlement().id()).equals(completed), "Offhand use must not mutate progress");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 400)
    public static void progressionIsIndependentPerPlayerAndSettlement(GameTestHelper helper) {
        Fixture first = fixture(helper, 6656);
        Fixture second = fixtureAt(helper.getLevel(), first.board().offset(128, 0, 0));
        ServerLevel level = helper.getLevel();
        QuestSavedData data = QuestSavedData.get(level.getServer());
        RecordingPlayer playerA = player(level, first.board());
        RecordingPlayer playerB = player(level, first.board());
        interact(helper, playerA, first.board(), true, false);
        interact(helper, playerB, first.board(), true, true);
        helper.assertTrue(data.progress(playerA.getUUID(), first.settlement().id()).state() == QuestState.ACTIVE
                && data.progress(playerB.getUUID(), first.settlement().id()).state() == QuestState.ACTIVE,
                "Each player must be able to accept an independent instance of the same settlement quest");
        playerA.getInventory().items.set(0, new ItemStack(Items.IRON_INGOT, 16));
        interact(helper, playerA, first.board(), true, true);
        helper.assertTrue(data.progress(playerA.getUUID(), first.settlement().id()).state() == QuestState.COMPLETED
                && data.progress(playerB.getUUID(), first.settlement().id()).state() == QuestState.ACTIVE
                && data.progress(playerB.getUUID(), first.settlement().id()).reputation() == 0,
                "One player's completion must not change another player's quest or reputation");

        moveToBoard(playerA, second.board());
        interact(helper, playerA, second.board(), false, false);
        helper.assertTrue(data.progress(playerA.getUUID(), second.settlement().id()).state() == QuestState.AVAILABLE
                && data.progress(playerA.getUUID(), second.settlement().id()).reputation() == 0,
                "A completed quest and reputation in one settlement must not transfer to another settlement");
        interact(helper, playerA, second.board(), true, false);
        helper.assertTrue(data.progress(playerA.getUUID(), second.settlement().id()).state() == QuestState.ACTIVE,
                "The same player must have an independently active quest in another settlement");
        playerA.getInventory().items.set(1, new ItemStack(Items.IRON_INGOT, 16));
        interact(helper, playerA, second.board(), true, true);
        helper.assertTrue(data.progress(playerA.getUUID(), first.settlement().id()).reputation() == 10
                && data.progress(playerA.getUUID(), second.settlement().id()).reputation() == 10
                && count(playerA, Items.EMERALD) == 16, "Both settlements must reward their own quest exactly once");

        playerB.getInventory().items.set(0, new ItemStack(Items.IRON_INGOT, 16));
        interact(helper, playerB, first.board(), true, true);
        helper.assertTrue(data.progress(playerB.getUUID(), first.settlement().id()).state() == QuestState.COMPLETED
                && data.progress(playerB.getUUID(), first.settlement().id()).reputation() == 10
                && count(playerB, Items.EMERALD) == 8, "The second player must still receive their own reward");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 400)
    public static void reconnectAndDiskReloadKeepCompletedRewardsClosed(GameTestHelper helper) {
        Fixture fixture = fixture(helper, 7168);
        ServerLevel level = helper.getLevel();
        QuestSavedData data = QuestSavedData.get(level.getServer());
        RecordingPlayer original = player(level, fixture.board());
        interact(helper, original, fixture.board(), true, false);
        original.getInventory().items.set(0, new ItemStack(Items.IRON_INGOT, 16));
        interact(helper, original, fixture.board(), true, true);
        var expected = data.progress(original.getUUID(), fixture.settlement().id());
        helper.assertTrue(expected.state() == QuestState.COMPLETED && expected.reputation() == 10, "Fixture must finish its quest");

        RecordingPlayer reconnected = new RecordingPlayer(level, original.getUUID());
        moveToBoard(reconnected, fixture.board());
        reconnected.getInventory().items.set(0, new ItemStack(Items.IRON_INGOT, 16));
        interact(helper, reconnected, fixture.board(), true, true);
        helper.assertTrue(data.progress(reconnected.getUUID(), fixture.settlement().id()).equals(expected)
                && count(reconnected, Items.IRON_INGOT) == 16 && count(reconnected, Items.EMERALD) == 0,
                "A fresh player object with the same UUID must not receive a completed reward again");

        level.getDataStorage().save();
        IOUtilities.waitUntilIOWorkerComplete();
        var directory = level.getServer().getWorldPath(LevelResource.ROOT).resolve("data");
        var storage = new DimensionDataStorage(directory.toFile(), null, level.getServer().registryAccess());
        var reopened = storage.get(new SavedData.Factory<>(QuestSavedData::new, QuestSavedData::load), QuestSavedData.DATA_NAME);
        IOUtilities.waitUntilIOWorkerComplete();
        helper.assertTrue(reopened != null && reopened != data && !reopened.isDirty()
                && reopened.progress(original.getUUID(), fixture.settlement().id()).equals(expected),
                "Fresh world storage must reload the same player UUID, settlement UUID, terms, completion and reputation");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 400)
    public static void inventoryCapacityAndArmorCannotBeExploited(GameTestHelper helper) {
        Fixture fixture = fixture(helper, 7680);
        ServerLevel level = helper.getLevel();
        QuestSavedData data = QuestSavedData.get(level.getServer());
        RecordingPlayer player = player(level, fixture.board());
        interact(helper, player, fixture.board(), true, false);
        var active = data.progress(player.getUUID(), fixture.settlement().id());
        player.getInventory().armor.set(0, new ItemStack(Items.IRON_INGOT, 64));
        interact(helper, player, fixture.board(), true, false);
        helper.assertTrue(data.progress(player.getUUID(), fixture.settlement().id()).equals(active)
                && player.getInventory().armor.get(0).getCount() == 64 && count(player, Items.EMERALD) == 0,
                "Iron in armor slots must not count toward resource delivery");

        for (int slot = 0; slot < player.getInventory().items.size(); slot++) {
            player.getInventory().items.set(slot, new ItemStack(Items.COBBLESTONE, 64));
        }
        player.getInventory().offhand.set(0, new ItemStack(Items.IRON_INGOT, 32));
        interact(helper, player, fixture.board(), true, true);
        helper.assertTrue(data.progress(player.getUUID(), fixture.settlement().id()).equals(active)
                && count(player, Items.IRON_INGOT) == 32 && count(player, Items.EMERALD) == 0
                && player.getInventory().items.stream().allMatch(stack -> stack.is(Items.COBBLESTONE) && stack.getCount() == 64),
                "If all rewards cannot fit after delivery, refuse without consuming iron, changing inventory or completing the quest");

        player.getInventory().offhand.set(0, ItemStack.EMPTY);
        player.getInventory().items.set(0, new ItemStack(Items.IRON_INGOT, 16));
        interact(helper, player, fixture.board(), true, true);
        helper.assertTrue(data.progress(player.getUUID(), fixture.settlement().id()).state() == QuestState.COMPLETED
                && count(player, Items.IRON_INGOT) == 0 && player.getInventory().items.get(0).is(Items.EMERALD)
                && player.getInventory().items.get(0).getCount() == 8,
                "A slot freed by exact delivery must be usable for the full reward");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 400)
    public static void snapshottedTermsMergeRewardsAcrossStacks(GameTestHelper helper) {
        Fixture fixture = fixture(helper, 8704);
        ServerLevel level = helper.getLevel();
        QuestSavedData data = QuestSavedData.get(level.getServer());
        RecordingPlayer player = player(level, fixture.board());
        QuestTerms acceptedTerms = new QuestTerms(16, 80, 10);
        helper.assertTrue(data.accept(player.getUUID(), fixture.settlement().id(), acceptedTerms),
                "Fixture must accept its own frozen terms rather than the current eight-emerald offer");
        player.getInventory().items.set(0, new ItemStack(Items.EMERALD, 60));
        player.getInventory().items.set(1, new ItemStack(Items.IRON_INGOT, 16));

        interact(helper, player, fixture.board(), true, true);
        var completed = data.progress(player.getUUID(), fixture.settlement().id());
        helper.assertTrue(completed.state() == QuestState.COMPLETED && completed.terms().equals(acceptedTerms)
                && completed.reputation() == 10 && count(player, Items.IRON_INGOT) == 0 && count(player, Items.EMERALD) == 140,
                "Delivery must use accepted terms, merge the reward and grant all eighty emeralds exactly once");
        helper.assertTrue(player.getInventory().items.get(0).is(Items.EMERALD) && player.getInventory().items.get(0).getCount() == 64
                && player.getInventory().items.get(1).is(Items.EMERALD) && player.getInventory().items.get(1).getCount() == 64
                && player.getInventory().items.get(2).is(Items.EMERALD) && player.getInventory().items.get(2).getCount() == 12
                && player.getInventory().items.stream().allMatch(stack -> stack.isEmpty() || stack.getCount() <= stack.getMaxStackSize()),
                "Rewards exceeding a stack must merge existing emeralds and split across valid inventory slots");
        interact(helper, player, fixture.board(), true, false);
        helper.assertTrue(data.progress(player.getUUID(), fixture.settlement().id()).equals(completed)
                && count(player, Items.EMERALD) == 140, "Repeated delivery must not repeat a larger configured reward");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 400)
    public static void boardRequiresReachAndMatchingSettlement(GameTestHelper helper) {
        Fixture first = fixture(helper, 8192);
        ServerLevel level = helper.getLevel();
        QuestSavedData data = QuestSavedData.get(level.getServer());
        RecordingPlayer player = player(level, first.board());
        player.setPos(first.board().getX() + 12, first.board().getY(), first.board().getZ());
        interact(helper, player, first.board(), true, false);
        helper.assertTrue(data.progress(player.getUUID(), first.settlement().id()).state() == QuestState.AVAILABLE,
                "Board actions must reject an out-of-reach player even inside the territory");

        BlockPos exterior = first.board().offset(80, 0, 0);
        putBoard(level, exterior);
        moveToBoard(player, exterior);
        interact(helper, player, exterior, true, true);
        helper.assertTrue(data.progress(player.getUUID(), first.settlement().id()).state() == QuestState.AVAILABLE,
                "A board outside any settlement must not create a quest association");

        Fixture second = fixtureAt(level, first.board().offset(50, 0, 0));
        BlockPos edgeBoard = first.board().offset(23, 0, 0);
        putBoard(level, edgeBoard);
        player.setPos(first.board().getX() + 27.5, first.board().getY(), first.board().getZ() + 0.5);
        interact(helper, player, edgeBoard, true, false);
        helper.assertTrue(data.progress(player.getUUID(), first.settlement().id()).state() == QuestState.AVAILABLE
                && data.progress(player.getUUID(), second.settlement().id()).state() == QuestState.AVAILABLE,
                "A nearby player in a different settlement must not accept the board's quest");
        helper.succeed();
    }

    private static Fixture fixture(GameTestHelper helper, int offset) {
        return fixtureAt(helper.getLevel(), helper.absolutePos(new BlockPos(offset, 1, offset)));
    }

    private static Fixture fixtureAt(ServerLevel level, BlockPos requested) {
        SettlementSavedData settlements = SettlementSavedData.get(level.getServer());
        BlockPos board = requested;
        Territory territory = territory(level, board);
        while (settlements.overlaps(territory)) {
            board = board.offset(256, 0, 0);
            territory = territory(level, board);
        }
        Settlement settlement = Settlement.founding(UUID.randomUUID(), territory, 5);
        settlements.add(settlement);
        putBoard(level, board);
        return new Fixture(settlement, board);
    }

    private static Territory territory(ServerLevel level, BlockPos center) {
        return new Territory(level.dimension().location().toString(), center.getX(), center.getY(), center.getZ(), 24);
    }

    private static void putBoard(ServerLevel level, BlockPos board) {
        level.getChunk(board.getX() >> 4, board.getZ() >> 4);
        level.setBlock(board.below(), Blocks.GRASS_BLOCK.defaultBlockState(), FLAGS);
        level.setBlock(board, KingdomBlocks.QUEST_BOARD.get().defaultBlockState(), FLAGS);
    }

    private static RecordingPlayer player(ServerLevel level, BlockPos board) {
        RecordingPlayer player = new RecordingPlayer(level, UUID.randomUUID());
        moveToBoard(player, board);
        return player;
    }

    private static void moveToBoard(RecordingPlayer player, BlockPos board) {
        player.setPos(board.getX() + 2.5, board.getY(), board.getZ() + 0.5);
    }

    private static BlockHitResult hit(BlockPos board) {
        return new BlockHitResult(Vec3.atCenterOf(board), Direction.WEST, board, false);
    }

    private static void interact(GameTestHelper helper, RecordingPlayer player, BlockPos board, boolean sneaking, boolean heldItem) {
        player.setShiftKeyDown(sneaking);
        var level = player.serverLevel();
        var state = level.getBlockState(board);
        if (heldItem) {
            helper.assertTrue(state.useItemOn(new ItemStack(Items.STICK), level, player, InteractionHand.MAIN_HAND, hit(board))
                    == ItemInteractionResult.CONSUME, "Held-item board use must consume the action on the server");
        } else {
            helper.assertTrue(state.useWithoutItem(level, player, hit(board)) == InteractionResult.CONSUME,
                    "Empty-hand board use must consume the action on the server");
        }
        // Regression coverage for the preserved delivery backend. Packet-driven UI is covered separately.
        dev.livingkingdoms.quest.QuestService.interact(player, board, sneaking);
    }

    private static int count(RecordingPlayer player, Item item) {
        Inventory inventory = player.getInventory();
        return inventory.items.stream().filter(stack -> stack.is(item)).mapToInt(ItemStack::getCount).sum()
                + inventory.offhand.stream().filter(stack -> stack.is(item)).mapToInt(ItemStack::getCount).sum();
    }

    private static boolean hasKey(Component message, String key) {
        return message.getContents() instanceof TranslatableContents translated && translated.getKey().equals(key);
    }

    private record Fixture(Settlement settlement, BlockPos board) { }

    private static final class RecordingPlayer extends FakePlayer {
        final List<Component> messages = new ArrayList<>();
        RecordingPlayer(ServerLevel level, UUID id) { super(level, new GameProfile(id, "QuestTest")); }
        @Override public void displayClientMessage(Component message, boolean overlay) { messages.add(message); }
        @Override public void sendSystemMessage(Component message) { messages.add(message); }
    }
}
