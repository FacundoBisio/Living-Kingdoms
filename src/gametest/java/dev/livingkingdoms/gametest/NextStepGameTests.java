package dev.livingkingdoms.gametest;

import dev.livingkingdoms.construction.ConstructionService;
import dev.livingkingdoms.quest.expansion.domain.ResourceKind;
import dev.livingkingdoms.rpg.QuestGuides;
import dev.livingkingdoms.ui.KingdomHudService;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import java.util.Locale;

/** 0.38 "always show the next step": the HUD site row is derived on the server from the real project. */
@GameTestHolder(KingdomGameTests.MOD_ID)
@PrefixGameTestTemplate(false)
public final class NextStepGameTests {
    @GameTest(template="empty",timeoutTicks=600)
    public static void hudSiteRowNamesMissingMaterialsThenDropsThem(GameTestHelper h) {
        var f=ConstructionGameTests.found(h,1140000); var server=h.getLevel().getServer();
        var entry=ConstructionService.current(server,f.settlement().id()).orElseThrow(); var p=entry.project();
        var site=KingdomHudService.snapshot(f.player()).getCompound("settlement").getCompound("site");
        h.assertTrue(site.getString("next").equals("needs"),"An unfunded project reports the needs step, got "+site);
        var missing=site.getList("missing",10); int expected=0;
        for(var kind:ResourceKind.values()) if(p.missing(kind)>0 && expected<3) {
            var m=missing.getCompound(expected++);
            h.assertTrue(m.getInt("count")==p.missing(kind) && m.getString("item").equals(dev.livingkingdoms.economy.StockResource.of(kind).name().toLowerCase(Locale.ROOT)),"Missing entry derived from the project: "+m);
        }
        h.assertTrue(expected==missing.size() && expected>0,"Every missing kind (max 3) is listed");
        var player=f.player(); player.getInventory().clearContent(); int slot=0;
        for(var kind:ResourceKind.values()) if(p.required().containsKey(kind)) {
            var item=switch(kind) { case LOGS -> Items.OAK_LOG; case STONE -> Items.STONE; case IRON_INGOT -> Items.IRON_INGOT; case WHEAT -> Items.WHEAT; };
            for(int left=p.required().get(kind);left>0;left-=64) player.getInventory().items.set(slot++,new ItemStack(item,Math.min(64,left)));
        }
        h.assertTrue(ConstructionService.deposit(player,f.settlement().id(),p.id()),"Project funded");
        var funded=ConstructionService.current(server,f.settlement().id()).orElseThrow().project();
        var after=KingdomHudService.snapshot(player).getCompound("settlement");
        var next=QuestGuides.next(false,funded.funded(),funded.builderRequired(),funded.builderId()!=null,funded.state()==dev.livingkingdoms.construction.domain.ConstructionState.BUILDING);
        h.assertTrue(funded.funded() && !after.getCompound("site").getString("next").equals("needs")
                && (next==QuestGuides.Next.NONE ? !after.contains("site") : after.getCompound("site").getString("next").equals(next.name().toLowerCase(Locale.ROOT))),
                "Once funded the needs step is gone and the row follows the project: "+after.getCompound("site"));
        h.succeed();
    }

    @GameTest(template="empty",timeoutTicks=600)
    public static void blockedProjectAlertsOncePerReasonAndShowsItInTheHud(GameTestHelper h) {
        var f=ConstructionGameTests.found(h,1141024); var server=h.getLevel().getServer();
        var entry=ConstructionService.current(server,f.settlement().id()).orElseThrow(); var p=entry.project();
        var player=f.player(); player.getInventory().clearContent(); int slot=0;
        for(var kind:ResourceKind.values()) if(p.required().containsKey(kind)) {
            var item=switch(kind) { case LOGS -> Items.OAK_LOG; case STONE -> Items.STONE; case IRON_INGOT -> Items.IRON_INGOT; case WHEAT -> Items.WHEAT; };
            for(int left=p.required().get(kind);left>0;left-=64) player.getInventory().items.set(slot++,new ItemStack(item,Math.min(64,left)));
        }
        ConstructionService.deposit(player,f.settlement().id(),p.id());
        BlockPos obstacle=entry.plan().buildings().getFirst().origin().offset(2,1,2); h.getLevel().setBlock(obstacle,Blocks.CHEST.defaultBlockState(),18);
        for(int i=0;i<3;i++) h.assertTrue(!ConstructionService.resolve(server,p.id(),player,true),"Retry "+i+" stays blocked by the chest");
        String key=ConstructionService.reason(p.id()).orElseThrow().key();
        h.assertTrue(!ConstructionService.firstAlert(p.id(),key),"The stall was already announced once; further retries do not repeat it");
        h.assertTrue(ConstructionService.firstAlert(p.id(),"construction.livingkingdoms.reason.player"),"A different reason is a new announcement");
        var hud=KingdomHudService.snapshot(player); var site=hud.getCompound("settlement").getCompound("site");
        h.assertTrue(site.getString("next").equals("reason") && site.getString("reason").equals(key),"HUD shows the real stall reason: "+site
                +" (alive "+player.isAlive()+", site distance "+Math.sqrt(entry.plan().buildings().getFirst().origin().distSqr(player.blockPosition()))+", hud "+hud+")");
        h.getLevel().setBlock(obstacle,entry.plan().before().get(obstacle),18);
        h.assertTrue(ConstructionService.resolve(server,p.id(),player,true),"Cleared site completes");
        h.assertTrue(ConstructionService.firstAlert(p.id(),key),"Completion clears the memory, so a later stall would announce again");
        h.succeed();
    }
}
