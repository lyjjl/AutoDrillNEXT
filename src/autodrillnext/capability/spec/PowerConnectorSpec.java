package autodrillnext.capability.spec;

import autodrillnext.model.ContentId;

import java.util.Objects;

public record PowerConnectorSpec(
    ContentId id,
    int size,
    float range,
    int maxLinks,
    boolean beam,
    CostVector cost
) implements CapabilitySpec {
    public PowerConnectorSpec {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(cost, "cost");
        if (size < 1 || range < 0f || maxLinks < 0) throw new IllegalArgumentException("invalid power connector");
    }
}
