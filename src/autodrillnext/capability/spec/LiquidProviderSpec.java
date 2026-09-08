package autodrillnext.capability.spec;

import autodrillnext.model.ContentId;
import autodrillnext.model.LiquidId;

import java.util.Objects;

public record LiquidProviderSpec(
    ContentId id,
    int size,
    LiquidId result,
    float supplyPerSecond,
    CostVector cost
) implements CapabilitySpec {
    public LiquidProviderSpec {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(cost, "cost");
        if (size < 1 || !Float.isFinite(supplyPerSecond) || supplyPerSecond < 0f) {
            throw new IllegalArgumentException("invalid liquid provider");
        }
    }
}
