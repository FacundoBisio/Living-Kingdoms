package dev.livingkingdoms.gametest;

import dev.livingkingdoms.block.KingdomBlocks;
import dev.livingkingdoms.construction.preview.*;
import dev.livingkingdoms.construction.domain.ConstructionState;
import dev.livingkingdoms.construction.persistence.ConstructionSavedData;
import dev.livingkingdoms.settlement.domain.*;
import dev.livingkingdoms.settlement.persistence.SettlementSavedData;
import dev.livingkingdoms.structure.*;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketListener;
import net.minecraft.network.protocol.*;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.server.level.*;
import net.minecraft.server.network.*;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.*;
import net.neoforged.neoforge.gametest.*;
import java.util.*;

@GameTestHolder(KingdomGameTests.MOD_ID)
@PrefixGameTestTemplate(false)
public final class ConstructionPreviewGameTests {
    private static final TicketType<ChunkPos> FIXTURE = TicketType.create("livingkingdoms_preview_test",Comparator.comparingLong(ChunkPos::toLong),600);
    private static final class PreviewPlayer extends UiTestPlayer {
        CompoundTag preview = new CompoundTag();
        PreviewPlayer(ServerLevel level) {
            super(level);
            var wire = new Connection(PacketFlow.SERVERBOUND) { @Override public void setListenerForServerboundHandshake(PacketListener listener) {} };
            connection = new ServerGamePacketListenerImpl(level.getServer(),wire,this,CommonListenerCookie.createInitial(getGameProfile(),false)) {
                @Override public void send(Packet<?> packet) {
                    if (packet instanceof ClientboundCustomPayloadPacket p && p.payload() instanceof PreviewPayloads.Snapshot snapshot) preview = snapshot.data().copy();
                }
            };
            getAbilities().mayBuild = true;
        }
    }
    @GameTest(template="empty",timeoutTicks=600)
    public static void previewDoesNotMutateAndConfirmationCreatesOneRealPendingProject(GameTestHelper h) {
        var level = h.getLevel(); var center = fixture(h,610000);
        var player = player(level,center); var data = SettlementSavedData.get(level.getServer());
        var settlement = Settlement.established(UUID.randomUUID(),new Territory(level.dimension().location().toString(),center.getX(),center.getY(),center.getZ(),48),
                5,SettlementOrigin.CONVERTED,player.getUUID());
        data.add(settlement);
        h.assertTrue(!ConstructionPreviewService.begin(player,settlement.id(),BuildingKind.MARKET),"Unjoined players cannot mint a preview capability");
        OnboardingTestSupport.legacyCollaborator(player,settlement);
        var storage = ConstructionSavedData.get(level.getServer());
        h.assertTrue(ConstructionPreviewService.begin(player,settlement.id(),BuildingKind.MARKET),"Survival preview supports MARKET from a converted native plaza");
        var preview = player.preview.copy(); var token = preview.getUUID("session"); var origin = BlockPos.of(preview.getLong("origin"));
        var rotation = Rotation.valueOf(preview.getString("rotation"));
        h.assertTrue(storage.projects(settlement.id()).isEmpty() && data.layout(settlement.id()).isEmpty()
                && level.getBlockState(origin).isAir() && level.getBlockState(origin.above()).isAir(),"Preview creates no blocks, metadata or project");
        var other = player(level,center);
        h.assertTrue(!ConstructionPreviewService.handle(other,new PreviewPayloads.Request(token,PreviewPayloads.Action.CONFIRM,origin,rotation)),"Cross-player capability replay fails");
        h.assertTrue(!ConstructionPreviewService.handle(player,new PreviewPayloads.Request(UUID.randomUUID(),PreviewPayloads.Action.CONFIRM,origin,rotation)),"Forged token cannot consume the actual capability");
        var confirm = new PreviewPayloads.Request(token,PreviewPayloads.Action.CONFIRM,origin,rotation);
        h.assertTrue(ConstructionPreviewService.handle(player,confirm),"Confirmation uses the actual reservation pipeline");
        h.assertTrue(!ConstructionPreviewService.handle(player,confirm),"Repeated confirmation cannot create a duplicate");
        var project = storage.projects(settlement.id()).getFirst();
        h.assertTrue(storage.projects(settlement.id()).size() == 1 && project.project().state() == ConstructionState.WAITING_FOR_RESOURCES
                && project.project().supplied().isEmpty() && project.project().building() == BuildingKind.MARKET
                && level.getBlockState(project.marker()).is(KingdomBlocks.CONSTRUCTION_MARKER)
                && level.getBlockState(origin).isAir(),"Real unfunded project with native bounds and marker; no instant building or spending");
        project.plan().validateGeometry();
        h.succeed();
    }
    @GameTest(template="empty",timeoutTicks=600)
    public static void nativePreviewTransformsEveryOrientationWithinBudget(GameTestHelper h) {
        var catalog = BuildingCatalog.load(h.getLevel(),ArchitectureStyle.PLAINS);
        for (var kind : BuildingKind.values()) for (var rotation : Rotation.values()) {
            var module = catalog.get(kind); var origin = new BlockPos(100,64,100); var size = module.rotatedSize(rotation);
            var building = new SettlementLayout.Building(module,origin,rotation,new PlotBounds(100,100,99+size.getX(),99+size.getZ()),
                    module.worldPosition(module.entrance(),origin,rotation),List.of());
            var cells = PreviewGeometry.cells(building); var bounds = module.worldBounds(origin,rotation);
            h.assertTrue(!cells.isEmpty() && cells.size() <= PreviewGeometry.MAX_BLOCKS,"Preview render work is bounded");
            for (var cell : cells) h.assertTrue(bounds.isInside(cell.position()),"All transformed native NBT blocks remain in native bounds");
            for (var cell : cells) h.assertTrue(module.blocks().stream().anyMatch(b -> building.position(b.pos()).equals(cell.position())
                    && b.state().rotate(rotation).equals(cell.state())),"Original block state and orientation preserved");
        }
        h.succeed();
    }
    @GameTest(template="empty",timeoutTicks=600)
    public static void confirmationRechecksChangedWorldAndCancelsWithoutReservation(GameTestHelper h) {
        var level = h.getLevel(); var center = fixture(h,611024); var player = player(level,center);
        var settlement = Settlement.established(UUID.randomUUID(),new Territory(level.dimension().location().toString(),center.getX(),center.getY(),center.getZ(),48),
                5,SettlementOrigin.CONVERTED,player.getUUID());
        SettlementSavedData.get(level.getServer()).add(settlement);
        OnboardingTestSupport.legacyCollaborator(player,settlement);
        h.assertTrue(ConstructionPreviewService.begin(player,settlement.id(),BuildingKind.HOUSE),"Plan available");
        var preview = player.preview; var origin = BlockPos.of(preview.getLong("origin"));
        level.setBlock(origin.above(),Blocks.CHEST.defaultBlockState(),18);
        h.assertTrue(!ConstructionPreviewService.handle(player,new PreviewPayloads.Request(preview.getUUID("session"),PreviewPayloads.Action.CONFIRM,
                origin,Rotation.valueOf(preview.getString("rotation")))),"Changed protected block entity invalidates confirmation");
        h.assertTrue(ConstructionSavedData.get(level.getServer()).projects(settlement.id()).isEmpty()
                && level.getBlockState(origin.above()).is(Blocks.CHEST)
                && SettlementSavedData.get(level.getServer()).layout(settlement.id()).isEmpty(),"Failed confirmation preserves obstruction and no converted metadata receipt");
        level.setBlock(origin.above(),Blocks.AIR.defaultBlockState(),18);
        h.assertTrue(ConstructionPreviewService.begin(player,settlement.id(),BuildingKind.HOUSE),"New survey after removing obstruction");
        preview=player.preview;player.getAbilities().mayBuild=false;
        h.assertTrue(!ConstructionPreviewService.handle(player,new PreviewPayloads.Request(preview.getUUID("session"),PreviewPayloads.Action.CONFIRM,
                BlockPos.of(preview.getLong("origin")),Rotation.valueOf(preview.getString("rotation"))))
                && ConstructionSavedData.get(level.getServer()).projects(settlement.id()).isEmpty()
                && SettlementSavedData.get(level.getServer()).layout(settlement.id()).isEmpty(),"Revoked build permission invalidates capability before reservation");
        h.succeed();
    }
    private static PreviewPlayer player(ServerLevel level,BlockPos center) {
        var player = new PreviewPlayer(level); player.setPos(center.getX()+.5,center.getY(),center.getZ()+.5); return player;
    }
    private static BlockPos fixture(GameTestHelper h,int coordinate) {
        var level = h.getLevel(); var center = h.absolutePos(new BlockPos(coordinate,1,coordinate)); int radius = 52;
        for (int x=(center.getX()-radius)>>4;x<=(center.getX()+radius)>>4;x++) for (int z=(center.getZ()-radius)>>4;z<=(center.getZ()+radius)>>4;z++) level.getChunk(x,z);
        var chunk = new ChunkPos(center); level.getChunkSource().addRegionTicket(FIXTURE,chunk,5,chunk);
        for (int x=-radius;x<=radius;x++) for (int z=-radius;z<=radius;z++) {
            for (int y=-3;y<0;y++) level.setBlock(center.offset(x,y,z),(y == -1 ? Blocks.GRASS_BLOCK : Blocks.DIRT).defaultBlockState(),18);
            for (int y=0;y<13;y++) level.setBlock(center.offset(x,y,z),Blocks.AIR.defaultBlockState(),18);
        }
        level.setBlock(center,Blocks.LODESTONE.defaultBlockState(),18);
        return center;
    }
}
