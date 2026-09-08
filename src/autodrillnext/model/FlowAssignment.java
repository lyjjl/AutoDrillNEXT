package autodrillnext.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public record FlowAssignment(
    float qOut,
    Map<String, Float> flows,
    Map<String, Float> utilizations,
    boolean capacitySafe
) {
    public FlowAssignment {
        if (!Float.isFinite(qOut) || qOut < 0f) throw new IllegalArgumentException("invalid qout");
        flows = immutableFloatMap(flows);
        utilizations = immutableFloatMap(utilizations);
    }

    public float flow(String edgeId) {
        return flows.getOrDefault(edgeId, 0f);
    }

    public float utilization(String edgeId) {
        return utilizations.getOrDefault(edgeId, 0f);
    }

    private static Map<String, Float> immutableFloatMap(Map<String, Float> values) {
        LinkedHashMap<String, Float> copy = new LinkedHashMap<>();
        values.forEach((key, value) -> {
            Objects.requireNonNull(key, "flow key");
            Objects.requireNonNull(value, "flow value");
            if (!Float.isFinite(value) || value < 0f) throw new IllegalArgumentException("invalid flow value");
            copy.put(key, value);
        });
        return Collections.unmodifiableMap(copy);
    }
}
