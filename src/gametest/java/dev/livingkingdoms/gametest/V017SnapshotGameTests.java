package dev.livingkingdoms.gametest;

import net.minecraft.core.HolderLookup;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.*;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import java.util.*;

/** Real synthetic-world bytes, never an original player's world directory. */
@GameTestHolder("livingkingdoms_tests")
@PrefixGameTestTemplate(false)
public final class V017SnapshotGameTests {
    @GameTest(template="empty",timeoutTicks=400)
    public static void allNineCopiedWorldStoresRetainNamesIdsExperienceEconomyQuestsAndReceipts(GameTestHelper h) throws Exception {
        Map<String,String> types=Map.of(
                "citizens","citizen.persistence.CitizenSavedData","construction","construction.persistence.ConstructionSavedData",
                "defense","defense.persistence.DefenseSavedData","economy","economy.EconomySavedData",
                "encounters","encounter.persistence.EncounterSavedData","onboarding","onboarding.persistence.KingdomOnboardingSavedData",
                "professions","profession.persistence.ProfessionSavedData","quests","quest.persistence.QuestSavedData",
                "settlements","settlement.persistence.SettlementSavedData");
        var registries=h.getLevel().registryAccess();
        for(var entry:types.entrySet()) {
            byte[] bytes;try(var stream=V017SnapshotGameTests.class.getResourceAsStream("/v017-world/livingkingdoms_"+entry.getKey()+".dat")) {h.assertTrue(stream!=null,"Copied source fixture required: "+entry.getKey());bytes=stream.readAllBytes();}
            var source=NbtIo.readCompressed(new java.io.ByteArrayInputStream(bytes),NbtAccounter.unlimitedHeap()).getCompound("data");
            var loader=Class.forName("dev.livingkingdoms."+entry.getValue()).getMethod("load",CompoundTag.class,HolderLookup.Provider.class);
            var loaded=(SavedData)loader.invoke(null,source,registries);var saved=loaded.save(new CompoundTag(),registries);
            h.assertTrue(canonical(source).equals(canonical(saved)),entry.getKey()+": persisted facts must be preserved");
            var reopened=(SavedData)loader.invoke(null,saved,registries);
            h.assertTrue(canonical(saved).equals(canonical(reopened.save(new CompoundTag(),registries))),entry.getKey()+": second reload must retain facts");
        }
        h.succeed();
    }
    /** Ordering of record/path collections may differ; each complete primitive and compound fact is retained. */
    private static String canonical(Tag tag) {
        if(tag instanceof CompoundTag compound) {var keys=new TreeSet<>(compound.getAllKeys());var result=new StringBuilder("{");for(String key:keys) result.append(key).append(':').append(canonical(compound.get(key))).append(';');return result.append('}').toString();}
        if(tag instanceof ListTag list) {var elements=new ArrayList<String>();for(var element:list) elements.add(canonical(element));Collections.sort(elements);return '['+String.join(";",elements)+']';}
        return tag.getId()+":"+tag;
    }
}
