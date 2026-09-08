package autodrillnext.capability.spec;

import autodrillnext.model.ContentId;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

public final class CapabilityDescriptor {
    private final ContentId id;
    private final CapabilityKind kind;
    private final EnumSet<CapabilityState> states;
    private final Set<String> reasons;
    private final CostVector cost;
    private final String adapterId;
    private final CapabilitySpec spec;

    public CapabilityDescriptor(
        ContentId id,
        CapabilityKind kind,
        Set<CapabilityState> states,
        Set<String> reasons,
        CostVector cost
    ) {
        this(id, kind, states, reasons, cost, "", null);
    }

    public CapabilityDescriptor(
        ContentId id,
        CapabilityKind kind,
        Set<CapabilityState> states,
        Set<String> reasons,
        CostVector cost,
        String adapterId,
        CapabilitySpec spec
    ) {
        this.id = Objects.requireNonNull(id, "id");
        this.kind = Objects.requireNonNull(kind, "kind");
        this.states = states.isEmpty()
            ? EnumSet.noneOf(CapabilityState.class)
            : EnumSet.copyOf(states);
        this.reasons = Set.copyOf(reasons);
        this.cost = Objects.requireNonNull(cost, "cost");
        this.adapterId = adapterId == null ? "" : adapterId;
        this.spec = spec;
    }

    public ContentId id() {
        return id;
    }

    public CapabilityKind kind() {
        return kind;
    }

    public Set<CapabilityState> states() {
        return Collections.unmodifiableSet(states);
    }

    public Set<String> reasons() {
        return reasons;
    }

    public CostVector cost() {
        return cost;
    }

    public String adapterId() {
        return adapterId;
    }

    public CapabilitySpec spec() {
        return spec;
    }

    public boolean selectable() {
        return states.contains(CapabilityState.AFFORDABLE_NOW);
    }
}
