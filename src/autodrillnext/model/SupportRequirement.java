package autodrillnext.model;

import java.util.List;
import java.util.Objects;

public record SupportRequirement(
    Kind kind,
    List<LiquidId> allowedLiquids,
    float demandPerSecond,
    boolean mandatory
) {
    public SupportRequirement {
        Objects.requireNonNull(kind, "kind");
        allowedLiquids = List.copyOf(allowedLiquids);
        if (!Float.isFinite(demandPerSecond) || demandPerSecond < 0f) {
            throw new IllegalArgumentException("demand must be finite and non-negative");
        }
    }

    public enum Kind {
        POWER,
        LIQUID,
        BOOSTER
    }
}
