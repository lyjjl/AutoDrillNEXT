package autodrillnext.capability.spec;

import autodrillnext.model.ContentId;

import java.util.Objects;

public record CapabilityCandidate(
    ContentId id,
    CapabilityKind kind,
    boolean supported,
    boolean unlocked,
    boolean placeable,
    boolean overPlacementLimit,
    CostVector cost,
    String adapterId,
    CapabilitySpec spec
) {
    public CapabilityCandidate(
        ContentId id,
        CapabilityKind kind,
        boolean supported,
        boolean unlocked,
        boolean placeable,
        boolean overPlacementLimit,
        CostVector cost
    ) {
        this(id, kind, supported, unlocked, placeable, overPlacementLimit, cost, "", null);
    }

    public CapabilityCandidate {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(cost, "cost");
        adapterId = adapterId == null ? "" : adapterId;
    }
}
