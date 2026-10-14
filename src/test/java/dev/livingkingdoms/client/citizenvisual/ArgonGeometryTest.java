package dev.livingkingdoms.client.citizenvisual;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.core.Direction;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** CPU mesh checks only: no OpenGL context or assertion of in-game visual quality. */
class ArgonGeometryTest {
    private static String source() throws IOException {
        try (var stream = ArgonGeometryTest.class.getResourceAsStream("/assets/livingkingdoms/geo/argon4/citizen.geo.json")) {
            assertNotNull(stream);
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test void retainsPlayerModelAndArmorPivotContractWithoutAttachingHammer() throws IOException {
        ModelPart root = ArgonCitizenGeometry.createRoot(source());
        for (String child : List.of("head", "hat", "body", "right_arm", "left_arm", "right_leg", "left_leg",
                "right_sleeve", "left_sleeve", "right_pants", "left_pants", "jacket", "ear", "cloak")) {
            assertTrue(root.hasChild(child), child);
        }
        assertPivot(root.getChild("head"), 0, 0, 0);
        assertPivot(root.getChild("body"), 0, 0, 0);
        assertPivot(root.getChild("right_arm"), -5, 2, 0);
        assertPivot(root.getChild("left_arm"), 5, 2, 0);
        assertPivot(root.getChild("right_leg"), -2, 12, 0);
        assertPivot(root.getChild("left_leg"), 2, 12, 0);
        assertTrue(root.getChild("head").hasChild("hair"));
        assertTrue(root.getChild("head").hasChild("beard"));
        assertTrue(root.getChild("head").hasChild("head_accessories"));
        assertTrue(root.getChild("body").hasChild("torso"));
        assertTrue(root.getChild("body").hasChild("accessories"));
        assertFalse(root.getChild("right_arm").hasChild("right_hand"));
        assertFalse(root.hasChild("right_hand"));
        List<ModelPart.Cube> citizenCubes = new ArrayList<>();
        root.visit(new PoseStack(), (pose, path, index, cube) -> citizenCubes.add(cube));
        assertEquals(22, citizenCubes.size());
        ModelPart hammer = ArgonCitizenGeometry.createHammer(source());
        assertPivot(hammer, -1, 9, 0);
        List<ModelPart.Cube> toolCubes = new ArrayList<>();
        hammer.visit(new PoseStack(), (pose, path, index, cube) -> toolCubes.add(cube));
        assertEquals(2, toolCubes.size());
    }

    @Test void primaryHeadFaceUvsAndNormalsMatchPinnedNativePlayerCubeExactly() throws IOException {
        ModelPart head = ArgonCitizenGeometry.createRoot(source()).getChild("head");
        Capture actual = new Capture();
        head.visit(new PoseStack(), (pose, path, index, cube) -> {
            if (path.isEmpty() && index == 0) cube.compile(pose, actual, 0, 0, -1);
        });
        ModelPart nativeHead = new ModelPart(List.of(new ModelPart.Cube(0, 0, -4, -8, -4, 8, 8, 8,
                0, 0, 0, false, 64, 64, EnumSet.allOf(Direction.class))), Map.of());
        Capture expected = new Capture();
        nativeHead.render(new PoseStack(), expected, 0, 0);
        assertEquals(24, actual.vertices.size());
        Comparator<Vertex> ordering = Comparator.comparing(Vertex::toString);
        actual.vertices.sort(ordering);
        expected.vertices.sort(ordering);
        assertEquals(expected.vertices, actual.vertices, "Position, texture orientation and normals must match native 1.21.1");
    }

    @Test void accessoryOffsetsKeepBandAndFringeAboveEyePixels() throws IOException {
        ModelPart head = ArgonCitizenGeometry.createRoot(source()).getChild("head");
        assertPivot(head.getChild("hair"), 0, -.75F, 0);
        assertPivot(head.getChild("head_accessories"), 0, -2, 0);
        Capture fringe = new Capture();
        head.getChild("hair").visit(new PoseStack(), (pose, path, index, cube) -> {
            if (index == 0) cube.compile(pose, fringe, 0, 0, -1);
        });
        assertTrue(fringe.vertices.stream().allMatch(vertex -> vertex.y * 16 <= -5.1999F));
        Capture band = new Capture();
        head.getChild("head_accessories").render(new PoseStack(), band, 0, 0);
        assertTrue(band.vertices.stream().allMatch(vertex -> vertex.y * 16 < -6));
    }

    @Test void malformedResourcesAreRejectedAsControlledExceptions() throws IOException {
        for (String bad : List.of("", "null", "{}", "[]", "{", " ".repeat(65_537))) {
            assertThrows(IllegalArgumentException.class, () -> ArgonCitizenGeometry.createRoot(bad));
        }
        JsonObject missingHead = JsonParser.parseString(source()).getAsJsonObject();
        var bones = missingHead.getAsJsonArray("minecraft:geometry").get(0).getAsJsonObject().getAsJsonArray("bones");
        for (int index = 0; index < bones.size(); index++) {
            if (bones.get(index).getAsJsonObject().get("name").getAsString().equals("head")) { bones.remove(index); break; }
        }
        assertThrows(IllegalArgumentException.class, () -> ArgonCitizenGeometry.createRoot(missingHead.toString()));
        JsonObject missingUv = JsonParser.parseString(source()).getAsJsonObject();
        missingUv.getAsJsonArray("minecraft:geometry").get(0).getAsJsonObject().getAsJsonArray("bones")
                .get(2).getAsJsonObject().getAsJsonArray("cubes").get(0).getAsJsonObject().remove("uv");
        assertThrows(IllegalArgumentException.class, () -> ArgonCitizenGeometry.createRoot(missingUv.toString()));
        JsonObject wrongDimensions = JsonParser.parseString(source()).getAsJsonObject();
        wrongDimensions.getAsJsonArray("minecraft:geometry").get(0).getAsJsonObject().getAsJsonObject("description")
                .addProperty("texture_width", 128);
        assertThrows(IllegalArgumentException.class, () -> ArgonCitizenGeometry.createRoot(wrongDimensions.toString()));
    }

    private static void assertPivot(ModelPart part, float x, float y, float z) {
        assertEquals(x, part.x); assertEquals(y, part.y); assertEquals(z, part.z);
        assertEquals(x, part.getInitialPose().x); assertEquals(y, part.getInitialPose().y); assertEquals(z, part.getInitialPose().z);
    }

    private record Vertex(float x, float y, float z, float u, float v, float nx, float ny, float nz) { }

    private static final class Capture implements VertexConsumer {
        final List<Vertex> vertices = new ArrayList<>();
        @Override public void addVertex(float x, float y, float z, int color, float u, float v, int overlay, int light, float nx, float ny, float nz) {
            vertices.add(new Vertex(x, y, z, u, v, nx, ny, nz));
        }
        @Override public VertexConsumer addVertex(float x, float y, float z) { return this; }
        @Override public VertexConsumer setColor(int r, int g, int b, int a) { return this; }
        @Override public VertexConsumer setUv(float u, float v) { return this; }
        @Override public VertexConsumer setUv1(int u, int v) { return this; }
        @Override public VertexConsumer setUv2(int u, int v) { return this; }
        @Override public VertexConsumer setNormal(float x, float y, float z) { return this; }
    }
}
