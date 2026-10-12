package dev.livingkingdoms.ui;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class QuestBoardPresentationTest {
    @Test void lockedQuestsHaveDistinctMeaningAndNeverImplyProgressOrAcceptance() {
        var quest=quest("LOCKED");
        quest.putBoolean("accept",false);
        quest.put("requirements",terms(12,12,0,8));
        assertEquals(QuestBoardPresentation.Status.LOCKED,QuestBoardPresentation.status(quest));
        assertEquals("x",QuestBoardPresentation.status(quest).mark());
        assertNull(QuestBoardPresentation.progress(quest));
        assertFalse(quest.getBoolean("accept"));
    }

    @Test void statusMarkersRemainDistinctWithoutColorOrTranslation() {
        var marks=new java.util.HashSet<String>();
        for(var state:QuestBoardPresentation.Status.values()) {
            assertTrue(marks.add(state.mark()));
            assertEquals(state,QuestBoardPresentation.status(quest(state.name())));
        }
        assertEquals(QuestBoardPresentation.Status.FAILED,QuestBoardPresentation.status(quest("EXPIRED")));
    }

    @Test void resourceProgressIncludesEveryTermAndClampsMalformedCounts() {
        var quest=quest("ACTIVE");quest.put("requirements",terms(27,12,-9,8));
        var progress=QuestBoardPresentation.progress(quest);
        assertEquals(new QuestBoardPresentation.Progress(12,20),progress);
        assertEquals(0.6,progress.fraction());
        assertEquals(27,quest.getList("requirements",10).getCompound(0).getInt("count"),"Rendering never mutates server snapshot");
    }

    @Test void defenseProgressReflectsRemainingMembersButEmptyObjectivesHaveNoInventedProgress() {
        var quest=quest("ACTIVE");assertNull(QuestBoardPresentation.progress(quest));
        quest.putInt("defense_total",8);quest.putInt("defense_remaining",3);
        assertEquals(new QuestBoardPresentation.Progress(5,8),QuestBoardPresentation.progress(quest));
        quest.putInt("defense_remaining",99);
        assertEquals(new QuestBoardPresentation.Progress(0,8),QuestBoardPresentation.progress(quest));
    }

    private static CompoundTag quest(String state) { var tag=new CompoundTag();tag.putString("state",state);return tag; }
    private static ListTag terms(int first,int requiredFirst,int second,int requiredSecond) {
        var result=new ListTag();
        for(int[] pair:new int[][]{{first,requiredFirst},{second,requiredSecond}}) {
            var term=new CompoundTag();term.putInt("count",pair[0]);term.putInt("required",pair[1]);result.add(term);
        }
        return result;
    }
}
