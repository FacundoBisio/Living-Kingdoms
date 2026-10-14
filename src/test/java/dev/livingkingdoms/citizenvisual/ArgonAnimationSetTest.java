package dev.livingkingdoms.citizenvisual;

import static dev.livingkingdoms.citizenvisual.ArgonAnimationSet.Bone.*;
import static dev.livingkingdoms.citizenvisual.ArgonAnimationSet.Transform.*;
import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class ArgonAnimationSetTest {
    private static final String CLIP = "animation.citizen.test";

    @Test
    void originalResourcesHaveNineDistinctClipsAndOneIdenticalHammerDuplicate() throws IOException {
        String citizen = resource("citizen"), professions = resource("professions"), combat = resource("combat");
        var set = ArgonAnimationSet.parse(citizen, professions, combat);
        assertEquals(Set.of("animation.citizen.idle", "animation.citizen.walk", "animation.citizen.run",
                "animation.citizen.work_hammer", "animation.citizen.farm_plant", "animation.citizen.lumberjack_chop",
                "animation.citizen.guard_patrol", "animation.citizen.guard_attack", "animation.citizen.guard_block"), set.names());
        var hammer = set.clip("animation.citizen.work_hammer");
        assertTrue(hammer.loop());
        assertEquals(1.6f, hammer.lengthSeconds());
        assertEquals(-75f, hammer.sample(RIGHT_ARM, ROTATION, 0, 0.4f));
        assertEquals(-23.5f, hammer.sample(RIGHT_ARM, ROTATION, 0, 0.55f), 0.0001f);
        assertEquals(-25f, hammer.sample(RIGHT_HAND, ROTATION, 0, 0.4f));
        assertFalse(hammer.hasTrack(LEFT_LEG, ROTATION));
        assertEquals(0, hammer.sample(LEFT_LEG, ROTATION, 0, 0.4f));
        assertFalse(set.clip("animation.citizen.guard_attack").loop());
        assertThrows(UnsupportedOperationException.class, () -> set.names().clear());
    }

    @Test
    void allShippedTracksAreDeterministicAndFiniteAfterIndependentReload() throws IOException {
        String[] resources = {resource("citizen"), resource("professions"), resource("combat")};
        var original = ArgonAnimationSet.parse(resources);
        var reloaded = ArgonAnimationSet.parse(resources);
        for (String name : original.names()) for (var bone : ArgonAnimationSet.Bone.values())
            for (var transform : ArgonAnimationSet.Transform.values()) for (int axis = 0; axis < 3; axis++)
                for (float seconds : new float[]{-10, 0, 0.17f, 0.5f, 2, 50, Float.MAX_VALUE}) {
                    float value = original.clip(name).sample(bone, transform, axis, seconds);
                    assertTrue(Float.isFinite(value), name);
                    assertEquals(value, reloaded.clip(name).sample(bone, transform, axis, seconds));
                }
    }

    @Test
    void samplesSortedLinearTracksAndClampsNonloopingAnimations() {
        var clip = ArgonAnimationSet.parse(document(false,
                "\"1\":[20,40,60],\"0\":[0,0,0],\"0.5\":[10,20,30]")).clip(CLIP);
        assertEquals(5, clip.sample(BODY, ROTATION, 0, 0.25f));
        assertEquals(20, clip.sample(BODY, ROTATION, 1, 0.5f));
        assertEquals(45, clip.sample(BODY, ROTATION, 2, 0.75f));
        assertEquals(0, clip.sample(BODY, ROTATION, 0, -1));
        assertEquals(20, clip.sample(BODY, ROTATION, 0, 20));
    }

    @Test
    void loopsInSecondsAndWrapsNegativeTimeWithoutAllocatingPoseObjects() {
        var clip = ArgonAnimationSet.parse(document(true, "\"0\":[0,0,0],\"1\":[20,40,60]")).clip(CLIP);
        assertEquals(5, clip.sample(BODY, ROTATION, 0, 1.25f));
        assertEquals(15, clip.sample(BODY, ROTATION, 0, -0.25f));
        assertEquals(0, clip.sample(BODY, ROTATION, 0, 1));
    }

    @Test
    void sourceNumbersRemainDegreesAndPixelsAndConstantVectorsWork() {
        String json = """
                {"format_version":"1.8.0","animations":{"animation.citizen.test":{
                  "animation_length":2,"bones":{"body":{"rotation":[90,0,-180],"position":[1,2,3]}}}}}
                """;
        var clip = ArgonAnimationSet.parse(json).clip(CLIP);
        assertFalse(clip.loop());
        assertEquals(90, clip.sample(BODY, ROTATION, 0, 1));
        assertEquals(-180, clip.sample(BODY, ROTATION, 2, 1));
        assertEquals(2, clip.sample(BODY, POSITION, 1, 1));
        assertTrue(clip.hasTrack(BODY, POSITION));
    }

    @Test
    void equivalentDuplicateClipsAreAcceptedButConflictingOnesFail() {
        String first = document(true, "\"0\":[0,0,0],\"1\":[20,40,60]");
        String reordered = document(true, "\"1\":[20,40,60],\"0\":[0,0,0]");
        assertEquals(1, ArgonAnimationSet.parse(first, reordered).names().size());
        assertThrows(IllegalArgumentException.class, () -> ArgonAnimationSet.parse(first, document(false,
                "\"0\":[0,0,0],\"1\":[20,40,60]")));
        assertThrows(IllegalArgumentException.class, () -> ArgonAnimationSet.parse(first, document(true,
                "\"0\":[0,0,0],\"1\":[21,40,60]")));
    }

    @ParameterizedTest
    @MethodSource("invalidDocuments")
    void rejectsUnsupportedMalformedAndUnboundedInputs(String json) {
        assertThrows(IllegalArgumentException.class, () -> ArgonAnimationSet.parse(json));
    }

    private static Stream<String> invalidDocuments() {
        String valid = document(true, "\"0\":[0,0,0],\"1\":[20,40,60]");
        return Stream.of("", "[]", "null", valid + "{}", valid.replace("1.8.0", "1.10.0"),
                valid.replace("\"loop\":true", "\"loop\":\"hold_on_last_frame\""),
                valid.replace("\"animation_length\":1", "\"animation_length\":0"),
                valid.replace("\"animation_length\":1", "\"animation_length\":121"),
                valid.replace("\"body\"", "\"missing_bone\""),
                valid.replace("\"rotation\"", "\"scale\""),
                valid.replace("[20,40,60]", "[20,40]"),
                valid.replace("[20,40,60]", "[\"query.life_time\",40,60]"),
                valid.replace("[20,40,60]", "[1e999,40,60]"),
                valid.replace("[20,40,60]", "[3601,40,60]"),
                valid.replace("\"1\":[20,40,60]", "\"2\":[20,40,60]"),
                valid.replace("\"1\":[20,40,60]", "\"NaN\":[20,40,60]"),
                valid.replace("\"1\":[20,40,60]", "\"-1\":[20,40,60]"),
                valid.replace("\"1\":[20,40,60]", "\"0.0\":[20,40,60]"),
                valid.replace("\"1\":[20,40,60]", "\"0\":[20,40,60]"),
                valid.replace("\"1\":[20,40,60]", "\"1\":{\"post\":[20,40,60]}"),
                valid.replace("\"loop\":true", "\"loop\":true,\"events\":{}"),
                " ".repeat(ArgonAnimationSet.MAX_DOCUMENT_CHARS + 1));
    }

    @Test
    void rejectsKeyframeAndClipBudgetsBeforeCreatingRuntimeData() {
        StringBuilder frames = new StringBuilder();
        for (int i = 0; i <= ArgonAnimationSet.MAX_KEYFRAMES_PER_TRACK; i++) {
            if (i > 0) frames.append(',');
            frames.append('"').append(i / 1000.0).append("\":[0,0,0]");
        }
        assertThrows(IllegalArgumentException.class, () -> ArgonAnimationSet.parse(document(false, frames.toString())));
        StringBuilder clips = new StringBuilder("{\"format_version\":\"1.8.0\",\"animations\":{");
        for (int i = 0; i <= ArgonAnimationSet.MAX_CLIPS; i++) {
            if (i > 0) clips.append(',');
            clips.append("\"animation.citizen.clip").append(i).append("\":{\"animation_length\":1,")
                    .append("\"bones\":{\"body\":{\"rotation\":[0,0,0]}}}");
        }
        clips.append("}}");
        assertThrows(IllegalArgumentException.class, () -> ArgonAnimationSet.parse(clips.toString()));
    }

    @Test
    void guardsSamplingAndProvidesOptionalFallbackLookup() {
        var set = ArgonAnimationSet.parse(document(false, "\"0\":[0,0,0]"));
        assertNull(set.findClip("missing"));
        assertThrows(IllegalArgumentException.class, () -> set.clip("missing"));
        assertThrows(IllegalArgumentException.class, () -> set.clip(CLIP).sample(BODY, ROTATION, 3, 0));
        assertThrows(IllegalArgumentException.class, () -> set.clip(CLIP).sample(BODY, ROTATION, 0, Float.NaN));
        assertThrows(IllegalArgumentException.class, () -> set.clip(CLIP).sample(null, ROTATION, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> ArgonAnimationSet.parse(new String[0]));
        assertThrows(IllegalArgumentException.class, () -> ArgonAnimationSet.parse((String[]) null));
        assertThrows(IllegalArgumentException.class, () -> ArgonAnimationSet.parse(new String[9]));
    }

    private static String document(boolean loop, String frames) {
        return "{\"format_version\":\"1.8.0\",\"animations\":{\"" + CLIP + "\":{\"loop\":" + loop
                + ",\"animation_length\":1,\"bones\":{\"body\":{\"rotation\":{" + frames + "}}}}}}";
    }

    private static String resource(String name) throws IOException {
        String path = "/assets/livingkingdoms/animations/argon4/" + name + ".animation.json";
        try (var input = ArgonAnimationSetTest.class.getResourceAsStream(path)) {
            assertNotNull(input, path);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
