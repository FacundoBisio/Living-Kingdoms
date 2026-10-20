package dev.livingkingdoms.gametest;

import dev.livingkingdoms.config.KingdomConfig;
import dev.livingkingdoms.config.NpcKingdomConfig;
import dev.livingkingdoms.economy.KingdomEconomy;
import dev.livingkingdoms.npckingdom.NpcKingdomPolicy;
import dev.livingkingdoms.npckingdom.NpcKingdomService;
import dev.livingkingdoms.npckingdom.NpcKingdoms;
import dev.livingkingdoms.npckingdom.persistence.NpcKingdomSavedData;
import dev.livingkingdoms.onboarding.KingdomOnboardingService;
import dev.livingkingdoms.onboarding.persistence.KingdomOnboardingSavedData;
import dev.livingkingdoms.progression.domain.LevelValue;
import dev.livingkingdoms.quest.domain.QuestState;
import dev.livingkingdoms.quest.expansion.ExpandedQuestService;
import dev.livingkingdoms.quest.expansion.domain.QuestDifficulty;
import dev.livingkingdoms.quest.expansion.domain.QuestInstance;
import dev.livingkingdoms.quest.expansion.domain.QuestObjective;
import dev.livingkingdoms.quest.expansion.domain.QuestRewards;
import dev.livingkingdoms.quest.expansion.domain.QuestSource;
import dev.livingkingdoms.quest.expansion.domain.QuestSourceRole;
import dev.livingkingdoms.quest.expansion.domain.QuestTemplate;
import dev.livingkingdoms.quest.expansion.domain.ResourceKind;
import dev.livingkingdoms.quest.expansion.domain.ResourceRequirement;
import dev.livingkingdoms.quest.persistence.QuestSavedData;
import dev.livingkingdoms.settlement.SettlementGenerator;
import dev.livingkingdoms.settlement.persistence.SettlementSavedData;
import dev.livingkingdoms.ui.VillageUiService;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Independent NPC kingdoms: registration, independence gates and the chunk-safe natural trigger. */
@GameTestHolder(KingdomGameTests.MOD_ID)
@PrefixGameTestTemplate(false)
public final class NpcKingdomGameTests {
    private static final int FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;

    private NpcKingdomGameTests() {}

    @GameTest(template = "empty", timeoutTicks = 600)
    public static void generatedNpcKingdomRejectsMembershipQuestsAndConsumption(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        var server = level.getServer();
        BlockPos center = helper.absolutePos(new BlockPos(61440, 0, -61440));
        prepare(level, center, 38);
        var result = new SettlementGenerator().generateAt(level, center);
        helper.assertTrue(result.successful(), "Prepared pad must host a generated village: " + result.diagnostics());
        var settlement = result.settlement();
        var registry = NpcKingdomSavedData.get(server);
        int before = registry.count();
        helper.assertTrue(NpcKingdomService.registerExplicit(level, settlement), "Generated village registers as an NPC kingdom");
        helper.assertTrue(registry.count() == before + 1 && NpcKingdoms.isNpc(server, settlement.id())
                && registry.ruler(settlement.id()).filter(name -> !name.isBlank() && name.length() <= NpcKingdomPolicy.MAX_RULER_LENGTH).isPresent(),
                "Registry records a bounded ruler name");
        helper.assertTrue(registry.attempted(NpcKingdomPolicy.cell(settlement.territory().x(), settlement.territory().z(),
                NpcKingdomConfig.CELL_SIZE.get()).key()), "Explicit generation consumes its region cell");
        helper.assertTrue(!NpcKingdomService.registerExplicit(level, settlement), "A kingdom cannot be registered twice");

        UiTestPlayer player = new UiTestPlayer(level);
        player.getAbilities().mayBuild = true;
        player.setPos(settlement.territory().x() + 0.5, settlement.territory().y(), settlement.territory().z() + 3.5);
        // Legacy-looking quest history would normally enroll the player; NPC kingdoms must not.
        var quests = QuestSavedData.get(server);
        long now = server.overworld().getGameTime();
        var food = new QuestInstance(UUID.randomUUID(), QuestTemplate.FOOD_REQUEST, new QuestSource(settlement.id(), null, QuestSourceRole.FARMER),
                new QuestObjective.Resource(List.of(new ResourceRequirement(ResourceKind.WHEAT, 4))), new LevelValue(1), QuestDifficulty.VERY_EASY,
                new QuestRewards(1, 1), QuestState.AVAILABLE, false, now, now + 24000);
        helper.assertTrue(quests.offer(player.getUUID(), food) && quests.acceptExpanded(player.getUUID(), food.id()), "Legacy-style history fixture");
        var onboarding = KingdomOnboardingSavedData.get(server);
        helper.assertTrue(!KingdomOnboardingService.canHelp(player, settlement), "Players cannot help an NPC kingdom");
        helper.assertTrue(onboarding.journey(player.getUUID(), settlement.id()).isEmpty()
                && onboarding.registration(settlement.id()).isEmpty(), "No legacy enrollment or registration for NPC kingdoms");
        helper.assertTrue(!KingdomEconomy.authorized(player, settlement.id()), "Economy, storage and management stay closed");
        ExpandedQuestService.prepare(player, settlement);
        helper.assertTrue(quests.mainSettlement(player.getUUID()).isEmpty(), "An NPC kingdom never anchors the main chain");
        helper.assertTrue(!KingdomEconomy.consume(server, settlement), "NPC kingdoms are exempt from food consumption");

        player.clientMessages.clear();
        helper.assertTrue(!VillageUiService.openShortcut(player), "Kingdom shortcut does not open management for visitors");
        helper.assertTrue(player.clientMessages.stream().anyMatch(NpcKingdomGameTests::visitorNotice), "Shortcut explains the visitor status");
        var mayorId = quests.mayor(settlement.id()).orElse(null);
        if (mayorId != null && level.getEntity(mayorId) instanceof Villager mayor) {
            player.setPos(mayor.getX(), mayor.getY(), mayor.getZ() + 1);
            player.clientMessages.clear();
            helper.assertTrue(!KingdomOnboardingService.begin(player, settlement, mayor), "Mayor onboarding never starts");
            helper.assertTrue(!KingdomOnboardingService.advance(player, settlement, mayor, "REGISTER", "Stolen Realm"), "Players cannot join or rename");
            helper.assertTrue(!VillageUiService.openDialogue(player, mayor)
                    && player.clientMessages.stream().anyMatch(NpcKingdomGameTests::visitorNotice), "Mayor greets the player as a visitor");
            helper.assertTrue(onboarding.journey(player.getUUID(), settlement.id()).isEmpty(), "No onboarding journey was recorded");
        }
        helper.assertTrue(SettlementSavedData.get(server).get(settlement.id()).orElseThrow().name().equals(settlement.name()),
                "The NPC kingdom keeps its generated name");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void naturalAttemptNeverLoadsMissingChunks(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        var server = level.getServer();
        int cellSize = NpcKingdomConfig.CELL_SIZE.get();
        BlockPos far = helper.absolutePos(new BlockPos(-71680, 0, 81920));
        var cell = NpcKingdomPolicy.cell(far.getX(), far.getZ(), cellSize);
        var center = NpcKingdomPolicy.center(level.getSeed(), cell, cellSize, KingdomConfig.SETTLEMENT_RADIUS.get());
        int chunkX = center.x() >> 4, chunkZ = center.z() >> 4;
        helper.assertTrue(!level.getChunkSource().hasChunk(chunkX, chunkZ), "Fixture center chunk must be unloaded");
        var registry = NpcKingdomSavedData.get(server);
        int kingdoms = registry.count();
        int settlements = SettlementSavedData.get(server).settlements().size();
        var outcome = NpcKingdomService.attempt(level, cell.key());
        helper.assertTrue(Set.of(NpcKingdomService.Outcome.UNLOADED_RETRY, NpcKingdomService.Outcome.UNLOADED_ABANDONED,
                NpcKingdomService.Outcome.ALREADY_ATTEMPTED, NpcKingdomService.Outcome.LIMIT_REACHED).contains(outcome),
                "Unloaded territory is deferred, never generated: " + outcome);
        helper.assertTrue(!level.getChunkSource().hasChunk(chunkX, chunkZ), "The natural trigger must not load or generate chunks");
        helper.assertTrue(registry.count() == kingdoms && SettlementSavedData.get(server).settlements().size() == settlements,
                "No settlement or NPC kingdom is created for unloaded terrain");
        helper.succeed();
    }

    private static boolean visitorNotice(net.minecraft.network.chat.Component message) {
        return message.getContents() instanceof TranslatableContents translated && translated.getKey().equals(NpcKingdoms.VISITOR_KEY);
    }

    private static void prepare(ServerLevel level, BlockPos center, int radius) {
        for (int x = (center.getX() - radius) >> 4; x <= (center.getX() + radius) >> 4; x++)
            for (int z = (center.getZ() - radius) >> 4; z <= (center.getZ() + radius) >> 4; z++) level.getChunk(x, z);
        for (int x = -radius; x <= radius; x++)
            for (int z = -radius; z <= radius; z++) {
                level.setBlock(center.offset(x, -1, z), Blocks.GRASS_BLOCK.defaultBlockState(), FLAGS);
                for (int y = -3; y < -1; y++) level.setBlock(center.offset(x, y, z), Blocks.DIRT.defaultBlockState(), FLAGS);
                for (int y = 0; y < 13; y++) level.setBlock(center.offset(x, y, z), Blocks.AIR.defaultBlockState(), FLAGS);
            }
    }
}
