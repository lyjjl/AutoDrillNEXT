package autodrillnext.capability.spec;

import autodrillnext.model.ContentId;
import autodrillnext.model.SupportRequirement;

import java.util.Objects;

public record SupportVariant(
    ContentId id,
    SupportRequirement requirement,
    float productionMultiplier
) {
    public SupportVariant {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(requirement, "requirement");
        if (!Float.isFinite(productionMultiplier) || productionMultiplier <= 0f) {
            throw new IllegalArgumentException("production multiplier must be positive");
        }
    }
}
