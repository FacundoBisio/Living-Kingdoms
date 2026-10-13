package dev.livingkingdoms.ui;

import dev.livingkingdoms.client.VillageTextCache;
import net.minecraft.client.StringSplitter;
import net.minecraft.network.chat.Component;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.chat.contents.TranslatableContents;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class UiPolishTest {
    @org.junit.jupiter.api.BeforeAll static void bootstrap() {
        net.minecraft.SharedConstants.tryDetectVersion();net.minecraft.server.Bootstrap.bootStrap();
    }
    @Test void dialoguePagesPreserveEveryLineAndFinalShortPage() {
        var lines=java.util.stream.IntStream.range(0,13).boxed().toList();
        var pages=DialoguePages.of(lines,5);
        assertEquals(List.of(5,5,3),pages.stream().map(List::size).toList());
        assertEquals(lines,pages.stream().flatMap(List::stream).toList());
        assertThrows(UnsupportedOperationException.class,()->pages.getFirst().clear());
        assertEquals(1,DialoguePages.of(List.of(),5).size());
        assertThrows(IllegalArgumentException.class,()->DialoguePages.of(lines,0));
    }
    @Test void pagesUseActualNativeWrappingWithoutDiscardingWords() {
        var splitter=new StringSplitter((codepoint,style)->6F);
        String text="La comunidad de Peñaflor necesita herramientas, espacio y protección. ".repeat(8);
        for(int width:new int[]{244,276,388}) {
            var lines=dev.livingkingdoms.client.VillageTextFlow.wrap(splitter,Component.literal(text),width);
            var pages=DialoguePages.of(lines,5);
            for(var page:pages) { assertTrue(page.size()<=5); for(var line:page) assertTrue(splitter.stringWidth(line)<=width); }
            String recovered=pages.stream().flatMap(List::stream).map(net.minecraft.network.chat.FormattedText::getString).reduce("",String::concat);
            assertEquals(text.replaceAll("\\s+",""),recovered.replaceAll("\\s+",""));
        }
    }
    @Test void textCacheAvoidsRemeasuringEquivalentTextAndInvalidatesWidthAndStyle() {
        var measurements=new AtomicInteger();
        var splitter=new StringSplitter((codepoint,style)->{measurements.incrementAndGet();return style.isBold()?7F:6F;});
        var cache=new VillageTextCache(); var text=Component.literal("Iron supplies for a growing kingdom");
        var first=cache.wrap(splitter,text,100); int count=measurements.get();
        assertSame(first,cache.wrap(splitter,text.copy(),100)); assertEquals(count,measurements.get());
        cache.wrap(splitter,text,80); assertTrue(measurements.get()>count);
        count=measurements.get();cache.wrap(splitter,text.copy().withStyle(net.minecraft.ChatFormatting.BOLD),80);assertTrue(measurements.get()>count);
        cache.clear();assertNotSame(first,cache.wrap(splitter,text,100));
    }
    @Test void everyHudTextToneKeepsContrastOverBrightTerrainAtMinimumOpacity() {
        double alpha=KingdomHudPalette.MIN_BACKGROUND_ALPHA/255.0;
        double background=luminance(blendChannel(KingdomHudPalette.BACKGROUND,16,alpha),
                blendChannel(KingdomHudPalette.BACKGROUND,8,alpha),blendChannel(KingdomHudPalette.BACKGROUND,0,alpha));
        for(var tone:KingdomHudPresentation.Tone.values()) {
            int color=KingdomHudPalette.text(tone);
            assertEquals(255,color>>>24);
            double contrast=(luminance((color>>>16)&255,(color>>>8)&255,color&255)+.05)/(background+.05);
            assertTrue(contrast>=4.5,()->tone+" contrast over white terrain: "+contrast);
        }
    }
    @Test void localizationsShareKeysArgumentsAndHaveReadableUtf8() throws java.io.IOException {
        var english=localization("en_us");var spanish=localization("es_ar");
        assertEquals(english.keySet(),spanish.keySet());
        var parameters=java.util.regex.Pattern.compile("%(?:[0-9]+\\$)?[sd]");
        for(var key:english.keySet()) {
            String en=english.get(key).getAsString(),es=spanish.get(key).getAsString();
            assertEquals(parameters.matcher(en).results().map(java.util.regex.MatchResult::group).toList(),
                    parameters.matcher(es).results().map(java.util.regex.MatchResult::group).toList(),key);
            for(var value:List.of(en,es)) assertFalse(value.contains("\u00c3") || value.contains("\u00c2") || value.contains("\u00e2\u20ac"),key);
        }
        assertEquals("Poblaci\u00f3n: %s",spanish.get("hud.livingkingdoms.population_value").getAsString());
    }
    private static com.google.gson.JsonObject localization(String locale) throws java.io.IOException {
        try(var stream=java.util.Objects.requireNonNull(UiPolishTest.class.getResourceAsStream("/assets/livingkingdoms/lang/"+locale+".json"));
            var reader=new java.io.InputStreamReader(stream,java.nio.charset.StandardCharsets.UTF_8)) {
            return com.google.gson.JsonParser.parseReader(reader).getAsJsonObject();
        }
    }
    private static double blendChannel(int color,int shift,double alpha) { return ((color>>>shift)&255)*alpha+255*(1-alpha); }
    private static double luminance(double red,double green,double blue) {
        return .2126*linear(red/255)+.7152*linear(green/255)+.0722*linear(blue/255);
    }
    private static double linear(double value) { return value<=.04045?value/12.92:Math.pow((value+.055)/1.055,2.4); }
    @Test void bothCardsFitCommonGuiScalesAndOffsetsAvoidBossBars() {
        for(var size:new int[][]{{320,240},{427,240},{640,360},{960,540},{1920,1080}}) {
            var cards=KingdomHudLayout.cards(size[0],size[1],44,6,6,200,4,5);
            assertNotNull(cards.kingdom());assertNotNull(cards.quest());
            assertTrue(cards.kingdom().y()+cards.kingdom().height()<cards.quest().y());
            assertTrue(cards.quest().y()+cards.quest().height()<=size[1]-55);
            if(cards.kingdom().x()+cards.kingdom().width()>(size[0]-182)/2) assertTrue(cards.kingdom().y()>44);
        }
        var moved=KingdomHudLayout.cards(960,540,50,10_000,10_000,200,0,4);
        assertNotNull(moved.quest());assertTrue(moved.quest().x()+moved.quest().width()<=960);
        assertTrue(moved.quest().y()+moved.quest().height()<=540-55);
    }
    @Test void overflowingCardsRetainTheirWholeContentInReadablePages() {
        var cards=KingdomHudLayout.cards(320,240,70,6,6,200,12,18);
        for(var card:List.of(cards.kingdom(),cards.quest())) {
            assertNotNull(card);assertTrue(card.lineCapacity()>=2);
            assertTrue(card.y()+card.height()<=185);
            var rows=java.util.stream.IntStream.range(0,18).boxed().toList();
            assertEquals(rows,DialoguePages.of(rows,card.lineCapacity()-1).stream().flatMap(List::stream).toList());
        }
        assertTrue(VillageUiLayout.readable(320,240));assertFalse(VillageUiLayout.readable(200,120));
    }
    @Test void historyFilterKeepsCurrentWorkAndOnlyNextLockedStepWithoutMutatingSnapshots() {
        var data=new CompoundTag();var quests=new ListTag();
        quests.add(quest("first_meeting","COMPLETED","MAIN"));quests.add(quest("main_residents","AVAILABLE","MAIN"));
        quests.add(quest("main_iron","LOCKED","MAIN"));quests.add(quest("main_development","LOCKED","MAIN"));
        quests.add(quest("iron_request","EXPIRED","DYNAMIC"));quests.add(quest("food_request","AVAILABLE","DYNAMIC"));
        data.put("quests",quests);var original=data.copy();
        assertEquals(List.of("main_residents","main_iron"),QuestBoardState.visible(data,0,false).stream().map(q->q.getString("template")).toList());
        assertEquals(4,QuestBoardState.visible(data,0,true).size());assertEquals(1,QuestBoardState.visible(data,1,false).size());
        assertEquals(original,data);
    }
    @Test void hudModulesAreIndependentAndReadyStatusUsesAuthoritativeProgress() {
        var data=new CompoundTag();var local=new CompoundTag();local.putString("name","Peñaflor");local.putInt("reputation",7);
        local.putInt("population",3);local.putString("safety","threatened");data.put("settlement",local);
        var q=quest("main_residents","ACTIVE","MAIN");q.putString("settlement","Peñaflor");q.putBoolean("ready",true);data.put("quest",q);
        var both=KingdomHudPresentation.describe(data,false,true,true);
        assertEquals("Peñaflor",both.kingdom().getFirst().text().getString());
        assertTrue(both.kingdom().stream().anyMatch(row->row.tone()==KingdomHudPresentation.Tone.WARNING));
        assertTrue(both.quest().stream().anyMatch(row->row.icon()==KingdomHudPresentation.Icon.CHECK));
        assertTrue(KingdomHudPresentation.describe(data,false,false,true).kingdom().isEmpty());
        data.remove("settlement");assertFalse(KingdomHudPresentation.describe(data,false,true,true).quest().isEmpty());
        assertTrue(KingdomHudPresentation.describe(data,false,true,false).quest().isEmpty());
    }
    @Test void partyAndMultipleResourceProgressAreCompleteAndBounded() {
        var data=new CompoundTag();var q=quest("main_patrol","ACTIVE","MAIN");q.putInt("party_total",5);q.putInt("party_remaining",2);data.put("quest",q);
        var rows=KingdomHudPresentation.describe(data,false,false,true).quest();
        var progress=(TranslatableContents)rows.getLast().text().getContents();
        assertEquals("hud.livingkingdoms.defeated",progress.getKey());assertArrayEquals(new Object[]{3,5},progress.getArgs());
        q=quest("building_request","ACTIVE","DYNAMIC");var items=new ListTag();
        for(var item:List.of("logs","stone")) {var term=new CompoundTag();term.putString("item",item);term.putInt("required",32);term.putInt("count",99);items.add(term);}
        q.put("requirements",items);data.put("quest",q);
        rows=KingdomHudPresentation.describe(data,false,false,true).quest();
        assertEquals(4,rows.size());
        for(var row:rows.subList(2,4)) { var contents=(TranslatableContents)row.text().getContents();assertEquals(32,contents.getArgs()[0]);assertEquals(32,contents.getArgs()[1]); }
    }
    private static CompoundTag quest(String template,String state,String category) {
        var q=new CompoundTag();q.putUUID("id",UUID.randomUUID());q.putString("template",template);q.putString("state",state);q.putString("category",category);return q;
    }
}
