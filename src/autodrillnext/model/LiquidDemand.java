package autodrillnext.model;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

public record LiquidDemand(List<LiquidId> allowedLiquids, float perSecond, boolean mandatory) {
    public LiquidDemand {
        if (allowedLiquids.isEmpty()) throw new IllegalArgumentException("liquid demand needs an allowed liquid");
        LinkedHashSet<LiquidId> unique = new LinkedHashSet<>();
        for (LiquidId liquid : allowedLiquids) unique.add(Objects.requireNonNull(liquid, "allowed liquid"));
        allowedLiquids = List.copyOf(unique);
        if (!Float.isFinite(perSecond) || perSecond < 0f) {
            throw new IllegalArgumentException("liquid demand must be finite and non-negative");
        }
    }
}
