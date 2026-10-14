package dev.livingkingdoms.citizenvisual;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.google.gson.stream.JsonReader;
import java.io.IOException;
import java.io.StringReader;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Bounded, immutable reader for the numeric GeckoLib 1.8 animation subset shipped with Argon 4.
 * Sampling returns degrees for rotation and Blockbench pixels for position; it allocates no objects.
 * This is presentation data only: callers choose clips from existing authoritative activity states.
 */
public final class ArgonAnimationSet {
    public static final int MAX_DOCUMENT_CHARS = 262_144;
    public static final int MAX_CLIPS = 48;
    public static final int MAX_KEYFRAMES_PER_TRACK = 128;
    private static final int MAX_DOCUMENTS = 8;
    private static final int MAX_TOTAL_KEYFRAMES = 8_192;
    private static final int TRANSFORM_COUNT = 2;
    private static final int CHANNELS = Bone.values().length * TRANSFORM_COUNT;
    private final Map<String, Clip> clips;

    private ArgonAnimationSet(Map<String, Clip> clips) {
        this.clips = Collections.unmodifiableMap(new LinkedHashMap<>(clips));
    }

    public enum Bone {
        ROOT, BODY, TORSO, HEAD, HAIR, BEARD, HEAD_ACCESSORIES,
        RIGHT_ARM, RIGHT_HAND, LEFT_ARM, LEFT_HAND, RIGHT_LEG, LEFT_LEG, ACCESSORIES;
        private static Bone parse(String name) {
            for (Bone bone : values()) if (bone.name().toLowerCase(java.util.Locale.ROOT).equals(name)) return bone;
            throw invalid("Unknown animation bone: " + name);
        }
    }

    public enum Transform {
        ROTATION, POSITION;
        private static Transform parse(String name) {
            return switch (name) {
                case "rotation" -> ROTATION;
                case "position" -> POSITION;
                default -> throw invalid("Unsupported animation transform: " + name);
            };
        }
    }

    /** Parse once at resource loading, accepting duplicate clips only when their numeric tracks match. */
    public static ArgonAnimationSet parse(String... documents) {
        if (documents == null || documents.length == 0 || documents.length > MAX_DOCUMENTS)
            throw invalid("Expected between 1 and " + MAX_DOCUMENTS + " animation documents");
        Map<String, Clip> clips = new LinkedHashMap<>();
        int totalKeyframes = 0;
        for (String document : documents) {
            JsonObject root = object(read(document), "animation document");
            fields(root, Set.of("format_version", "animations"));
            JsonElement format = root.get("format_version");
            if (format == null || !format.isJsonPrimitive() || !format.getAsJsonPrimitive().isString()
                    || !"1.8.0".equals(format.getAsString())) throw invalid("Expected animation format 1.8.0");
            JsonObject entries = object(root.get("animations"), "animations");
            if (entries.size() == 0 || entries.size() > MAX_CLIPS) throw invalid("Invalid animation count");
            for (Map.Entry<String, JsonElement> entry : entries.entrySet()) {
                String name = entry.getKey();
                if (!name.matches("[a-z][a-z0-9_.]{0,95}")) throw invalid("Invalid animation identifier");
                Clip clip = parseClip(object(entry.getValue(), name));
                Clip previous = clips.putIfAbsent(name, clip);
                if (previous != null && !previous.sameAs(clip)) throw invalid("Conflicting duplicate animation: " + name);
                if (previous == null) {
                    totalKeyframes += clip.keyframeCount();
                    if (clips.size() > MAX_CLIPS || totalKeyframes > MAX_TOTAL_KEYFRAMES)
                        throw invalid("Animation set exceeds resource budget");
                }
            }
        }
        return new ArgonAnimationSet(clips);
    }

    public Set<String> names() { return clips.keySet(); }
    /** Nullable lookup for callers that retain native poses when a resource pack omits an optional clip. */
    public Clip findClip(String name) { return clips.get(name); }
    public Clip clip(String name) {
        Clip clip = clips.get(name);
        if (clip == null) throw invalid("Unknown animation: " + name);
        return clip;
    }

    public static final class Clip {
        private final float lengthSeconds;
        private final boolean loop;
        private final Track[] tracks;
        private Clip(float lengthSeconds, boolean loop, Track[] tracks) {
            this.lengthSeconds = lengthSeconds; this.loop = loop; this.tracks = tracks;
        }
        public float lengthSeconds() { return lengthSeconds; }
        public boolean loop() { return loop; }
        public boolean hasTrack(Bone bone, Transform transform) { return tracks[channel(bone, transform)] != null; }
        public float sample(Bone bone, Transform transform, int axis, float seconds) {
            if (axis < 0 || axis > 2 || !Float.isFinite(seconds)) throw invalid("Invalid animation sample");
            Track track = tracks[channel(bone, transform)];
            if (track == null) return 0;
            float time = loop ? seconds % lengthSeconds : Math.max(0, Math.min(seconds, lengthSeconds));
            if (time < 0) time += lengthSeconds;
            return track.sample(axis, time);
        }
        private boolean sameAs(Clip other) {
            if (lengthSeconds != other.lengthSeconds || loop != other.loop) return false;
            for (int i = 0; i < tracks.length; i++) {
                Track a = tracks[i], b = other.tracks[i];
                if (a == null ? b != null : b == null || !Arrays.equals(a.times, b.times) || !Arrays.equals(a.values, b.values))
                    return false;
            }
            return true;
        }
        private int keyframeCount() {
            int count = 0;
            for (Track track : tracks) if (track != null) count += track.times.length;
            return count;
        }
    }

    private static final class Track {
        private final float[] times;
        private final float[] values;
        private Track(float[] times, float[] values) { this.times = times; this.values = values; }
        private float sample(int axis, float time) {
            int end = times.length - 1;
            if (time <= times[0]) return values[axis];
            if (time >= times[end]) return values[end * 3 + axis];
            int index = Arrays.binarySearch(times, time);
            if (index >= 0) return values[index * 3 + axis];
            int next = -index - 1, previous = next - 1;
            float fraction = (time - times[previous]) / (times[next] - times[previous]);
            float before = values[previous * 3 + axis], after = values[next * 3 + axis];
            return before + (after - before) * fraction;
        }
    }

    private static Clip parseClip(JsonObject source) {
        fields(source, Set.of("loop", "animation_length", "bones"));
        float length = number(source.get("animation_length"));
        if (length <= 0 || length > 120) throw invalid("Animation length must be in (0, 120]");
        JsonElement looping = source.get("loop");
        if (looping != null && (!looping.isJsonPrimitive() || !looping.getAsJsonPrimitive().isBoolean()))
            throw invalid("Animation loop must be boolean");
        JsonObject bones = object(source.get("bones"), "bones");
        if (bones.size() == 0 || bones.size() > Bone.values().length) throw invalid("Invalid animation bone count");
        Track[] tracks = new Track[CHANNELS];
        for (Map.Entry<String, JsonElement> entry : bones.entrySet()) {
            Bone bone = Bone.parse(entry.getKey());
            JsonObject transforms = object(entry.getValue(), "bone transforms");
            if (transforms.size() == 0) throw invalid("Empty bone transforms");
            for (Map.Entry<String, JsonElement> transform : transforms.entrySet()) {
                Transform type = Transform.parse(transform.getKey());
                tracks[channel(bone, type)] = parseTrack(transform.getValue(), type, length);
            }
        }
        return new Clip(length, looping != null && looping.getAsBoolean(), tracks);
    }

    private static Track parseTrack(JsonElement source, Transform type, float length) {
        TreeMap<Float, float[]> sorted = new TreeMap<>();
        if (source != null && source.isJsonArray()) sorted.put(0f, vector(source, type));
        else {
            JsonObject frames = object(source, "keyframes");
            if (frames.size() == 0 || frames.size() > MAX_KEYFRAMES_PER_TRACK) throw invalid("Invalid keyframe count");
            for (Map.Entry<String, JsonElement> frame : frames.entrySet()) {
                float time;
                try { time = Float.parseFloat(frame.getKey()); }
                catch (NumberFormatException ex) { throw invalid("Invalid keyframe time"); }
                if (!Float.isFinite(time) || time < 0 || time > length) throw invalid("Keyframe outside animation");
                if (time == 0) time = 0; // Normalize negative zero and equivalent timestamp spellings.
                if (sorted.putIfAbsent(time, vector(frame.getValue(), type)) != null) throw invalid("Duplicate keyframe time");
            }
        }
        float[] times = new float[sorted.size()], values = new float[sorted.size() * 3];
        int index = 0;
        for (Map.Entry<Float, float[]> frame : sorted.entrySet()) {
            times[index] = frame.getKey();
            System.arraycopy(frame.getValue(), 0, values, index * 3, 3);
            index++;
        }
        return new Track(times, values);
    }

    private static float[] vector(JsonElement source, Transform type) {
        if (source == null || !source.isJsonArray() || source.getAsJsonArray().size() != 3)
            throw invalid("Expected three numeric animation values");
        float[] result = new float[3];
        float limit = type == Transform.ROTATION ? 3600 : 256;
        for (int i = 0; i < result.length; i++) {
            result[i] = number(source.getAsJsonArray().get(i));
            if (Math.abs(result[i]) > limit) throw invalid("Animation value exceeds supported range");
        }
        return result;
    }

    private static JsonElement read(String document) {
        if (document == null || document.length() > MAX_DOCUMENT_CHARS) throw invalid("Animation document exceeds size budget");
        try (JsonReader reader = new JsonReader(new StringReader(document))) {
            reader.setLenient(false);
            JsonElement result = readValue(reader, 0, new int[1]);
            if (reader.peek() != com.google.gson.stream.JsonToken.END_DOCUMENT) throw invalid("Trailing animation JSON");
            return result;
        } catch (IOException | IllegalStateException ex) {
            throw new IllegalArgumentException("Invalid animation JSON", ex);
        }
    }

    /** Reject duplicate object keys, excessive nesting and MoLang strings instead of silently losing data. */
    private static JsonElement readValue(JsonReader reader, int depth, int[] nodes) throws IOException {
        if (depth > 12 || ++nodes[0] > 40_000) throw invalid("Animation JSON exceeds structure budget");
        return switch (reader.peek()) {
            case BEGIN_OBJECT -> {
                JsonObject result = new JsonObject(); reader.beginObject();
                while (reader.hasNext()) {
                    String name = reader.nextName();
                    if (name.length() > 96 || result.has(name)) throw invalid("Invalid or duplicate JSON key");
                    result.add(name, readValue(reader, depth + 1, nodes));
                }
                reader.endObject(); yield result;
            }
            case BEGIN_ARRAY -> {
                JsonArray result = new JsonArray(); reader.beginArray();
                while (reader.hasNext()) result.add(readValue(reader, depth + 1, nodes));
                reader.endArray(); yield result;
            }
            case STRING -> {
                String value = reader.nextString();
                if (value.length() > 96) throw invalid("Animation string exceeds length budget");
                yield new JsonPrimitive(value);
            }
            case NUMBER -> {
                double value;
                try { value = Double.parseDouble(reader.nextString()); }
                catch (NumberFormatException ex) { throw invalid("Invalid animation number"); }
                if (!Double.isFinite(value)) throw invalid("Nonfinite animation number");
                yield new JsonPrimitive(value);
            }
            case BOOLEAN -> new JsonPrimitive(reader.nextBoolean());
            default -> throw invalid("Unsupported animation JSON value");
        };
    }

    private static float number(JsonElement source) {
        if (source == null || !source.isJsonPrimitive() || !source.getAsJsonPrimitive().isNumber())
            throw invalid("Expected finite numeric animation value");
        float value = source.getAsFloat();
        if (!Float.isFinite(value)) throw invalid("Nonfinite animation value");
        return value;
    }
    private static JsonObject object(JsonElement source, String label) {
        if (source == null || !source.isJsonObject()) throw invalid("Expected object: " + label);
        return source.getAsJsonObject();
    }
    private static void fields(JsonObject object, Set<String> allowed) {
        for (String field : object.keySet()) if (!allowed.contains(field)) throw invalid("Unsupported animation field: " + field);
    }
    private static int channel(Bone bone, Transform transform) {
        if (bone == null || transform == null) throw invalid("Missing animation channel");
        return bone.ordinal() * TRANSFORM_COUNT + transform.ordinal();
    }
    private static IllegalArgumentException invalid(String message) { return new IllegalArgumentException(message); }
}
