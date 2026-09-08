package autodrillnext.model;

import autodrillnext.capability.spec.CostVector;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

public record MiningLayout(
    List<DrillCandidate> candidates,
    CostVector cost,
    float productionPerSecond
) {
    public MiningLayout {
        candidates = List.copyOf(candidates);
        if (cost == null) throw new NullPointerException("cost");
        if (!Float.isFinite(productionPerSecond) || productionPerSecond < 0f) {
            throw new IllegalArgumentException("layout production must be finite and non-negative");
        }
    }

    public static MiningLayout of(List<DrillCandidate> candidates) {
        CostVector cost = CostVector.empty();
        float production = 0f;
        for (DrillCandidate candidate : candidates) {
            cost = cost.plus(candidate.cost());
            production += candidate.production().perSecond();
        }
        return new MiningLayout(candidates, cost, production);
    }

    public boolean hasNoFootprintOverlap() {
        Set<autodrillnext.world.TileKey> occupied = new HashSet<>();
        for (DrillCandidate candidate : candidates) {
            for (autodrillnext.world.TileKey tile : candidate.footprint().tiles()) {
                if (!occupied.add(tile)) return false;
            }
        }
        return true;
    }
}
