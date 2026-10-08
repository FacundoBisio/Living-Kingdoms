package dev.livingkingdoms.structure;

import dev.livingkingdoms.LivingKingdoms;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;

import java.util.EnumMap;
import java.util.Map;

/** Catalog boundary permits future biome-specific exports without changing placement. */
public record BuildingCatalog(ArchitectureStyle style, Map<BuildingKind, BuildingTemplate> modules) {
    public BuildingCatalog { modules = Map.copyOf(modules); }

    public static BuildingCatalog load(ServerLevel level, ArchitectureStyle style) {
        Map<BuildingKind, BuildingTemplate> modules = new EnumMap<>(BuildingKind.class);
        for (BuildingKind kind : BuildingKind.values()) {
            ResourceLocation id = ResourceLocation.fromNamespaceAndPath(LivingKingdoms.MOD_ID,
                    "allied/plains/" + kind.name().toLowerCase(java.util.Locale.ROOT));
            var nativeTemplate = level.getStructureManager().get(id)
                    .orElseThrow(() -> new IllegalArgumentException("Missing module: " + id));
            modules.put(kind, BuildingTemplate.validate(kind, id, nativeTemplate));
        }
        return new BuildingCatalog(style, modules);
    }

    public BuildingTemplate get(BuildingKind kind) { return java.util.Objects.requireNonNull(modules.get(kind)); }
}
