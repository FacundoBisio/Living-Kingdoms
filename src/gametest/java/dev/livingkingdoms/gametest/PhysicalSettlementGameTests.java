package dev.livingkingdoms.gametest;

import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.livingkingdoms.block.KingdomBlocks;
import dev.livingkingdoms.block.QuestBoardBlock;
import dev.livingkingdoms.config.KingdomConfig;
import dev.livingkingdoms.npc.NpcIdentity;
import dev.livingkingdoms.npc.NpcInteractions;
import dev.livingkingdoms.npc.NpcRole;
import dev.livingkingdoms.npc.NpcService;
import dev.livingkingdoms.quest.domain.QuestState;
import dev.livingkingdoms.quest.persistence.QuestSavedData;
import dev.livingkingdoms.settlement.SettlementGenerator;
import dev.livingkingdoms.faction.Faction;
import dev.livingkingdoms.settlement.domain.Settlement;
import dev.livingkingdoms.settlement.persistence.SettlementSavedData;
import dev.livingkingdoms.structure.SettlementSitePlanner;
import dev.livingkingdoms.structure.SettlementTemplate;
import net.minecraft.commands.CommandSource;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.SignBlockEntity;
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
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

/** All terrain preparation and explicit chunk loading here are test-only. */
@GameTestHolder(KingdomGameTests.MOD_ID)
@PrefixGameTestTemplate(false)
public final class PhysicalSettlementGameTests {
    private static final int FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;

    @GameTest(template = "empty", timeoutTicks = 600)
    public static void generateCommandPlacesAndPersistsSettlement(GameTestHelper helper) throws CommandSyntaxException {
        ServerLevel level = helper.getLevel();
        SettlementSavedData data = SettlementSavedData.get(level.getServer());
        int initialCount = data.settlements().size();
        BlockPos first = helper.absolutePos(new BlockPos(256, 0, 256));
        BlockPos second = first.offset(160, 0, 0);
        preparePlot(level, first);
        preparePlot(level, second);
        RecordingPlayer player = new RecordingPlayer(level);
        RecordingSource feedback = new RecordingSource();
        player.setPos(first.getX() - 32, first.getY(), first.getZ());
        var source = player.createCommandSourceStack().withSource(feedback).withPermission(2);
        var dispatcher = level.getServer().getCommands().getDispatcher();
        boolean denied = false;
        try { dispatcher.execute("kingdom settlement generate", source.withPermission(0)); }
        catch (CommandSyntaxException expected) { denied = true; }
        helper.assertTrue(denied && data.settlements().size() == initialCount, "Non-operators cannot generate settlements");
        helper.assertTrue(dispatcher.execute("kingdom settlement generate", source) == 1, "Generate command must succeed");
        Settlement created = data.at(level.dimension().location().toString(), first.getX(), first.getZ()).orElseThrow();
        helper.assertTrue(created.faction() == Faction.ALLIED && created.level() == 1
                && created.population() == KingdomConfig.INITIAL_POPULATION.get(), "Generated settlement starts allied at level 1");
        helper.assertTrue(created.territory().x() == first.getX() && created.territory().z() == first.getZ(),
                "Generate must use the nearby planned center");
        BlockPos marker = new BlockPos(created.territory().x(), created.territory().y(), created.territory().z());
        helper.assertTrue(level.getBlockState(marker).is(Blocks.LODESTONE), "Central marker must exist");
        helper.assertTrue(level.getBlockState(first.below()).is(Blocks.GRASS_BLOCK), "Flat-site generation must preserve original ground");
        BlockPos origin = marker.offset(-15, -1, -15);
        helper.assertTrue(level.getBlockState(origin.offset(27, 1, 12)).is(Blocks.FURNACE), "Blacksmith furnace must exist");
        SignBlockEntity sign = (SignBlockEntity) level.getBlockEntity(origin.offset(13, 2, 9));
        helper.assertTrue(sign != null && sign.getFrontText().getMessage(1, false).getString().equals("Town Hall"),
                "Town Hall must have a visible, serialized sign");
        BlockPos board = origin.offset(18, 1, 15);
        var boardState = level.getBlockState(board);
        helper.assertTrue(boardState.is(KingdomBlocks.QUEST_BOARD)
                && boardState.getValue(QuestBoardBlock.FACING) == Direction.WEST, "Quest Board block and orientation must exist");
        player.setPos(board.getX() + 1.5, board.getY(), board.getZ() + 0.5);
        BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(board), Direction.WEST, board, false);
        helper.assertTrue(boardState.useWithoutItem(level, player, hit) == InteractionResult.CONSUME,
                "Empty-hand board use is consumed on the server");
        QuestSavedData quests = QuestSavedData.get(level.getServer());
        helper.assertTrue(!player.messages.isEmpty() && hasKey(player.messages.getFirst(), "quest.livingkingdoms.header")
                && quests.progress(player.getUUID(), created.id()).state() == QuestState.AVAILABLE,
                "Generated Quest Board must show the available settlement quest without accepting it");
        long inspections = player.messages.stream().filter(message -> hasKey(message, "quest.livingkingdoms.header")).count();
        helper.assertTrue(boardState.useItemOn(new ItemStack(Items.STICK), level, player, InteractionHand.MAIN_HAND, hit)
                == ItemInteractionResult.CONSUME
                && player.messages.stream().filter(message -> hasKey(message, "quest.livingkingdoms.header")).count() == inspections + 1,
                "Held-item interaction also inspects the quest exactly once");

        UUID mayorId = quests.mayor(created.id()).orElseThrow();
        var mayor = level.getEntity(mayorId);
        helper.assertTrue(mayor != null && NpcIdentity.read(mayor).orElseThrow().settlementId().equals(created.id())
                && NpcIdentity.read(mayor).orElseThrow().role() == NpcRole.MAYOR,
                "Generated settlement must have a Mayor associated by settlement UUID");
        helper.assertTrue(NpcService.ensureMayor(level, created).orElseThrow() == mayor
                && quests.mayor(created.id()).orElseThrow().equals(mayorId), "Repeated Mayor association must not spawn another entity");
        var villagerMayor = (net.minecraft.world.entity.npc.Villager) mayor;
        helper.assertTrue(villagerMayor.isNoAi() && villagerMayor.isPersistenceRequired() && villagerMayor.isInvulnerable()
                && villagerMayor.getOffers().isEmpty(), "The initial Mayor must remain a persistent stationary dialogue NPC without trades");
        CompoundTag mayorTag = mayor.saveWithoutId(new CompoundTag());
        var reloadedMayor = EntityType.VILLAGER.create(level);
        reloadedMayor.load(mayorTag);
        helper.assertTrue(reloadedMayor.getUUID().equals(mayorId)
                && NpcIdentity.read(reloadedMayor).equals(NpcIdentity.read(mayor)),
                "Vanilla entity NBT must preserve the Mayor UUID, role and settlement association");

        player.messages.clear();
        var welcome = new PlayerInteractEvent.EntityInteract(player, InteractionHand.MAIN_HAND, mayor);
        NpcInteractions.onInteract(welcome);
        helper.assertTrue(welcome.isCanceled() && player.messages.size() == 1
                && hasKey(player.messages.getFirst(), "npc.livingkingdoms.mayor.before")
                && ((TranslatableContents) player.messages.getFirst().getContents()).getArgs()[0].equals(created.name()),
                "Mayor dialogue must welcome the player before quest completion");
        player.messages.clear();
        var offhand = new PlayerInteractEvent.EntityInteract(player, InteractionHand.OFF_HAND, mayor);
        NpcInteractions.onInteract(offhand);
        var specific = new PlayerInteractEvent.EntityInteractSpecific(player, InteractionHand.MAIN_HAND, mayor, Vec3.ZERO);
        NpcInteractions.onInteractSpecific(specific);
        helper.assertTrue(offhand.isCanceled() && specific.isCanceled() && player.messages.isEmpty(),
                "Offhand and specific entity callbacks must not duplicate Mayor dialogue or open vanilla trading");

        player.setShiftKeyDown(true);
        boardState.useWithoutItem(level, player, hit);
        player.getInventory().items.set(0, new ItemStack(Items.IRON_INGOT, 16));
        boardState.useItemOn(player.getMainHandItem(), level, player, InteractionHand.MAIN_HAND, hit);
        helper.assertTrue(quests.progress(player.getUUID(), created.id()).state() == QuestState.COMPLETED
                && quests.progress(player.getUUID(), created.id()).reputation() == 10,
                "The generated settlement's own board must complete its quest and grant its reputation");
        player.setShiftKeyDown(false);
        player.messages.clear();
        NpcInteractions.onInteract(new PlayerInteractEvent.EntityInteract(player, InteractionHand.MAIN_HAND, mayor));
        helper.assertTrue(player.messages.size() == 1 && hasKey(player.messages.getFirst(), "npc.livingkingdoms.mayor.after"),
                "Mayor dialogue must recognize this player's completed settlement quest");
        player.messages.clear();
        RecordingPlayer newcomer = new RecordingPlayer(level);
        newcomer.setPos(player.getX(), player.getY(), player.getZ());
        NpcInteractions.onInteract(new PlayerInteractEvent.EntityInteract(newcomer, InteractionHand.MAIN_HAND, mayor));
        helper.assertTrue(newcomer.messages.size() == 1 && hasKey(newcomer.messages.getFirst(), "npc.livingkingdoms.mayor.before"),
                "Another player's Mayor dialogue must remain independent of the completed player");

        player.setPos(marker.getX() + 1, marker.getY(), marker.getZ());
        source = player.createCommandSourceStack().withSource(feedback).withPermission(2);
        helper.assertTrue(dispatcher.execute("kingdom settlement info", source.withPermission(0)) == 1,
                "Info must identify the physical settlement from inside its plaza");
        helper.assertTrue(dispatcher.execute("kingdom settlement generate", source) == 0,
                "An occupied territory must not generate a second settlement on top of itself");
        helper.assertTrue(data.settlements().size() == initialCount + 1, "Failed generation must not add data");
        player.setPos(second.getX() - 32, second.getY(), second.getZ());
        helper.assertTrue(dispatcher.execute("kingdom settlement generate",
                player.createCommandSourceStack().withSource(feedback).withPermission(2)) == 1, "Second distant generation must succeed");
        Settlement other = data.at(level.dimension().location().toString(), second.getX(), second.getZ()).orElseThrow();
        helper.assertTrue(!created.id().equals(other.id()) && data.settlements().size() == initialCount + 2,
                "Manual generation must assign distinct UUIDs");
        helper.assertTrue(quests.mayor(other.id()).isPresent() && !quests.mayor(other.id()).orElseThrow().equals(mayorId),
                "Distinct settlements must receive distinct Mayor UUIDs");
        helper.assertTrue(new HashSet<>(data.settlements().stream().map(Settlement::id).toList()).size() == data.settlements().size(),
                "Every settlement UUID must remain unique");
        level.getDataStorage().save();
        IOUtilities.waitUntilIOWorkerComplete();
        var directory = level.getServer().getWorldPath(LevelResource.ROOT).resolve("data");
        var fresh = new DimensionDataStorage(directory.toFile(), null, level.getServer().registryAccess());
        var reopened = fresh.get(new SavedData.Factory<>(SettlementSavedData::new, SettlementSavedData::load), SettlementSavedData.DATA_NAME);
        IOUtilities.waitUntilIOWorkerComplete();
        helper.assertTrue(reopened != null && reopened.settlements().contains(created) && reopened.settlements().contains(other),
                "Both physical settlements must reload with the same IDs, centers and territory");
        var reopenedQuests = fresh.get(new SavedData.Factory<>(QuestSavedData::new, QuestSavedData::load), QuestSavedData.DATA_NAME);
        IOUtilities.waitUntilIOWorkerComplete();
        helper.assertTrue(reopenedQuests != null && reopenedQuests.mayor(created.id()).orElseThrow().equals(mayorId)
                && reopenedQuests.mayor(other.id()).isPresent()
                && reopenedQuests.progress(player.getUUID(), created.id()).equals(quests.progress(player.getUUID(), created.id())),
                "Reload must preserve each generated Mayor association and the completed player's settlement quest and reputation");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 600)
    public static void unsafeSitesDoNotChangeWorldOrSavedData(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        SettlementSavedData data = SettlementSavedData.get(level.getServer());
        int count = data.settlements().size();
        BlockPos center = helper.absolutePos(new BlockPos(768, 0, 768));
        preparePlot(level, center);
        SettlementGenerator generator = new SettlementGenerator();
        BlockPos protectedPos = center.offset(4, 1, 4);
        level.setBlock(protectedPos, Blocks.CHEST.defaultBlockState(), FLAGS);
        ChestBlockEntity chest = (ChestBlockEntity) level.getBlockEntity(protectedPos);
        chest.setItem(0, new ItemStack(Items.DIAMOND, 7));
        var rejected = generator.generateAt(level, center);
        helper.assertTrue(rejected.failure() == SettlementGenerator.Failure.NO_SAFE_SITE,
                "Existing construction and block entities must prevent placement");
        helper.assertTrue(level.getBlockEntity(protectedPos) == chest && chest.getItem(0).getCount() == 7,
                "Protected inventories must be untouched");
        helper.assertTrue(data.settlements().size() == count && level.getBlockState(center).isAir(), "Refusal must not add data or blocks");
        level.setBlock(protectedPos, Blocks.AIR.defaultBlockState(), FLAGS);
        level.setBlock(center, Blocks.WATER.defaultBlockState(), FLAGS);
        helper.assertTrue(!generator.generateAt(level, center).successful(), "Liquids must prevent placement");
        helper.assertTrue(level.getBlockState(center).is(Blocks.WATER), "Water must not be drained on failure");
        level.setBlock(center, Blocks.AIR.defaultBlockState(), FLAGS);
        var pig = EntityType.PIG.create(level);
        pig.setPos(center.getX() + 0.5, center.getY(), center.getZ() + 0.5);
        level.addFreshEntity(pig);
        helper.assertTrue(!generator.generateAt(level, center).successful(), "An occupied site must not bury entities");
        pig.discard();
        level.setBlock(center.above(5), Blocks.STONE.defaultBlockState(), FLAGS);
        helper.assertTrue(!generator.generateAt(level, center).successful(), "Excessive height variation must prevent placement");
        level.setBlock(center.above(5), Blocks.AIR.defaultBlockState(), FLAGS);
        BlockPos outsideBorder = new BlockPos(30_000_000, center.getY(), 30_000_000);
        helper.assertTrue(!generator.generateAt(level, outsideBorder).successful(), "World border must prevent placement");
        helper.assertTrue(data.settlements().size() == count, "Every rejected site must leave saved data unchanged");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 600)
    public static void gentleSlopeUsesSupportsAndPreservesTerrain(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos center = helper.absolutePos(new BlockPos(1024, 0, 1024));
        preparePlot(level, center);
        // Raise half the plot by one block, leaving the other half at its original height.
        for (int x = 0; x <= 15; x++) {
            for (int z = -15; z <= 15; z++) level.setBlock(center.offset(x, 0, z), Blocks.GRASS_BLOCK.defaultBlockState(), FLAGS);
        }
        var result = new SettlementGenerator().generateAt(level, center);
        helper.assertTrue(result.successful(), "A gentle dry slope must be supported without excavation");
        helper.assertTrue(level.getBlockState(center).is(Blocks.GRASS_BLOCK), "Higher original ground must be preserved");
        helper.assertTrue(level.getBlockState(center.offset(-1, 0, 0)).is(Blocks.COBBLESTONE), "Lower columns need a foundation support");
        helper.assertTrue(level.getBlockState(center.offset(-1, -1, 0)).is(Blocks.GRASS_BLOCK), "Lower original ground must be preserved");
        helper.assertTrue(result.settlement().territory().y() == center.getY() + 2, "Marker must be above the raised foundation");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 400)
    public static void plannerNeverLoadsMissingChunks(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos unloaded = helper.absolutePos(new BlockPos(4096, 0, 4096));
        int chunkX = unloaded.getX() >> 4;
        int chunkZ = unloaded.getZ() >> 4;
        helper.assertTrue(!level.getChunkSource().hasChunk(chunkX, chunkZ), "Fixture must be an unloaded chunk");
        var plan = new SettlementSitePlanner().at(level, SettlementSavedData.get(level.getServer()),
                SettlementTemplate.load(level), unloaded, 2, 48);
        helper.assertTrue(plan.isEmpty() && !level.getChunkSource().hasChunk(chunkX, chunkZ), "Planning must not load or generate missing chunks");
        helper.succeed();
    }

    private static void preparePlot(ServerLevel level, BlockPos center) {
        for (int x = (center.getX() - 16) >> 4; x <= (center.getX() + 16) >> 4; x++) {
            for (int z = (center.getZ() - 16) >> 4; z <= (center.getZ() + 16) >> 4; z++) level.getChunk(x, z);
        }
        for (int x = -15; x <= 15; x++) {
            for (int z = -15; z <= 15; z++) {
                level.setBlock(center.offset(x, -1, z), Blocks.GRASS_BLOCK.defaultBlockState(), FLAGS);
                for (int y = 0; y < 12; y++) level.setBlock(center.offset(x, y, z), Blocks.AIR.defaultBlockState(), FLAGS);
            }
        }
    }

    private static final class RecordingPlayer extends FakePlayer {
        final List<Component> messages = new ArrayList<>();
        RecordingPlayer(ServerLevel level) { super(level, new GameProfile(UUID.randomUUID(), "PhysicalTest")); }
        @Override public void displayClientMessage(Component message, boolean overlay) { messages.add(message); }
        @Override public void sendSystemMessage(Component message) { messages.add(message); }
    }

    private static boolean hasKey(Component message, String key) {
        return message.getContents() instanceof TranslatableContents translated && translated.getKey().equals(key);
    }

    private static final class RecordingSource implements CommandSource {
        final List<Component> messages = new ArrayList<>();
        @Override public void sendSystemMessage(Component message) { messages.add(message); }
        @Override public boolean acceptsSuccess() { return true; }
        @Override public boolean acceptsFailure() { return true; }
        @Override public boolean shouldInformAdmins() { return false; }
    }
}
