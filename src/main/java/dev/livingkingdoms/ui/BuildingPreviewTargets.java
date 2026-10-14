package dev.livingkingdoms.ui;
import dev.livingkingdoms.structure.BuildingKind;
import java.nio.charset.StandardCharsets;
import java.util.*;
public final class BuildingPreviewTargets {
    private static final Map<UUID,BuildingKind> TARGETS=new HashMap<>();
    static {for(var kind:BuildingKind.values()) if(dev.livingkingdoms.construction.preview.ConstructionPreviewService.supported(kind)) TARGETS.put(target(kind),kind);}
    private BuildingPreviewTargets() {}
    public static UUID target(BuildingKind kind) {return UUID.nameUUIDFromBytes(("livingkingdoms:preview:"+kind.name()).getBytes(StandardCharsets.UTF_8));}
    public static Optional<BuildingKind> building(UUID id) {return Optional.ofNullable(TARGETS.get(id));}
}
