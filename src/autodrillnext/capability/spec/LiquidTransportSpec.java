package autodrillnext.capability.spec;

import autodrillnext.model.ContentId;

import java.util.Objects;

public record LiquidTransportSpec(
    ContentId id,
    int size,
    float liquidCapacity,
    float pressure,
    int range,
    boolean bridge,
    CostVector cost
) implements CapabilitySpec {
    public LiquidTransportSpec {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(cost, "cost");
        if (size < 1 || range < 0) throw new IllegalArgumentException("invalid liquid transport dimensions");
        if (!Float.isFinite(liquidCapacity) || liquidCapacity < 0f) {
            throw new IllegalArgumentException("liquid capacity must be finite and non-negative");
        }
        if (!Float.isFinite(pressure) || pressure < 0f) {
            throw new IllegalArgumentException("pressure must be finite and non-negative");
        }
    }
}
