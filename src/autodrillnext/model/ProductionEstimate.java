package autodrillnext.model;

import java.util.Objects;

public record ProductionEstimate(ContentId output, float perSecond, float transportPerSecond) {
    public ProductionEstimate(ContentId output, float perSecond) {
        this(output, perSecond, perSecond);
    }

    public ProductionEstimate {
        Objects.requireNonNull(output, "output");
        if (!Float.isFinite(perSecond) || perSecond < 0f) {
            throw new IllegalArgumentException("production must be finite and non-negative");
        }
        if (!Float.isFinite(transportPerSecond) || transportPerSecond < perSecond) {
            throw new IllegalArgumentException("transport production must cover target production");
        }
    }
}
