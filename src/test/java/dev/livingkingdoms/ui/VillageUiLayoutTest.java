package dev.livingkingdoms.ui;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import dev.livingkingdoms.client.VillageTextFlow;
import net.minecraft.client.StringSplitter;
import net.minecraft.network.chat.Component;
import static org.junit.jupiter.api.Assertions.*;

class VillageUiLayoutTest {
    @Test void boardAndDialogueFitMinecraftScaledResolutions() {
        // 854x480, 1280x720 and 1920x1080 windows at legal GUI scales.
        for (int[] resolution : new int[][]{{320,240},{427,240},{640,360},{854,480},{960,540},{1920,1080}}) {
            for (boolean board : new boolean[]{true,false}) {
                var layout=VillageUiLayout.fit(resolution[0],resolution[1],board);
                assertTrue(layout.left()>=6 && layout.top()>=6);
                assertTrue(layout.left()+layout.width()<=resolution[0]-6);
                assertTrue(layout.top()+layout.height()<=resolution[1]-6);
                assertTrue(layout.detailWidth()>=120);
                assertTrue(layout.viewport()>100);
                assertTrue(layout.contentTop()+layout.rows()*34<=layout.contentBottom());
                assertTrue(layout.footerTop()+20<layout.top()+layout.height());
                assertTrue(layout.dialogueActionsTop()+44<layout.top()+layout.height());
            }
        }
    }

    @Test void threeMayorActionRowsPreserveReadableSpaceAndStayInsideThePanel() {
        for(int[] resolution:new int[][]{{320,240},{427,240},{640,360},{854,480},{960,540},{1920,1080}}) {
            var layout=VillageUiLayout.fit(resolution[0],resolution[1],false);
            int firstRow=layout.dialogueActionsTop(3);
            int lastRow=firstRow+48;
            assertTrue(firstRow>=layout.contentTop()+64,"Mayor actions must leave a usable scrolling reader");
            assertTrue(lastRow+20<=layout.top()+layout.height()-8,"All six controls must fit above the panel inset");
            int buttonWidth=Math.min(128,(layout.detailWidth()-4)/2);
            assertTrue(buttonWidth>=80,"Labels must remain usable at supported GUI scales");
            assertTrue(layout.detailX()+buttonWidth*2+4<=layout.left()+layout.width()-8);
            assertEquals(layout.dialogueActionsTop(),lastRow-24,"The extra row moves upward without changing the bottom inset");
        }
    }

    @Test void acceptingAndClaimingKeepSelectionInItsDestinationTab() {
        UUID id=UUID.randomUUID();
        CompoundTag data=snapshot(id,"DYNAMIC","ACTIVE");
        assertEquals(2,QuestBoardState.afterAction(data,id,UiPayloads.Action.ACCEPT,1));
        assertEquals(id,QuestBoardState.visible(data,2).getFirst().getUUID("id"));
        assertTrue(QuestBoardState.visible(data,1).isEmpty());
        data=snapshot(id,"DYNAMIC","COMPLETED");
        assertEquals(1,QuestBoardState.afterAction(data,id,UiPayloads.Action.CLAIM,2));
        assertEquals(id,QuestBoardState.visible(data,1).getFirst().getUUID("id"));
        data=snapshot(id,"MAIN","COMPLETED");
        assertEquals(0,QuestBoardState.afterAction(data,id,UiPayloads.Action.CLAIM,2));
        data.putString("notice","ui.livingkingdoms.action_rejected");
        assertEquals(2,QuestBoardState.afterAction(data,id,UiPayloads.Action.CLAIM,2));
    }

    @Test void refreshDoesNotChangeTheSelectedTab() {
        UUID id=UUID.randomUUID();
        CompoundTag data=snapshot(id,"MAIN","ACTIVE");
        assertEquals(0,QuestBoardState.afterAction(data,id,UiPayloads.Action.REFRESH,0));
        assertEquals(2,QuestBoardState.afterAction(data,UUID.randomUUID(),UiPayloads.Action.CLAIM,2));
    }

    @Test void longTranslatedTextWrapsWithinTheReaderAndRemainsScrollable() {
        // Deterministic glyph metrics exercise the actual native splitter used by the screen.
        var splitter=new StringSplitter((codepoint,style)->style.isBold()?7.0F:6.0F);
        String translation="Entrega de provisiones para los habitantes de Peñaflor: herramientas, madera y piedra. ".repeat(12);
        var text=Component.literal(translation);
        for(int width:new int[]{320,427,640,1920}) {
            var layout=VillageUiLayout.fit(width,240,true);
            int readerWidth=layout.detailWidth()-24;
            var lines=VillageTextFlow.wrap(splitter,text,readerWidth);
            assertTrue(lines.size()>1);
            for(var line:lines)assertTrue(splitter.stringWidth(line)<=readerWidth);
            String recovered=lines.stream().map(net.minecraft.network.chat.FormattedText::getString).reduce("",String::concat);
            assertEquals(translation.replace(" ",""),recovered.replace(" ",""));
            assertTrue(VillageTextFlow.height(lines.size())>layout.viewport());
        }
    }

    private static CompoundTag snapshot(UUID id,String category,String state) {
        CompoundTag quest=new CompoundTag(); quest.putUUID("id",id);
        quest.putString("category",category); quest.putString("state",state);
        ListTag quests=new ListTag();quests.add(quest);
        CompoundTag data=new CompoundTag();data.put("quests",quests);return data;
    }
}
