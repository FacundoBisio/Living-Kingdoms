package dev.livingkingdoms.profession.persistence;

import dev.livingkingdoms.citizen.domain.*;
import dev.livingkingdoms.profession.domain.*;
import dev.livingkingdoms.economy.*;
import dev.livingkingdoms.progression.domain.LevelValue;
import dev.livingkingdoms.settlement.domain.*;
import dev.livingkingdoms.structure.*;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Rotation;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class EconomicProfessionTest {
    @Test void controlledRecipesHavePaidInputsAndDoNotMintIronEquipment() {
        for(var type:List.of(ProfessionType.LUMBERJACK,ProfessionType.MINER,ProfessionType.BLACKSMITH)) {
            var recipe=EconomicRecipe.forProfession(type,1);assertTrue(recipe.inputs().get(StockResource.FOOD)>0);
            assertTrue(recipe.outputs().values().stream().allMatch(n->n>0 && n<=8));
        }
        var smith=EconomicRecipe.forProfession(ProfessionType.BLACKSMITH,100);
        assertEquals(8,smith.inputs().get(StockResource.IRON));assertEquals(1,smith.outputs().get(StockResource.EQUIPMENT));
        assertThrows(IllegalArgumentException.class,()->EconomicRecipe.forProfession(ProfessionType.MERCHANT,1));
    }
    @Test void levelsHaveBoundedOutputsAndCooldownCannotReachZero() {
        assertEquals(EconomicRecipe.forProfession(ProfessionType.LUMBERJACK,5),EconomicRecipe.forProfession(ProfessionType.LUMBERJACK,100));
        assertEquals(200,EconomicRecipe.cooldown(200,100,100));
        assertTrue(EconomicRecipe.cooldown(1200,3,2)<EconomicRecipe.cooldown(1200,1,1));
        assertEquals(1200,EconomicRecipe.cooldown(1200,1,1));
    }
    @Test void allEconomicCareersPersistXpCooldownAndCapacityWithoutChangingCitizenIdentity() {
        for(var type:List.of(ProfessionType.LUMBERJACK,ProfessionType.MINER,ProfessionType.BLACKSMITH)) {
            var kind=switch(type) {case LUMBERJACK->BuildingKind.LUMBERJACK_HUT;case MINER->BuildingKind.MINE;default->BuildingKind.BLACKSMITH;};
            var s=Settlement.established(UUID.randomUUID(),new Territory("minecraft:overworld",0,70,0,48),2,SettlementOrigin.FOUNDED,UUID.randomUUID());
            var a=new Citizen(UUID.randomUUID(),UUID.randomUUID(),s.id(),"Mira",new LevelValue(3),CitizenRole.UNASSIGNED,UUID.randomUUID(),CitizenState.ACTIVE,0);
            var b=new Citizen(UUID.randomUUID(),UUID.randomUUID(),s.id(),"Alden",new LevelValue(2),CitizenRole.UNASSIGNED,UUID.randomUUID(),CitizenState.ACTIVE,0);
            var building=new SettlementLayoutMetadata.Building(kind,ResourceLocation.parse("livingkingdoms:allied/plains/"+kind.name().toLowerCase(Locale.ROOT)),new BlockPos(0,70,0),Rotation.NONE,new PlotBounds(0,0,6,6),new BlockPos(3,70,7));
            var d=new ProfessionSavedData();d.synchronize(s,new SettlementLayoutMetadata(ArchitectureStyle.PLAINS,List.of(building),List.of(BlockPos.ZERO),List.of()),2,2,2,List.of(a,b),1,Map.of());
            var station=d.buildings(s.id()).getFirst().id();assertTrue(d.assignEconomic(a,station,10,type));assertFalse(d.assignEconomic(b,station,10,type));
            var before=d.profession(a.id()).orElseThrow();var receipt=before.work(WorkState.WORKING,1210,0,0);assertTrue(d.replace(before,receipt));
            assertTrue(d.economicExperience(receipt,20,5,20));assertFalse(d.economicExperience(receipt,20,5,20));
            var reopened=ProfessionSavedData.load(d.save(new CompoundTag(),null),null);var actual=reopened.profession(a.id()).orElseThrow();
            assertEquals(2,actual.level().value());assertEquals(20,actual.experience());assertEquals(1210,actual.nextWorkAt());assertEquals(3,a.level().value());
            assertTrue(reopened.removeEconomic(a.id()));assertTrue(reopened.assignEconomic(a,station,500,type));assertEquals(20,reopened.profession(a.id()).orElseThrow().experience());
            assertEquals(1210,reopened.profession(a.id()).orElseThrow().nextWorkAt());
        }
    }
    @Test void productionCountersExcludeDepositsAndSpendInputsBeforeCrafting() {
        var a=StockAccount.empty(64,0).exchange(Map.of(),Map.of(StockResource.IRON,8,StockResource.WOOD,2),false).orElseThrow();
        assertTrue(a.produced().isEmpty());
        var smith=EconomicRecipe.forProfession(ProfessionType.BLACKSMITH,1);
        var inputs=new EnumMap<StockResource,Integer>(StockResource.class);inputs.putAll(smith.inputs());inputs.remove(StockResource.FOOD);
        var made=a.exchange(inputs,smith.outputs(),true).orElseThrow();assertEquals(0,made.balance(StockResource.IRON));assertEquals(1,made.balance(StockResource.EQUIPMENT));
        assertEquals(1L,made.produced().get(StockResource.EQUIPMENT));assertTrue(made.exchange(inputs,smith.outputs(),true).isEmpty());
    }
}
