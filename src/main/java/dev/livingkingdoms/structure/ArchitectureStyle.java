package dev.livingkingdoms.structure;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/** Style intent is metadata; every style currently falls back to the plains placeholder catalog. */
public enum ArchitectureStyle {
    PLAINS, TAIGA, DESERT, SNOW, SAVANNA;

    public static ArchitectureStyle at(ServerLevel level, BlockPos pos) {
        String biome = level.getBiome(pos).unwrapKey().map(key -> key.location().getPath()).orElse("plains");
        if (biome.contains("snow") || biome.contains("frozen") || biome.contains("ice")) return SNOW;
        if (biome.contains("taiga")) return TAIGA;
        if (biome.contains("desert") || biome.contains("badlands")) return DESERT;
        if (biome.contains("savanna")) return SAVANNA;
        return PLAINS;
    }
}
