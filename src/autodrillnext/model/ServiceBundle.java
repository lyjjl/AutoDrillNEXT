package autodrillnext.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class ServiceBundle {
    private final String id;
    private final float qout;
    private final PlanCost cost;
    private final Set<String> drillIds;
    private final Set<String> outputOwners;
    private final Map<String, Set<String>> bridgeEndpoints;
    private final Set<String> requiredSupportIds;
    private final Set<String> providedSupportIds;

    private ServiceBundle(
        String id,
        float qout,
        PlanCost cost,
        Set<String> drillIds,
        Set<String> outputOwners,
        Map<String, Set<String>> bridgeEndpoints,
        Set<String> requiredSupportIds,
        Set<String> providedSupportIds
    ) {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("bundle id must not be blank");
        if (!Float.isFinite(qout) || qout < 0f) throw new IllegalArgumentException("bundle qout must be finite and non-negative");
        this.id = id;
        this.qout = qout;
        this.cost = Objects.requireNonNull(cost, "bundle cost");
        this.drillIds = immutableSet(drillIds);
        this.outputOwners = immutableSet(outputOwners);
        LinkedHashMap<String, Set<String>> pairs = new LinkedHashMap<>();
        bridgeEndpoints.forEach((pair, endpoints) -> pairs.put(pair, immutableSet(endpoints)));
        this.bridgeEndpoints = Collections.unmodifiableMap(pairs);
        this.requiredSupportIds = immutableSet(requiredSupportIds);
        this.providedSupportIds = immutableSet(providedSupportIds);
    }

    public static ServiceBundle of(
        String id,
        float qout,
        PlanCost cost,
        Set<String> drillIds,
        Set<String> outputOwners,
        Map<String, Set<String>> bridgeEndpoints,
        Set<String> requiredSupportIds,
        Set<String> providedSupportIds
    ) {
        return new ServiceBundle(id, qout, cost, drillIds, outputOwners, bridgeEndpoints,
            requiredSupportIds, providedSupportIds);
    }
    public ServiceBundle withQout(float value) {
        return new ServiceBundle(id, value, cost, drillIds, outputOwners, bridgeEndpoints,
            requiredSupportIds, providedSupportIds);
    }


    public String id() {
        return id;
    }

    public float qout() {
        return qout;
    }

    public PlanCost cost() {
        return cost;
    }

    public Set<String> drillIds() {
        return drillIds;
    }

    public Set<String> outputOwners() {
        return outputOwners;
    }

    public Map<String, Set<String>> bridgeEndpoints() {
        return bridgeEndpoints;
    }

    public Set<String> requiredSupportIds() {
        return requiredSupportIds;
    }

    public Set<String> providedSupportIds() {
        return providedSupportIds;
    }

    public boolean isDependencyClosed() {
        if (!outputOwners.containsAll(drillIds)) return false;
        for (Set<String> endpoints : bridgeEndpoints.values()) {
            if (endpoints.size() != 2) return false;
        }
        return providedSupportIds.containsAll(requiredSupportIds);
    }

    private static Set<String> immutableSet(Set<String> values) {
        LinkedHashSet<String> copy = new LinkedHashSet<>();
        for (String value : values) {
            if (value == null || value.isBlank()) throw new IllegalArgumentException("bundle dependency id must not be blank");
            copy.add(value);
        }
        return Collections.unmodifiableSet(copy);
    }
}
