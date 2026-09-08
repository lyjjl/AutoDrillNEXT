package autodrillnext.model;

import autodrillnext.capability.spec.CostVector;
import autodrillnext.capability.spec.SupportVariant;
import autodrillnext.world.PlacementFootprint;
import autodrillnext.world.TileKey;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public record DrillCandidate(
    String id,
    ContentId drillId,
    TileKey anchor,
    int rotation,
    PlacementFootprint footprint,
    Set<TileKey> coveredOreCells,
    ProductionEstimate production,
    List<SupportRequirement> mandatorySupport,
    List<SupportVariant> supportVariants,
    CostVector cost,
    Set<String> conflictIds
) {
    public DrillCandidate(
        String id,
        ContentId drillId,
        TileKey anchor,
        int rotation,
        PlacementFootprint footprint,
        Set<TileKey> coveredOreCells,
        ProductionEstimate production,
        List<SupportVariant> supportVariants,
        CostVector cost,
        Set<String> conflictIds
    ) {
        this(id, drillId, anchor, rotation, footprint, coveredOreCells, production,
            List.of(), supportVariants, cost, conflictIds);
    }

    public DrillCandidate {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("candidate id must not be blank");
        Objects.requireNonNull(drillId, "drill id");
        Objects.requireNonNull(anchor, "anchor");
        if (rotation < 0 || rotation > 3) throw new IllegalArgumentException("rotation must be in [0, 3]");
        Objects.requireNonNull(footprint, "footprint");
        coveredOreCells = immutableCopy(coveredOreCells, "covered ore cell");
        Objects.requireNonNull(production, "production");
        mandatorySupport = List.copyOf(mandatorySupport);
        supportVariants = List.copyOf(supportVariants);
        Objects.requireNonNull(cost, "cost");
        conflictIds = immutableCopy(conflictIds, "conflict id");
    }

    public DrillCandidate withConflicts(Set<String> conflicts) {
        return new DrillCandidate(
            id,
            drillId,
            anchor,
            rotation,
            footprint,
            coveredOreCells,
            production,
            mandatorySupport,
            supportVariants,
            cost,
            conflicts
        );
    }

    private static <T> Set<T> immutableCopy(Set<T> values, String label) {
        LinkedHashSet<T> copy = new LinkedHashSet<>();
        for (T value : values) copy.add(Objects.requireNonNull(value, label));
        return Collections.unmodifiableSet(copy);
    }
}
