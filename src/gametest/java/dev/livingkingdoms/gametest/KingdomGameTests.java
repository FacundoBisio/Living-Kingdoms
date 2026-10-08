package dev.livingkingdoms.gametest;

import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.livingkingdoms.config.KingdomConfig;
import dev.livingkingdoms.settlement.domain.Settlement;
import dev.livingkingdoms.settlement.domain.Territory;
import dev.livingkingdoms.settlement.persistence.SettlementSavedData;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.storage.DimensionDataStorage;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.IOUtilities;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Loaded only in the isolated GameTest run; excluded from the distributable mod. */
@Mod(KingdomGameTests.MOD_ID)
@GameTestHolder(KingdomGameTests.MOD_ID)
@PrefixGameTestTemplate(false)
public final class KingdomGameTests {
    public static final String MOD_ID = "livingkingdoms_tests";

    @GameTest(template = "empty", timeoutTicks = 400)
    public static void settlementCommandsAndPersistence(GameTestHelper helper) throws CommandSyntaxException {
        ServerLevel level = helper.getLevel();
        MinecraftServer server = level.getServer();
        helper.assertTrue(server.isSameThread(), "Commands must execute on the server thread");
        helper.assertTrue(level == server.overworld(), "GameTest must use the Overworld");
        SettlementSavedData data = SettlementSavedData.get(server);
        int initialCount = data.settlements().size();
        FakePlayer player = new FakePlayer(level, new GameProfile(UUID.randomUUID(), "KingdomTest"));
        BlockPos position = helper.absolutePos(new BlockPos(1, 2, 1));
        int radius = KingdomConfig.SETTLEMENT_RADIUS.get();
        String dimension = level.dimension().location().toString();
        // GameTest worlds may be retained between runs. Keep the smoke test repeatable.
        while (data.overlaps(new Territory(dimension, position.getX(), position.getY(), position.getZ(), radius))) {
            position = position.offset(2 * radius + 1, 0, 0);
        }
        player.setPos(position.getX(), position.getY(), position.getZ());
        RecordingSource feedback = new RecordingSource();
        CommandSourceStack source = player.createCommandSourceStack().withSource(feedback).withPermission(2);
        var dispatcher = server.getCommands().getDispatcher();

        boolean denied = false;
        try {
            dispatcher.execute("kingdom settlement create", source.withPermission(0));
        } catch (CommandSyntaxException expected) {
            denied = true;
        }
        helper.assertTrue(denied, "Non-operators must not create settlements");
        helper.assertTrue(data.settlements().size() == initialCount, "Denied command must not mutate data");
        helper.assertTrue(dispatcher.execute("kingdom settlement create", source) == 1, "Create command must succeed");
        helper.assertTrue(data.settlements().size() == initialCount + 1, "Create must add exactly one settlement");
        helper.assertTrue(data.isDirty(), "Create must mark world data dirty");
        Settlement created = data.at(level.dimension().location().toString(), position.getX(), position.getZ()).orElseThrow();
        helper.assertTrue(created.territory().x() == position.getX()
                && created.territory().y() == position.getY() && created.territory().z() == position.getZ(),
                "Settlement must be founded at the player's position");

        helper.assertTrue(dispatcher.execute("kingdom settlement create", source) == 0, "Overlapping creation must fail");
        helper.assertTrue(data.settlements().size() == initialCount + 1, "Overlap failure must not add a settlement");
        feedback.messages.clear();
        helper.assertTrue(dispatcher.execute("kingdom settlement info", source.withPermission(0)) == 1,
                "Any player must be able to read settlement info");
        helper.assertTrue(feedback.messages.size() == 1, "Info must send one response");
        helper.assertTrue(feedback.messages.getFirst().getContents() instanceof TranslatableContents,
                "Info must send translatable feedback");
        TranslatableContents info = (TranslatableContents) feedback.messages.getFirst().getContents();
        Object[] fields = info.getArgs();
        helper.assertTrue(info.getKey().equals("commands.livingkingdoms.settlement.info") && fields.length == 4,
                "Normal info contains only name, faction, level and population");
        helper.assertTrue(fields[0].equals(dev.livingkingdoms.ui.VillageNames.display(created))
                && fields[2].equals(created.level()) && fields[3].equals(created.population())
                && java.util.Arrays.stream(fields).noneMatch(field -> field.toString().contains(created.id().toString())),
                "Info fields must match the server settlement");
        helper.assertTrue(fields[1] instanceof Component
                && ((Component) fields[1]).getContents() instanceof TranslatableContents
                && ((TranslatableContents) ((Component) fields[1]).getContents()).getKey()
                .equals("faction.livingkingdoms." + created.faction().id()), "Info must include the settlement faction");

        level.getDataStorage().save();
        IOUtilities.waitUntilIOWorkerComplete();
        Path directory = server.getWorldPath(LevelResource.ROOT).resolve("data");
        DimensionDataStorage freshStorage = new DimensionDataStorage(directory.toFile(), null, server.registryAccess());
        SettlementSavedData reopened = freshStorage.get(new SavedData.Factory<>(SettlementSavedData::new,
                SettlementSavedData::load), SettlementSavedData.DATA_NAME);
        IOUtilities.waitUntilIOWorkerComplete();
        helper.assertTrue(reopened != null && reopened.settlements().equals(data.settlements()),
                "Fresh Minecraft data storage must reload every saved settlement");
        helper.assertTrue(reopened != data && !reopened.isDirty(), "Reload must use a clean, distinct data instance");
        helper.succeed();
    }

    private static final class RecordingSource implements CommandSource {
        private final List<Component> messages = new ArrayList<>();

        @Override public void sendSystemMessage(Component message) { messages.add(message); }
        @Override public boolean acceptsSuccess() { return true; }
        @Override public boolean acceptsFailure() { return true; }
        @Override public boolean shouldInformAdmins() { return false; }
    }
}
