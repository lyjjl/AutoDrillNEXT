package autodrillnext.capability.adapter;

import autodrillnext.capability.spec.ResourceValuation;
import mindustry.type.Liquid;

import java.util.List;
import java.util.Objects;

public record CapabilityContext(float buildCostMultiplier, List<Liquid> liquids, boolean infiniteResources,
                                ResourceValuation resourceValuation) {
    public CapabilityContext {
        if (!Float.isFinite(buildCostMultiplier) || buildCostMultiplier < 0f) {
            throw new IllegalArgumentException("build cost multiplier must be finite and non-negative");
        }
        liquids = List.copyOf(liquids);
        Objects.requireNonNull(resourceValuation, "resource valuation");
    }
}
