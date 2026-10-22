package dev.livingkingdoms.client.warband;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.livingkingdoms.citizenvisual.ArgonAnimationSet;
import dev.livingkingdoms.client.citizenvisual.ArgonCitizenGeometry;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;
import net.minecraft.client.model.geom.ModelPart;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Resource and CPU-mesh contracts for the warband soldier; they cannot establish in-game appearance. */
class WarbandSoldierAssetsTest {
    private static final String ROOT = "/assets/livingkingdoms/";

    private static String text(String path) throws IOException {
        try (var stream = WarbandSoldierAssetsTest.class.getResourceAsStream(ROOT + path)) {
            assertNotNull(stream, path);
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test void rawBakeKeepsThePlayerContractAndLeavesTheAuthoredHelmUvsUntouched() throws IOException {
        ModelPart root = ArgonCitizenGeometry.createRawRoot(text("geo/argon4/warband_soldier.geo.json"));
        for (String child : List.of("head", "hat", "body", "right_arm", "left_arm", "right_leg", "left_leg",
                "right_sleeve", "left_sleeve", "right_pants", "left_pants", "jacket", "ear", "cloak")) {
            assertTrue(root.hasChild(child), child);
        }
        ModelPart head = root.getChild("head");
        assertTrue(head.hasChild("head_accessories") && head.hasChild("beard") && head.hasChild("hair"));
        // Raw mode applies no citizen offsets: the helm sits exactly on the head.
        assertEquals(0, head.getChild("head_accessories").y);
        assertEquals(0, head.getChild("hair").y);
        int[] cubes = {0};
        root.visit(new PoseStack(), (pose, path, index, cube) -> cubes[0]++);
        assertEquals(15, cubes[0]);
        // The helm overlay owns the [32,0] island; the citizen-only remap would move it to v=44.
        Capture helm = new Capture();
        head.getChild("head_accessories").visit(new PoseStack(), (pose, path, index, cube) -> {
            if (index == 0) cube.compile(pose, helm, 0, 0, -1);
        });
        assertEquals(24, helm.vertices.size());
        for (Vertex vertex : helm.vertices) {
            assertTrue(vertex.u * 64 >= 31.99F && vertex.u * 64 <= 64.01F && vertex.v * 64 >= -.01F && vertex.v * 64 <= 16.01F,
                    "Helm UV left its [32,0] island: " + vertex);
        }
    }

    @Test void rawModeSkipsTheCitizenOnlyOffsetsEvenOnTheCitizenSource() throws IOException {
        // The citizen path keeps its -2 offset (asserted in ArgonGeometryTest); raw mode must not apply it.
        assertEquals(0, ArgonCitizenGeometry.createRawRoot(text("geo/argon4/citizen.geo.json"))
                .getChild("head").getChild("head_accessories").y);
    }

    @Test void warbandClipsParseAlongsideTheCitizenClipsWithoutConflicts() throws IOException {
        ArgonAnimationSet set = ArgonAnimationSet.parse(text("animations/argon4/citizen.animation.json"),
                text("animations/argon4/professions.animation.json"), text("animations/argon4/combat.animation.json"),
                text("animations/argon4/warband.animation.json"));
        for (String clip : List.of("axe_attack", "crossbow_aim", "crossbow_reload", "captain_command", "death_stagger")) {
            assertNotNull(set.findClip("animation.warband." + clip), clip);
        }
        var attack = set.clip("animation.warband.axe_attack");
        assertEquals(1.0F, attack.lengthSeconds());
        assertFalse(attack.loop());
        assertTrue(attack.hasTrack(ArgonAnimationSet.Bone.RIGHT_ARM, ArgonAnimationSet.Transform.ROTATION));
        // Rest pose at both ends so the swing blends into and out of the vanilla pose.
        for (var bone : List.of(ArgonAnimationSet.Bone.BODY, ArgonAnimationSet.Bone.RIGHT_ARM, ArgonAnimationSet.Bone.LEFT_ARM)) {
            for (int axis = 0; axis < 3; axis++) {
                assertEquals(attack.sample(bone, ArgonAnimationSet.Transform.ROTATION, axis, 0),
                        attack.sample(bone, ArgonAnimationSet.Transform.ROTATION, axis, 1), 1e-4F);
            }
        }
        // The swing is a real overhead chop: the arm is raised high before it comes down.
        assertTrue(attack.sample(ArgonAnimationSet.Bone.RIGHT_ARM, ArgonAnimationSet.Transform.ROTATION, 0, .35F) < -100);
    }

    @Test void textureIsOpaqueOnEveryBodyIslandAndCutsOutTheOpenFaceHelm() throws IOException {
        BufferedImage image;
        try (var stream = WarbandSoldierAssetsTest.class.getResourceAsStream(ROOT + "textures/entity/argon4/warband_soldier.png")) {
            assertNotNull(stream);
            image = ImageIO.read(stream);
        }
        assertEquals(64, image.getWidth());
        assertEquals(64, image.getHeight());
        Map<String, int[]> islands = Map.of("head", new int[]{0, 0, 32, 16}, "torso", new int[]{16, 16, 24, 16},
                "right arm", new int[]{40, 16, 16, 16}, "right leg", new int[]{0, 16, 16, 16},
                "left arm", new int[]{32, 48, 16, 16}, "left leg", new int[]{16, 48, 16, 16});
        for (var island : islands.entrySet()) {
            int[] r = island.getValue();
            for (int y = r[1]; y < r[1] + r[3]; y++) for (int x = r[0]; x < r[0] + r[2]; x++) {
                assertEquals(255, image.getRGB(x, y) >>> 24, island.getKey() + " has a hole at " + x + "," + y);
            }
        }
        // Eyes live on the base face [8..15, 8..15]; the helm overlay face [40..47, 8..15] must leave that window open.
        int open = 0;
        for (int y = 10; y <= 13; y++) for (int x = 41; x <= 46; x++) if (image.getRGB(x, y) >>> 24 == 0) open++;
        assertTrue(open >= 12, "The open-face helm must leave the eye window transparent, found " + open + " pixels");
    }

    private record Vertex(float x, float y, float z, float u, float v) { }

    private static final class Capture implements VertexConsumer {
        final List<Vertex> vertices = new ArrayList<>();
        @Override public void addVertex(float x, float y, float z, int color, float u, float v, int overlay, int light, float nx, float ny, float nz) {
            vertices.add(new Vertex(x, y, z, u, v));
        }
        @Override public VertexConsumer addVertex(float x, float y, float z) { return this; }
        @Override public VertexConsumer setColor(int r, int g, int b, int a) { return this; }
        @Override public VertexConsumer setUv(float u, float v) { return this; }
        @Override public VertexConsumer setUv1(int u, int v) { return this; }
        @Override public VertexConsumer setUv2(int u, int v) { return this; }
        @Override public VertexConsumer setNormal(float x, float y, float z) { return this; }
    }
}
