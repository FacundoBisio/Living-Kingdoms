package dev.livingkingdoms.client.citizenvisual;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import org.joml.Vector3f;

/** Bakes the supplied per-face Blockbench mesh into the existing vanilla PlayerModel contract. */
public final class ArgonCitizenGeometry {
    private static final ResourceLocation MODEL = ResourceLocation.fromNamespaceAndPath("livingkingdoms", "geo/argon4/citizen.geo.json");
    private static final int MAX_BYTES = 65_536;

    private ArgonCitizenGeometry() { }

    static ModelPart createRoot(ResourceManager resources) {
        return createRoot(load(resources));
    }

    /** CPU-only seam for geometry/UV tests; identical baking to resource reloads. */
    static ModelPart createRoot(String source) {
        return createRoot(parse(source));
    }

    private static ModelPart createRoot(Map<String, JsonObject> bones) {
        try { return bakeRoot(bones, true); }
        catch (RuntimeException exception) { throw invalid(exception); }
    }

    /**
     * Bakes a pack that already follows the citizen rig (same bones, pivots and per-face UV convention) exactly as
     * authored: none of the citizen-only UV remaps or offsets for hair, beard and head accessories are applied.
     */
    public static ModelPart createRawRoot(ResourceManager resources, ResourceLocation model) {
        return createRawRoot(load(resources, model));
    }

    /** CPU-only seam for tests; identical baking to resource reloads. */
    public static ModelPart createRawRoot(String source) {
        return createRawRoot(parse(source));
    }

    private static ModelPart createRawRoot(Map<String, JsonObject> bones) {
        try { return bakeRoot(bones, false); }
        catch (RuntimeException exception) { throw invalid(exception); }
    }

    private static ModelPart bakeRoot(Map<String, JsonObject> bones, boolean adapt) {
        Map<String, ModelPart> children = new LinkedHashMap<>();
        // Original fringe and band touch skin eye pixels. Static offsets leave
        // those eyes exposed without editing the archived source geometry.
        ModelPart hair = positioned(part(bones.get("hair"), 0, 0, 0, "hair", Map.of(), adapt), 0, adapt ? -.75F : 0, 0);
        ModelPart beard = part(bones.get("beard"), 0, 0, 0, "beard", Map.of(), adapt);
        ModelPart headAccessories = positioned(part(bones.get("head_accessories"), 0, 0, 0, "head_accessories", Map.of(), adapt), 0, adapt ? -2 : 0, 0);
        children.put("head", part(bones.get("head"), 0, 0, 0, "head", Map.of(
                "hair", hair, "beard", beard, "head_accessories", headAccessories), adapt));
        // Native body pivot must stay at y=0 for the existing armor layer. The
        // imported body pivot at y=12 is a Bedrock hierarchy detail, not anatomy.
        children.put("body", empty(Map.of(
                "torso", part(bones.get("torso"), 0, 0, 0, "torso", Map.of(), adapt),
                "accessories", part(bones.get("accessories"), 0, 0, 0, "accessories", Map.of(), adapt))));
        children.put("right_arm", positioned(part(bones.get("right_arm"), -5, 2, 0, "right_arm", Map.of(), adapt), -5, 2, 0));
        children.put("left_arm", positioned(part(bones.get("left_arm"), 5, 2, 0, "left_arm", Map.of(), adapt), 5, 2, 0));
        children.put("right_leg", positioned(part(bones.get("right_leg"), -2, 12, 0, "right_leg", Map.of(), adapt), -2, 12, 0));
        children.put("left_leg", positioned(part(bones.get("left_leg"), 2, 12, 0, "left_leg", Map.of(), adapt), 2, 12, 0));
        for (String name : List.of("hat", "jacket", "right_sleeve", "left_sleeve", "right_pants", "left_pants", "ear", "cloak")) {
            children.put(name, empty(Map.of()));
        }
        return empty(children);
    }

    /** Draw after the working arm's transform. Never permanently attached to a resting citizen. */
    static ModelPart createHammer(ResourceManager resources) {
        return createHammer(load(resources));
    }

    static ModelPart createHammer(String source) {
        return createHammer(parse(source));
    }

    private static ModelPart createHammer(Map<String, JsonObject> bones) {
        // The original hand pivot [6,13,0] is [-1,9,0] relative to native right arm.
        // Keep the native shoulder transform, then place the tool at its source
        // hand pivot so imported hand rotations do not swing around the shoulder.
        try { return positioned(part(bones.get("right_hand"), -6, 11, 0, "right_hand", Map.of(), true), -1, 9, 0); }
        catch (RuntimeException exception) { throw invalid(exception); }
    }

    private static ModelPart positioned(ModelPart part, float x, float y, float z) {
        part.setPos(x, y, z);
        part.setInitialPose(PartPose.offset(x, y, z));
        return part;
    }

    private static ModelPart empty(Map<String, ModelPart> children) {
        return new ModelPart(List.of(), children);
    }

    private static Map<String, JsonObject> load(ResourceManager resources) { return load(resources, MODEL); }

    private static Map<String, JsonObject> load(ResourceManager resources, ResourceLocation model) {
        try (InputStream stream = resources.getResourceOrThrow(model).open()) {
            byte[] data = stream.readNBytes(MAX_BYTES + 1);
            if (data.length > MAX_BYTES) throw new IllegalArgumentException("Argon geometry exceeds 64 KiB");
            return parse(new String(data, StandardCharsets.UTF_8));
        } catch (IOException exception) {
            throw new IllegalStateException("Could not load Argon citizen geometry", exception);
        }
    }

    private static Map<String, JsonObject> parse(String source) {
        try {
            if (source == null || source.length() > MAX_BYTES || source.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
                throw new IllegalArgumentException("Argon geometry exceeds 64 KiB");
            }
            JsonArray models = JsonParser.parseString(source).getAsJsonObject().getAsJsonArray("minecraft:geometry");
            if (models.size() != 1) throw new IllegalArgumentException("Expected one Argon geometry");
            JsonObject geometry = models.get(0).getAsJsonObject();
            JsonObject description = geometry.getAsJsonObject("description");
            if (description.get("texture_width").getAsInt() != 64 || description.get("texture_height").getAsInt() != 64) {
                throw new IllegalArgumentException("Argon geometry must use 64x64 skins");
            }
            JsonArray imported = geometry.getAsJsonArray("bones");
            if (imported.size() > 32) throw new IllegalArgumentException("Too many Argon bones");
            Map<String, JsonObject> result = new LinkedHashMap<>();
            int cubes = 0;
            for (var value : imported) {
                JsonObject bone = value.getAsJsonObject();
                if (bone.has("rotation")) throw new IllegalArgumentException("Rotated source bones require explicit adaptation");
                vector(bone.getAsJsonArray("pivot"));
                String name = bone.get("name").getAsString();
                if (result.put(name, bone) != null) throw new IllegalArgumentException("Duplicate Argon bone: " + name);
                if (bone.has("cubes")) cubes += bone.getAsJsonArray("cubes").size();
            }
            if (cubes > 64) throw new IllegalArgumentException("Too many Argon cubes");
            for (String name : List.of("head", "torso", "hair", "beard", "head_accessories", "accessories", "right_arm", "left_arm", "right_leg", "left_leg", "right_hand")) {
                if (!result.containsKey(name)) throw new IllegalArgumentException("Missing Argon bone: " + name);
            }
            return result;
        } catch (RuntimeException exception) { throw invalid(exception); }
    }

    private static IllegalArgumentException invalid(RuntimeException exception) {
        return exception instanceof IllegalArgumentException invalid ? invalid
                : new IllegalArgumentException("Invalid Argon citizen geometry", exception);
    }

    private static ModelPart part(JsonObject bone, float anchorX, float anchorY, float anchorZ, String name,
            Map<String, ModelPart> children, boolean adapt) {
        List<ModelPart.Cube> cubes = new ArrayList<>();
        if (bone.has("cubes")) {
            for (var entry : bone.getAsJsonArray("cubes")) {
                JsonObject cube = entry.getAsJsonObject();
                if (cube.has("rotation")) throw new IllegalArgumentException("Rotated source cubes require explicit adaptation");
                float[] origin = vector(cube.getAsJsonArray("origin"));
                float[] size = vector(cube.getAsJsonArray("size"));
                for (float extent : size) if (extent <= 0 || extent > 32) throw new IllegalArgumentException("Invalid Argon cube size");
                float inflate = cube.has("inflate") ? cube.get("inflate").getAsFloat() : 0;
                if (!Float.isFinite(inflate) || inflate < 0 || inflate > 1) throw new IllegalArgumentException("Invalid Argon inflation");
                cubes.add(new FaceCube(-origin[0] - size[0] - anchorX, 24 - origin[1] - size[1] - anchorY,
                        origin[2] - anchorZ, size[0], size[1], size[2], inflate, cube.getAsJsonObject("uv"), name, adapt));
            }
        }
        return new ModelPart(cubes, children);
    }

    private static float[] vector(JsonArray values) {
        if (values == null || values.size() != 3) throw new IllegalArgumentException("Expected a three-component Argon vector");
        float[] result = new float[3];
        for (int index = 0; index < 3; index++) {
            result[index] = values.get(index).getAsFloat();
            if (!Float.isFinite(result[index]) || Math.abs(result[index]) > 64) throw new IllegalArgumentException("Invalid Argon coordinate");
        }
        return result;
    }

    /** Exact fractional geometry/per-face UVs; no reflective access or per-frame mesh construction. */
    private static final class FaceCube extends ModelPart.Cube {
        private final Face[] faces;
        private final Vector3f position = new Vector3f();
        private final Vector3f normal = new Vector3f();

        FaceCube(float x, float y, float z, float width, float height, float depth, float inflate, JsonObject uv, String bone,
                boolean adapt) {
            super(0, 0, x, y, z, width, height, depth, 0, 0, 0, false, 64, 64, Set.of());
            float x0 = x - inflate, y0 = y - inflate, z0 = z - inflate;
            float x1 = x + width + inflate, y1 = y + height + inflate, z1 = z + depth + inflate;
            faces = new Face[]{
                    face(Direction.NORTH, uv, "north", bone, adapt, new float[][]{{x1,y0,z0},{x0,y0,z0},{x0,y1,z0},{x1,y1,z0}}),
                    face(Direction.SOUTH, uv, "south", bone, adapt, new float[][]{{x0,y0,z1},{x1,y0,z1},{x1,y1,z1},{x0,y1,z1}}),
                    face(Direction.WEST, uv, "east", bone, adapt, new float[][]{{x0,y0,z0},{x0,y0,z1},{x0,y1,z1},{x0,y1,z0}}),
                    face(Direction.EAST, uv, "west", bone, adapt, new float[][]{{x1,y0,z1},{x1,y0,z0},{x1,y1,z0},{x1,y1,z1}}),
                    face(Direction.DOWN, uv, "up", bone, adapt, new float[][]{{x1,y0,z1},{x0,y0,z1},{x0,y0,z0},{x1,y0,z0}}),
                    face(Direction.UP, uv, "down", bone, adapt, new float[][]{{x1,y1,z0},{x0,y1,z0},{x0,y1,z1},{x1,y1,z1}})
            };
        }

        private static Face face(Direction direction, JsonObject faces, String key, String bone, boolean adapt, float[][] positions) {
            JsonObject face = faces.getAsJsonObject(key);
            if (face == null) throw new IllegalArgumentException("Missing Argon face: " + key);
            JsonArray start = face.getAsJsonArray("uv"), extent = face.getAsJsonArray("uv_size");
            if (start.size() != 2 || extent.size() != 2) throw new IllegalArgumentException("Invalid Argon face UV");
            float u = start.get(0).getAsFloat(), v = start.get(1).getAsFloat();
            float w = extent.get(0).getAsFloat(), h = extent.get(1).getAsFloat();
            if (!Float.isFinite(u) || !Float.isFinite(v) || !Float.isFinite(w) || !Float.isFinite(h)
                    || u < 0 || v < 0 || u > 64 || v > 64 || u + w < 0 || u + w > 64 || v + h < 0 || v + h > 64) {
                throw new IllegalArgumentException("Argon face UV outside 64x64 texture");
            }
            if (adapt && bone.equals("beard")) { u = 56; v = 42; w = w == 0 ? 0 : Math.signum(w) * Math.min(Math.abs(w), 6); }
            if (adapt && bone.equals("head_accessories")) { u = 56; v = 44; w = w == 0 ? 0 : Math.signum(w) * Math.min(Math.abs(w), 8); }
            // Zero-area source cap UVs deliberately select a single texture row.
            if (adapt && w < 0 && (bone.equals("beard") || bone.equals("head_accessories"))) u -= w;
            // Bedrock Y points up; native ModelPart Y points down. Its lower
            // face also reverses the V extent, matching the existing player UVs.
            if (key.equals("down")) { v += h; h = -h; }
            return new Face(positions, new float[]{(u+w)/64, v/64, u/64, v/64, u/64, (v+h)/64, (u+w)/64, (v+h)/64}, direction.step());
        }

        @Override public void compile(PoseStack.Pose pose, VertexConsumer consumer, int light, int overlay, int color) {
            for (Face face : faces) {
                pose.transformNormal(face.normal, normal);
                for (int vertex = 0; vertex < 4; vertex++) {
                    float[] point = face.positions[vertex];
                    pose.pose().transformPosition(point[0] / 16, point[1] / 16, point[2] / 16, position);
                    consumer.addVertex(position.x(), position.y(), position.z(), color, face.uv[vertex * 2], face.uv[vertex * 2 + 1],
                            overlay, light, normal.x(), normal.y(), normal.z());
                }
            }
        }
    }

    private record Face(float[][] positions, float[] uv, Vector3f normal) { }
}
