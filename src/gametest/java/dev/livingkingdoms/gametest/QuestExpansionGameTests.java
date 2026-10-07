package dev.livingkingdoms.gametest;

import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.livingkingdoms.block.KingdomBlocks;
import dev.livingkingdoms.config.QuestExpansionConfig;
import dev.livingkingdoms.encounter.EncounterSpawner;
import dev.livingkingdoms.encounter.domain.HostileParty;
import dev.livingkingdoms.encounter.domain.PartyState;
import dev.livingkingdoms.encounter.domain.PartyType;
import dev.livingkingdoms.encounter.persistence.EncounterSavedData;
import dev.livingkingdoms.npc.NpcInteractions;
import dev.livingkingdoms.npc.NpcService;
import dev.livingkingdoms.progression.domain.LevelValue;
import dev.livingkingdoms.quest.QuestService;
import dev.livingkingdoms.quest.domain.QuestState;
import dev.livingkingdoms.quest.expansion.domain.*;
import dev.livingkingdoms.quest.persistence.QuestSavedData;
import dev.livingkingdoms.settlement.domain.Settlement;
import dev.livingkingdoms.settlement.domain.Territory;
import dev.livingkingdoms.settlement.persistence.SettlementSavedData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.npc.Villager;
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
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Board/NPC/command/event flows with isolated server fixtures, retaining the old iron quest. */
@GameTestHolder(KingdomGameTests.MOD_ID)
@PrefixGameTestTemplate(false)
public final class QuestExpansionGameTests {
    private static final int FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;

    @GameTest(template = "empty", timeoutTicks = 400)
    public static void boardInspectionAndPlayerCommandsEnforceOwnershipAndReach(GameTestHelper helper) throws CommandSyntaxException {
        Fixture fixture = fixture(helper, 76000);
        RecordingPlayer player = player(helper.getLevel(), fixture.board(), UUID.randomUUID());
        RecordingPlayer other = player(helper.getLevel(), fixture.board(), UUID.randomUUID());
        QuestSavedData data = data(helper);
        QuestInstance food = resource(fixture, QuestTemplate.FOOD_REQUEST, List.of(required(ResourceKind.WHEAT, 12)), 4, 5, now(helper));
        QuestInstance building = resource(fixture, QuestTemplate.BUILDING_REQUEST,
                List.of(required(ResourceKind.LOGS, 8), required(ResourceKind.STONE, 6)), 5, 6, now(helper));
        rotate(helper, player, fixture, List.of(food, building));
        inspect(helper, player, fixture.board());
        helper.assertTrue(main(data, player, fixture, QuestTemplate.FIRST_MEETING).state() == QuestState.AVAILABLE
                && quest(data, player, food).state() == QuestState.AVAILABLE
                && quest(data, player, building).state() == QuestState.AVAILABLE,
                "Actual board inspection must present main and resource requests without accepting them");
        helper.assertTrue(player.messages.stream().anyMatch(message -> hasKey(message, "quest.livingkingdoms.main_heading"))
                && player.messages.stream().anyMatch(message -> hasKey(message, "quest.livingkingdoms.requests_heading")),
                "The board must show both quest categories through localized feedback");
        String acceptFood = click(player, food.id(), "accept");
        helper.assertTrue(execute(other, acceptFood) == 0 && quest(data, player, food).state() == QuestState.AVAILABLE,
                "Another player cannot accept an offer using its visible UUID");
        player.setPos(fixture.board().getX() + 16, fixture.board().getY(), fixture.board().getZ());
        helper.assertTrue(execute(player, acceptFood) == 0 && quest(data, player, food).state() == QuestState.AVAILABLE,
                "The clickable command must revalidate board reach on the server");
        move(player, fixture.board());
        helper.assertTrue(action(player, fixture.board().offset(0, 1, 0), food.id(), "accept") == 0,
                "An in-reach air block cannot substitute for a quest board");
        Fixture elsewhere = fixture(helper, 76256);
        move(player, elsewhere.board());
        helper.assertTrue(action(player, elsewhere.board(), food.id(), "accept") == 0
                && quest(data, player, food).state() == QuestState.AVAILABLE,
                "An actual board in another allied settlement cannot act on this settlement's offer");
        move(player, fixture.board());
        helper.assertTrue(execute(player, acceptFood) == 1 && quest(data, player, food).state() == QuestState.ACTIVE,
                "A normal permission-zero player must be able to use the real accept command");
        player.getInventory().items.set(0, new ItemStack(Items.WHEAT, 16));
        helper.assertTrue(execute(player, click(player, food.id(), "claim")) == 1
                && quest(data, player, food).state() == QuestState.COMPLETED
                && count(player, Items.WHEAT) == 4 && count(player, Items.EMERALD) == 4,
                "The actual claim command must deliver typed food and award its frozen reward");
        helper.assertTrue(action(player, fixture.board(), building.id(), "accept") == 1, "Building request accepts independently");
        player.getInventory().items.set(4, new ItemStack(Items.BIRCH_LOG, 10));
        player.getInventory().offhand.set(0, new ItemStack(Items.STONE, 8));
        helper.assertTrue(action(player, fixture.board(), building.id(), "claim") == 1
                && quest(data, player, building).state() == QuestState.COMPLETED
                && count(player, Items.BIRCH_LOG) == 2 && count(player, Items.STONE) == 2
                && count(player, Items.EMERALD) == 9 && data.reputation(player.getUUID(), fixture.settlement().id()) == 11,
                "Building delivery must atomically consume tagged logs plus exact stone and award only this settlement");
        helper.assertTrue(data.reputation(other.getUUID(), fixture.settlement().id()) == 0,
                "Successful resource deliveries must not reward another player");
        helper.assertTrue(data.reputation(player.getUUID(), elsewhere.settlement().id()) == 0,
                "Resource delivery rewards must remain local to the source settlement");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 400)
    public static void resourceClaimsReconnectReloadAndRotationRemainOnce(GameTestHelper helper) throws CommandSyntaxException {
        Fixture fixture = fixture(helper, 78000);
        RecordingPlayer original = player(helper.getLevel(), fixture.board(), UUID.randomUUID());
        QuestSavedData data = data(helper);
        QuestInstance food = resource(fixture, QuestTemplate.FOOD_REQUEST, List.of(required(ResourceKind.WHEAT, 12)), 4, 5, now(helper));
        rotate(helper, original, fixture, List.of(food));
        inspect(helper, original, fixture.board());
        long generation = data.boardGeneration(original.getUUID(), fixture.settlement().id());
        long refreshed = data.boardRefreshAt(original.getUUID(), fixture.settlement().id());
        helper.assertTrue(action(original, fixture.board(), food.id(), "accept") == 1, "Fixture accepts food request through normal command");
        original.getInventory().offhand.set(0, new ItemStack(Items.WHEAT, 20));
        helper.assertTrue(action(original, fixture.board(), food.id(), "claim") == 1, "Fixture claims resource request");
        QuestInstance completed = quest(data, original, food);
        for (int i = 0; i < 3; i++) helper.assertTrue(action(original, fixture.board(), food.id(), "claim") == 0,
                "Repeated claim commands must be refused");
        inspect(helper, original, fixture.board());
        helper.assertTrue(count(original, Items.EMERALD) == 4 && count(original, Items.WHEAT) == 8
                && data.reputation(original.getUUID(), fixture.settlement().id()) == 5
                && data.boardGeneration(original.getUUID(), fixture.settlement().id()) == generation
                && data.boardRefreshAt(original.getUUID(), fixture.settlement().id()) == refreshed,
                "Opening/claiming cannot repeat rewards or reroll the persisted board batch");
        RecordingPlayer reconnected = player(helper.getLevel(), fixture.board(), original.getUUID());
        reconnected.getInventory().items.set(0, new ItemStack(Items.WHEAT, 20));
        helper.assertTrue(action(reconnected, fixture.board(), food.id(), "claim") == 0
                && count(reconnected, Items.WHEAT) == 20 && count(reconnected, Items.EMERALD) == 0,
                "A new player object with the same UUID cannot claim the completed reward again");
        helper.getLevel().getDataStorage().save();
        IOUtilities.waitUntilIOWorkerComplete();
        var directory = helper.getLevel().getServer().getWorldPath(LevelResource.ROOT).resolve("data");
        var storage = new DimensionDataStorage(directory.toFile(), null, helper.getLevel().registryAccess());
        QuestSavedData reopened = storage.get(new SavedData.Factory<>(QuestSavedData::new, QuestSavedData::load), QuestSavedData.DATA_NAME);
        IOUtilities.waitUntilIOWorkerComplete();
        helper.assertTrue(reopened != null && reopened != data && !reopened.isDirty()
                && reopened.quest(original.getUUID(), food.id()).orElseThrow().equals(completed)
                && reopened.reputation(original.getUUID(), fixture.settlement().id()) == 5
                && reopened.boardGeneration(original.getUUID(), fixture.settlement().id()) == generation
                && reopened.boardRefreshAt(original.getUUID(), fixture.settlement().id()) == refreshed
                && !reopened.completeExpanded(original.getUUID(), food.id()),
                "Actual disk reload must retain completion, one-time reward closure and board cooldown");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 400)
    public static void mayorIronCombatAndReturnCompleteOrderedMainChain(GameTestHelper helper) throws CommandSyntaxException {
        Fixture fixture = fixture(helper, 80000);
        RecordingPlayer player = player(helper.getLevel(), fixture.board(), UUID.randomUUID());
        QuestSavedData data = data(helper);
        inspect(helper, player, fixture.board());
        Villager mayor = NpcService.ensureMayor(helper.getLevel(), fixture.settlement()).orElseThrow();
        move(player, mayor.blockPosition());
        var event = new PlayerInteractEvent.EntityInteract(player, InteractionHand.MAIN_HAND, mayor);
        NpcInteractions.onInteract(event);
        helper.assertTrue(event.isCanceled() && main(data, player, fixture, QuestTemplate.FIRST_MEETING).state() == QuestState.COMPLETED,
                "An actual validated Mayor interaction must complete the first main step");
        move(player, fixture.board());
        QuestInstance iron = main(data, player, fixture, QuestTemplate.MAIN_IRON);
        helper.assertTrue(action(player, fixture.board(), iron.id(), "accept") == 1, "Main iron accepts the existing iron-delivery quest");
        var terms = data.progress(player.getUUID(), fixture.settlement().id()).terms();
        player.getInventory().items.set(0, new ItemStack(Items.IRON_INGOT, terms.requiredIron()));
        helper.assertTrue(action(player, fixture.board(), iron.id(), "claim") == 1
                && data.progress(player.getUUID(), fixture.settlement().id()).state() == QuestState.COMPLETED
                && main(data, player, fixture, QuestTemplate.MAIN_IRON).state() == QuestState.COMPLETED
                && count(player, Items.EMERALD) == terms.rewardEmeralds(),
                "Main iron must synchronize the original completed quest without issuing a duplicate reward");
        HostileParty patrol = spawn(helper, fixture.board().offset(48, 0, 0), PartyType.PILLAGER_PATROL, false);
        inspect(helper, player, fixture.board());
        QuestInstance combat = main(data, player, fixture, QuestTemplate.MAIN_PATROL);
        helper.assertTrue(((QuestObjective.Party) combat.objective()).partyId().equals(patrol.id()),
                "The main patrol must bind the actual nearby non-debug party UUID");
        helper.assertTrue(action(player, fixture.board(), combat.id(), "accept") == 1, "Main patrol accepts through the normal command");
        RecordingPlayer observer = player(helper.getLevel(), fixture.board(), UUID.randomUUID());
        QuestInstance observerQuest = partyRequest(fixture, QuestTemplate.PILLAGER_REQUEST, patrol, now(helper));
        rotate(helper, observer, fixture, List.of(observerQuest));
        helper.assertTrue(action(observer, fixture.board(), observerQuest.id(), "accept") == 1, "Observer may accept an independent patrol contract");
        defeat(helper, patrol, player);
        helper.assertTrue(EncounterSavedData.get(helper.getLevel().getServer()).get(patrol.id()).orElseThrow().state() == PartyState.DEFEATED
                && main(data, player, fixture, QuestTemplate.MAIN_PATROL).objectiveSatisfied()
                && quest(data, observer, observerQuest).state() == QuestState.FAILED
                && data.reputation(observer.getUUID(), fixture.settlement().id()) == 0,
                "Actual member deaths must mark a contributing player's bound objective ready and fail the nonparticipant's contract");
        helper.assertTrue(action(player, fixture.board(), combat.id(), "claim") == 1, "Completed combat objective can be claimed once");
        QuestInstance returned = main(data, player, fixture, QuestTemplate.MAIN_RETURN);
        helper.assertTrue(action(player, fixture.board(), returned.id(), "accept") == 1
                && action(player, fixture.board(), returned.id(), "claim") == 1,
                "Returning to the settlement must complete the final ordered main step through normal commands");
        int expectedEmeralds = terms.rewardEmeralds() + returned.rewards().emeralds();
        int expectedReputation = terms.reputationReward() + patrol.reputationReward() + returned.rewards().reputation();
        helper.assertTrue(data.quests(player.getUUID(), fixture.settlement().id()).stream()
                .filter(quest -> quest.template().category() == QuestCategory.MAIN && quest.state() == QuestState.COMPLETED).count() == 4
                && count(player, Items.EMERALD) == expectedEmeralds
                && data.reputation(player.getUUID(), fixture.settlement().id()) == expectedReputation
                && action(player, fixture.board(), returned.id(), "claim") == 0
                && count(player, Items.EMERALD) == expectedEmeralds,
                "All four main steps must complete in order and preserve single legacy, regional and return rewards");
        mayor.discard();
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 400)
    public static void undeadVictoryRequiresTheBoundPartyAndActualParticipation(GameTestHelper helper) throws CommandSyntaxException {
        Fixture fixture = fixture(helper, 82000);
        RecordingPlayer player = player(helper.getLevel(), fixture.board(), UUID.randomUUID());
        HostileParty unrelated = spawn(helper, fixture.board().offset(48, 0, 0), PartyType.UNDEAD_HORDE, false);
        HostileParty target = spawn(helper, fixture.board().offset(96, 0, 0), PartyType.UNDEAD_HORDE, false);
        QuestSavedData data = data(helper);
        QuestInstance request = partyRequest(fixture, QuestTemplate.UNDEAD_REQUEST, target, now(helper));
        rotate(helper, player, fixture, List.of(request));
        helper.assertTrue(action(player, fixture.board(), request.id(), "accept") == 1, "Undead contract must accept normally");
        defeat(helper, unrelated, player);
        Mob vanilla = EntityType.ZOMBIE.create(helper.getLevel());
        vanilla.setNoAi(true);
        vanilla.moveTo(fixture.board().getX() + 12.5, fixture.board().getY(), fixture.board().getZ() + 0.5, 0, 0);
        helper.assertTrue(helper.getLevel().addFreshEntity(vanilla)
                && vanilla.hurt(helper.getLevel().damageSources().playerAttack(player), 1000), "Unrelated vanilla zombie dies through actual damage");
        helper.assertTrue(!quest(data, player, request).objectiveSatisfied()
                && quest(data, player, request).state() == QuestState.ACTIVE
                && action(player, fixture.board(), request.id(), "claim") == 0,
                "Defeating another Undead party or an ordinary zombie cannot satisfy this UUID-bound contract");
        defeat(helper, target, player);
        helper.assertTrue(quest(data, player, request).objectiveSatisfied()
                && action(player, fixture.board(), request.id(), "claim") == 1
                && count(player, Items.EMERALD) == request.rewards().emeralds()
                && data.reputation(player.getUUID(), fixture.settlement().id())
                    == unrelated.reputationReward() + target.reputationReward() + request.rewards().reputation()
                && action(player, fixture.board(), request.id(), "claim") == 0,
                "Only the bound horde defeat event may unlock this contract's one-time reward");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 400)
    public static void debugMissingAndRetiredTargetsCannotProduceVictory(GameTestHelper helper) throws CommandSyntaxException {
        Fixture fixture = fixture(helper, 84000);
        RecordingPlayer player = player(helper.getLevel(), fixture.board(), UUID.randomUUID());
        QuestSavedData data = data(helper);
        HostileParty debug = spawn(helper, fixture.board().offset(48, 0, 0), PartyType.PILLAGER_PATROL, true);
        HostileParty retired = spawn(helper, fixture.board().offset(96, 0, 0), PartyType.PILLAGER_PATROL, false);
        QuestInstance missing = request(fixture, QuestTemplate.PILLAGER_REQUEST,
                new QuestObjective.Party(UUID.randomUUID(), retired.faction(), retired.type()), now(helper));
        QuestInstance debugQuest = partyRequest(fixture, QuestTemplate.PILLAGER_REQUEST, debug, now(helper));
        QuestInstance retiredQuest = partyRequest(fixture, QuestTemplate.PILLAGER_REQUEST, retired, now(helper));
        rotate(helper, player, fixture, List.of(missing, debugQuest, retiredQuest));
        inspect(helper, player, fixture.board());
        helper.assertTrue(quest(data, player, missing).state() == QuestState.FAILED
                && quest(data, player, debugQuest).state() == QuestState.FAILED
                && action(player, fixture.board(), missing.id(), "accept") == 0
                && action(player, fixture.board(), debugQuest.id(), "accept") == 0,
                "Missing and debug targets must fail safely during board validation");
        helper.assertTrue(action(player, fixture.board(), retiredQuest.id(), "accept") == 1, "A live non-debug target initially accepts");
        List<Mob> members = members(helper.getLevel(), retired);
        EncounterSavedData.get(helper.getLevel().getServer()).remove(retired.id());
        inspect(helper, player, fixture.board());
        helper.assertTrue(quest(data, player, retiredQuest).state() == QuestState.FAILED
                && !quest(data, player, retiredQuest).objectiveSatisfied()
                && action(player, fixture.board(), retiredQuest.id(), "claim") == 0,
                "Retiring metadata without a victory event must fail an active objective and never invent participation");
        members.forEach(Mob::discard);
        defeat(helper, debug, player);
        helper.assertTrue(count(player, Items.EMERALD) == 0
                && data.reputation(player.getUUID(), fixture.settlement().id()) == 0
                && !quest(data, player, debugQuest).objectiveSatisfied(),
                "Defeating a debug encounter cannot turn a failed contract into rewards");
        helper.succeed();
    }

    private static Fixture fixture(GameTestHelper helper, int offset) {
        ServerLevel level = helper.getLevel();
        BlockPos marker = helper.absolutePos(new BlockPos(offset, 1, offset));
        prepare(level, marker, 12);
        Territory territory = new Territory(level.dimension().location().toString(), marker.getX(), marker.getY(), marker.getZ(), 24);
        Settlement settlement = Settlement.founding(UUID.randomUUID(), territory, 5);
        SettlementSavedData.get(level.getServer()).add(settlement);
        level.setBlock(marker, Blocks.LODESTONE.defaultBlockState(), FLAGS);
        BlockPos board = marker.offset(4, 0, 0);
        level.setBlock(board, KingdomBlocks.QUEST_BOARD.get().defaultBlockState(), FLAGS);
        return new Fixture(settlement, board);
    }

    private static void inspect(GameTestHelper helper, RecordingPlayer player, BlockPos board) {
        move(player, board);
        player.setShiftKeyDown(false);
        BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(board), Direction.WEST, board, false);
        helper.assertTrue(helper.getLevel().getBlockState(board).useWithoutItem(helper.getLevel(), player, hit) == InteractionResult.CONSUME,
                "The fixture must traverse the actual empty-hand Quest Board interaction");
    }

    private static int action(RecordingPlayer player, BlockPos board, UUID quest, String action) throws CommandSyntaxException {
        return execute(player, "/kingdom quest " + action + " " + quest + " " + board.getX() + " " + board.getY() + " " + board.getZ());
    }
    private static int execute(RecordingPlayer player, String command) throws CommandSyntaxException {
        return player.server.getCommands().getDispatcher().execute(command.startsWith("/") ? command.substring(1) : command,
                player.createCommandSourceStack().withPermission(0));
    }
    private static String click(RecordingPlayer player, UUID quest, String action) {
        return player.messages.stream().map(message -> message.getStyle().getClickEvent()).filter(click -> click != null
                && click.getAction() == ClickEvent.Action.RUN_COMMAND && click.getValue().contains("quest " + action + " " + quest))
                .reduce((previous, last) -> last).orElseThrow().getValue();
    }
    private static void rotate(GameTestHelper helper, RecordingPlayer player, Fixture fixture, List<QuestInstance> offers) {
        helper.assertTrue(data(helper).rotateBoard(player.getUUID(), fixture.settlement().id(), now(helper),
                QuestExpansionConfig.rules().refreshTicks(), offers), "Controlled fixture offers must preflight a new persisted board batch");
    }
    private static QuestInstance resource(Fixture fixture, QuestTemplate template, List<ResourceRequirement> terms,
                                           int emeralds, int reputation, long now) {
        return new QuestInstance(UUID.randomUUID(), template,
                new QuestSource(fixture.settlement().id(), null, template.sourceRole()), new QuestObjective.Resource(terms),
                new LevelValue(5), QuestDifficulty.EASY, new QuestRewards(emeralds, reputation), QuestState.AVAILABLE, false, now, now + 48000);
    }
    private static QuestInstance partyRequest(Fixture fixture, QuestTemplate template, HostileParty party, long now) {
        return request(fixture, template, new QuestObjective.Party(party.id(), party.faction(), party.type()), now);
    }
    private static QuestInstance request(Fixture fixture, QuestTemplate template, QuestObjective objective, long now) {
        return new QuestInstance(UUID.randomUUID(), template, new QuestSource(fixture.settlement().id(), null, template.sourceRole()),
                objective, new LevelValue(5), QuestDifficulty.EASY, new QuestRewards(5, 7), QuestState.AVAILABLE, false, now, now + 48000);
    }
    private static HostileParty spawn(GameTestHelper helper, BlockPos origin, PartyType type, boolean debug) {
        prepare(helper.getLevel(), origin, 10);
        var result = EncounterSpawner.spawnAt(helper.getLevel(), origin, type, debug, !debug);
        helper.assertTrue(result.successful(), "Fixture must spawn actual " + type + " entities: " + result.failure());
        members(helper.getLevel(), result.party()).forEach(mob -> mob.setNoAi(true));
        return result.party();
    }
    private static List<Mob> members(ServerLevel level, HostileParty party) {
        return party.remainingMembers().stream().map(id -> (Mob) level.getEntity(id)).toList();
    }
    private static void defeat(GameTestHelper helper, HostileParty party, RecordingPlayer player) {
        for (Mob member : members(helper.getLevel(), party)) {
            member.invulnerableTime = 0;
            helper.assertTrue(member.hurt(helper.getLevel().damageSources().playerAttack(player), 1000),
                    "Actual player damage must drive contribution and death events for each encounter member");
        }
    }
    private static void prepare(ServerLevel level, BlockPos center, int radius) {
        for (int x = (center.getX() - radius) >> 4; x <= (center.getX() + radius) >> 4; x++)
            for (int z = (center.getZ() - radius) >> 4; z <= (center.getZ() + radius) >> 4; z++) level.getChunk(x, z);
        for (int x = -radius; x <= radius; x++) for (int z = -radius; z <= radius; z++) {
            level.setBlock(center.offset(x, -1, z), Blocks.GRASS_BLOCK.defaultBlockState(), FLAGS);
            for (int y = 0; y < 8; y++) level.setBlock(center.offset(x, y, z), Blocks.AIR.defaultBlockState(), FLAGS);
        }
    }
    private static RecordingPlayer player(ServerLevel level, BlockPos board, UUID id) {
        RecordingPlayer player = new RecordingPlayer(level, id);
        move(player, board);
        return player;
    }
    private static void move(RecordingPlayer player, BlockPos board) { player.setPos(board.getX() + 2.5, board.getY(), board.getZ() + 0.5); }
    private static ResourceRequirement required(ResourceKind resource, int count) { return new ResourceRequirement(resource, count); }
    private static QuestSavedData data(GameTestHelper helper) { return QuestSavedData.get(helper.getLevel().getServer()); }
    private static long now(GameTestHelper helper) { return helper.getLevel().getServer().overworld().getGameTime(); }
    private static QuestInstance quest(QuestSavedData data, RecordingPlayer player, QuestInstance quest) { return data.quest(player.getUUID(), quest.id()).orElseThrow(); }
    private static QuestInstance main(QuestSavedData data, RecordingPlayer player, Fixture fixture, QuestTemplate template) {
        return data.quests(player.getUUID(), fixture.settlement().id()).stream().filter(quest -> quest.template() == template).findFirst().orElseThrow();
    }
    private static int count(RecordingPlayer player, Item item) {
        return player.getInventory().items.stream().filter(stack -> stack.is(item)).mapToInt(ItemStack::getCount).sum()
                + player.getInventory().offhand.stream().filter(stack -> stack.is(item)).mapToInt(ItemStack::getCount).sum();
    }
    private static boolean hasKey(Component message, String key) {
        return message.getContents() instanceof TranslatableContents contents && contents.getKey().equals(key);
    }
    private record Fixture(Settlement settlement, BlockPos board) {}
    private static final class RecordingPlayer extends FakePlayer {
        private final List<Component> messages = new ArrayList<>();
        private RecordingPlayer(ServerLevel level, UUID id) { super(level, new GameProfile(id, "ExpandedQuestTest")); }
        @Override public void displayClientMessage(Component message, boolean overlay) { messages.add(message); }
        @Override public void sendSystemMessage(Component message) { messages.add(message); }
    }
}
