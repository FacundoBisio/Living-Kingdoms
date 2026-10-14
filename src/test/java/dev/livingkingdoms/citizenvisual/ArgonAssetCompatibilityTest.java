package dev.livingkingdoms.citizenvisual;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Resource contracts only: these checks cannot establish rendered appearance in Minecraft. */
class ArgonAssetCompatibilityTest {
    private static final String ROOT = "/assets/livingkingdoms/";
    private static final String[] JOBS = {"unassigned", "farmer", "guard", "builder", "blacksmith",
            "merchant", "captain", "mayor", "lumberjack", "miner"};
    private static final Set<String> BONES = Set.of("root", "body", "torso", "head", "hair", "beard",
            "head_accessories", "right_arm", "right_hand", "left_arm", "left_hand", "right_leg",
            "left_leg", "accessories");

    @Test void geometryUsesFiniteBoundedPlayerCompatibleUvAndResolvableBoneHierarchy() throws IOException {
        var geometry = json("geo/argon4/citizen.geo.json").getAsJsonArray("minecraft:geometry");
        assertEquals(1, geometry.size());
        var model = geometry.get(0).getAsJsonObject();
        var description = model.getAsJsonObject("description");
        assertEquals(64, description.get("texture_width").getAsInt());
        assertEquals(64, description.get("texture_height").getAsInt());
        var bones = new HashMap<String, JsonObject>();
        for (var element : model.getAsJsonArray("bones")) {
            var bone = element.getAsJsonObject();
            assertNull(bones.put(bone.get("name").getAsString(), bone), "Duplicate bone");
        }
        assertEquals(BONES, bones.keySet());
        int cubes = 0;
        for (var entry : bones.entrySet()) {
            var bone = entry.getValue();
            finiteVector(bone.getAsJsonArray("pivot"), 3);
            var ancestors = new HashSet<String>();
            String name = entry.getKey();
            while (bones.get(name).has("parent")) {
                assertTrue(ancestors.add(name), "Cyclic bone hierarchy");
                name = bones.get(name).get("parent").getAsString();
                assertTrue(bones.containsKey(name), "Missing parent: " + name);
            }
            if (!bone.has("cubes")) continue;
            for (var element : bone.getAsJsonArray("cubes")) {
                var cube = element.getAsJsonObject();
                finiteVector(cube.getAsJsonArray("origin"), 3);
                finiteVector(cube.getAsJsonArray("size"), 3);
                for (var dimension : cube.getAsJsonArray("size")) {
                    assertTrue(dimension.getAsDouble() > 0 && dimension.getAsDouble() <= 16);
                }
                if (cube.has("rotation")) finiteVector(cube.getAsJsonArray("rotation"), 3);
                if (cube.has("pivot")) finiteVector(cube.getAsJsonArray("pivot"), 3);
                var uv = cube.getAsJsonObject("uv");
                assertEquals(Set.of("north", "east", "south", "west", "up", "down"), uv.keySet());
                for (var face : uv.entrySet()) {
                    var start = face.getValue().getAsJsonObject().getAsJsonArray("uv");
                    var extent = face.getValue().getAsJsonObject().getAsJsonArray("uv_size");
                    finiteVector(start, 2); finiteVector(extent, 2);
                    for (int axis = 0; axis < 2; axis++) {
                        double from = start.get(axis).getAsDouble();
                        double to = from + extent.get(axis).getAsDouble();
                        // Negative spans and zero-height accessory caps are valid exported face UVs.
                        assertTrue(Math.min(from, to) >= 0 && Math.max(from, to) <= 64,
                                "Face exceeds skin atlas: " + entry.getKey() + "/" + face.getKey());
                    }
                }
                cubes++;
            }
        }
        assertEquals(24, cubes, "Source includes two hammer cubes, handled separately by the renderer");
        for (String name : new String[]{"torso", "head", "right_arm", "left_arm", "right_leg", "left_leg"}) {
            var base = bones.get(name).getAsJsonArray("cubes").get(0).getAsJsonObject();
            var front = base.getAsJsonObject("uv").getAsJsonObject("north");
            int[] expected = switch (name) {
                case "torso" -> new int[]{20, 20, 8, 12};
                case "head" -> new int[]{8, 8, 8, 8};
                case "right_arm" -> new int[]{44, 20, 4, 12};
                case "left_arm" -> new int[]{36, 52, 4, 12};
                case "right_leg" -> new int[]{4, 20, 4, 12};
                default -> new int[]{20, 52, 4, 12};
            };
            assertEquals(expected[0], front.getAsJsonArray("uv").get(0).getAsInt());
            assertEquals(expected[1], front.getAsJsonArray("uv").get(1).getAsInt());
            assertEquals(expected[2], front.getAsJsonArray("uv_size").get(0).getAsInt());
            assertEquals(expected[3], front.getAsJsonArray("uv_size").get(1).getAsInt());
        }
    }

    @Test void allEightyDerivedSkinsPreserveOriginalIdentityAndPrimaryBodyPixels() throws IOException {
        int[][] primary = {{0, 0, 32, 16}, {16, 16, 24, 16}, {40, 16, 16, 16},
                {32, 48, 16, 16}, {0, 16, 16, 16}, {16, 48, 16, 16}};
        var originalHashes = new HashSet<String>();
        var derivedHashes = new HashSet<String>();
        int checked = 0;
        for (String job : JOBS) for (int variant = 0; variant < 8; variant++) {
            String file = job + "_" + variant + ".png";
            byte[] originalBytes = bytes("textures/entity/citizen/" + file);
            byte[] derivedBytes = bytes("textures/entity/citizen_argon4/" + file);
            originalHashes.add(sha256(originalBytes)); derivedHashes.add(sha256(derivedBytes));
            BufferedImage original = image(originalBytes), derived = image(derivedBytes);
            for (int[] box : primary) for (int y = box[1]; y < box[1] + box[3]; y++) {
                for (int x = box[0]; x < box[0] + box[2]; x++) {
                    assertEquals(original.getRGB(x, y), derived.getRGB(x, y), file + " identity pixel " + x + "," + y);
                }
            }
            // The external geometry reads sleeve/boot overlays absent in the older flat player skins.
            assertEquals(255, derived.getRGB(44, 36) >>> 24, "Right sleeve must not be transparent");
            assertEquals(255, derived.getRGB(52, 52) >>> 24, "Left sleeve must not be transparent");
            assertEquals(255, derived.getRGB(4, 36) >>> 24, "Right boot must not be transparent");
            assertEquals(255, derived.getRGB(4, 52) >>> 24, "Left boot must not be transparent");
            checked++;
        }
        assertEquals(80, checked);
        assertEquals(80, originalHashes.size(), "Existing variants remain distinct");
        assertEquals(80, derivedHashes.size(), "Derived assets retain all distinct identities/professions");
    }

    @Test void externalTexturesRetainTheirExactProvenanceAndAreNotPlaceholderSubstitutes() throws Exception {
        Map<String, String> hashes = Map.of(
                "src/main/resources/assets/livingkingdoms/textures/entity/argon4/builder.png",
                "d0605ce09b2da2139dc8750b4e3c4427a1186b26201b7bdbb2a511556e67da66",
                "src/main/resources/assets/livingkingdoms/textures/entity/argon4/blacksmith.png",
                "557896cc8dd4115512f22f9cba29e69e4ee3fa752fee7b25e1c0acfaf0de76ab",
                "docs/argon4-source/textures/citizen.png",
                "59134fd6960c4c18dab8e36a370e7b766ac25e5cf0f6def39d272b7d851af10a",
                "docs/argon4-source/textures/citizen_female.png",
                "7821c9608c9ea1a3b57550f04cd33b5d4a7d42b7d45a44b5557b94386e1f77cd");
        for (var entry : hashes.entrySet()) {
            var data = Files.readAllBytes(projectFile(entry.getKey()));
            assertEquals(entry.getValue(), sha256(data), "Original provided artwork changed: " + entry.getKey());
            var png = image(data);
            var colors = new HashSet<Integer>();
            for (int y = 0; y < 64; y++) for (int x = 0; x < 64; x++) colors.add(png.getRGB(x, y));
            assertTrue(colors.size() > 16, "Flat replacement artwork: " + entry.getKey());
        }
        for (String profession : new String[]{"builder", "blacksmith"}) {
            assertArrayEquals(Files.readAllBytes(projectFile("src/main/resources/assets/livingkingdoms/textures/entity/argon4/"
                    + profession + ".png")), bytes("textures/entity/argon4/" + profession + ".png"));
        }
    }

    @Test void allImportedClipsUseKnownBonesAndDuplicatedHammerTimelineIsIdentical() throws IOException {
        var clips = new HashMap<String, JsonObject>();
        int entries = 0;
        for (String file : new String[]{"citizen", "professions", "combat"}) {
            for (var entry : json("animations/argon4/" + file + ".animation.json").getAsJsonObject("animations").entrySet()) {
                var animation = entry.getValue().getAsJsonObject();
                var existing = clips.putIfAbsent(entry.getKey(), animation);
                if (existing != null) assertEquals(existing, animation, "Conflicting duplicate animation");
                double length = animation.get("animation_length").getAsDouble();
                assertTrue(Double.isFinite(length) && length > 0 && length <= 10);
                assertTrue(BONES.containsAll(animation.getAsJsonObject("bones").keySet()), "Unknown animated bone");
                entries++;
            }
        }
        assertEquals(10, entries);
        assertEquals(Set.of("animation.citizen.idle", "animation.citizen.walk", "animation.citizen.run",
                "animation.citizen.work_hammer", "animation.citizen.guard_patrol", "animation.citizen.guard_attack",
                "animation.citizen.guard_block", "animation.citizen.farm_plant", "animation.citizen.lumberjack_chop"), clips.keySet());
    }

    private static void finiteVector(JsonArray values, int length) {
        assertNotNull(values); assertEquals(length, values.size());
        for (var value : values) assertTrue(Double.isFinite(value.getAsDouble()));
    }
    private static BufferedImage image(byte[] data) throws IOException {
        var image = ImageIO.read(new java.io.ByteArrayInputStream(data));
        assertNotNull(image); assertEquals(64, image.getWidth()); assertEquals(64, image.getHeight());
        assertTrue(image.getColorModel().hasAlpha(), "Expected RGBA skin");
        return image;
    }
    private static String sha256(byte[] data) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data)); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
    private static Path projectFile(String relative) throws IOException {
        // NeoForge's unit-test launcher may use a generated child directory as its working directory.
        Path candidate = Path.of("").toAbsolutePath();
        for (int depth = 0; candidate != null && depth < 12; depth++, candidate = candidate.getParent()) {
            if (Files.isRegularFile(candidate.resolve("gradle.properties"))
                    && Files.isDirectory(candidate.resolve("src/main/resources/assets/livingkingdoms"))) {
                return candidate.resolve(relative);
            }
        }
        throw new IOException("Cannot locate project source root from " + Path.of("").toAbsolutePath());
    }
    private static byte[] bytes(String path) throws IOException {
        try (var stream = resource(path)) { return stream.readAllBytes(); }
    }
    private static JsonObject json(String path) throws IOException {
        try (var stream = resource(path); var reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        }
    }
    private static InputStream resource(String path) {
        return java.util.Objects.requireNonNull(ArgonAssetCompatibilityTest.class.getResourceAsStream(ROOT + path),
                "Missing packaged asset: " + path);
    }
}
