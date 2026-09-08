package autodrillnext.compile;

import autodrillnext.capability.spec.CapabilitySnapshot;
import autodrillnext.model.Inventory;
import autodrillnext.world.TerrainSnapshot;

import java.util.Objects;

public record LiveSnapshot(
    TerrainSnapshot terrain,
    Inventory inventory,
    CapabilitySnapshot capabilities,
    boolean infiniteResources
) {
    public LiveSnapshot {
        Objects.requireNonNull(terrain, "terrain");
        Objects.requireNonNull(inventory, "inventory");
        Objects.requireNonNull(capabilities, "capabilities");
    }
}
