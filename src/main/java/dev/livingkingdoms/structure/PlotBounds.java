package dev.livingkingdoms.structure;

import dev.livingkingdoms.settlement.domain.Territory;

/** Horizontal footprint, inclusive. Roofs must stay inside the exported footprint. */
public record PlotBounds(int minX, int minZ, int maxX, int maxZ) {
    public PlotBounds {
        if (maxX < minX || maxZ < minZ) throw new IllegalArgumentException("Inverted plot");
    }

    public boolean contains(int x, int z) {
        return x >= minX && x <= maxX && z >= minZ && z <= maxZ;
    }

    public boolean conflicts(PlotBounds other, int spacing) {
        return (long) minX <= (long) other.maxX + spacing && (long) maxX + spacing >= other.minX
                && (long) minZ <= (long) other.maxZ + spacing && (long) maxZ + spacing >= other.minZ;
    }

    public boolean inside(Territory territory) {
        return territory.contains(territory.dimension(), minX, minZ)
                && territory.contains(territory.dimension(), maxX, minZ)
                && territory.contains(territory.dimension(), minX, maxZ)
                && territory.contains(territory.dimension(), maxX, maxZ);
    }
}
