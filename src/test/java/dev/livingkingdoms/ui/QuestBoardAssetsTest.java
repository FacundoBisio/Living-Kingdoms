package dev.livingkingdoms.ui;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import javax.imageio.ImageIO;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import static org.junit.jupiter.api.Assertions.*;

/** Checks packaged resources, not screenshots or subjective in-game appearance. */
class QuestBoardAssetsTest {
    private static final String ROOT="/assets/livingkingdoms/";

    @Test void allFacingVariantsAndLegacyItemResolveToThePackagedModel() throws IOException {
        var variants=json("blockstates/quest_board.json").getAsJsonObject("variants");
        assertEquals(4,variants.size());
        String[] directions={"north","east","south","west"};
        for(int i=0;i<directions.length;i++) {
            var value=variants.getAsJsonObject("facing="+directions[i]);
            assertEquals("livingkingdoms:block/quest_board",value.get("model").getAsString());
            assertEquals(i*90,value.has("y")?value.get("y").getAsInt():0);
        }
        assertEquals("livingkingdoms:block/quest_board",json("models/item/quest_board.json").get("parent").getAsString());
        assertEquals("minecraft:block/block",json("models/block/quest_board.json").get("parent").getAsString());
    }

    @Test void modelReferencesOpaqueNativePixelArtRatherThanPlaceholderWool() throws IOException {
        var textures=json("models/block/quest_board.json").getAsJsonObject("textures");
        var paths=new HashSet<String>();
        for(var entry:textures.entrySet()) {
            String id=entry.getValue().getAsString();
            assertTrue(id.startsWith("livingkingdoms:block/quest_board_"));
            if(!paths.add(id)) continue;
            try(var stream=resource("textures/"+id.substring(id.indexOf(':')+1)+".png")) {
                var image=ImageIO.read(stream);assertNotNull(image);
                assertEquals(16,image.getWidth());assertEquals(16,image.getHeight());
                var colors=new HashSet<Integer>();
                for(int y=0;y<16;y++)for(int x=0;x<16;x++) {
                    int pixel=image.getRGB(x,y);assertEquals(255,pixel>>>24);colors.add(pixel);
                }
                assertTrue(colors.size()>=4,"Not a flat placeholder texture: "+id);
            }
        }
        assertEquals(4,paths.size(),"Wood plus three individual inked notices");
    }

    @Test void freestandingGeometryHasDepthAndNoElementsOutsideTheBlock() throws IOException {
        var model=json("models/block/quest_board.json");var used=new HashSet<String>();
        for(var value:model.getAsJsonArray("elements")) {
            var element=value.getAsJsonObject();var from=element.getAsJsonArray("from");var to=element.getAsJsonArray("to");
            for(int axis=0;axis<3;axis++) {
                assertTrue(from.get(axis).getAsDouble()>=0 && to.get(axis).getAsDouble()<=16);
                assertTrue(from.get(axis).getAsDouble()<to.get(axis).getAsDouble(),"Each element has actual 3D volume");
            }
            for(var face:element.getAsJsonObject("faces").entrySet()) {
                String texture=face.getValue().getAsJsonObject().get("texture").getAsString();
                assertTrue(texture.startsWith("#"));
                assertTrue(model.getAsJsonObject("textures").has(texture.substring(1)),"Unresolved face texture");
                used.add(texture);
            }
        }
        assertTrue(used.containsAll(java.util.Set.of("#wood","#notice0","#notice1","#notice2")));
    }

    private static JsonObject json(String path) throws IOException {
        try(var stream=resource(path);var reader=new InputStreamReader(stream,StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        }
    }
    private static InputStream resource(String path) { return java.util.Objects.requireNonNull(QuestBoardAssetsTest.class.getResourceAsStream(ROOT+path),"Missing packaged asset: "+path); }
}
