package autodrillnext.model;

import autodrillnext.capability.spec.CostVector;
import autodrillnext.world.TileKey;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

public record PlanEdge(
    String id,
    EdgeKind kind,
    String from,
    String to,
    float nominalCapacity,
    float usableCapacity,
    float assignedFlow,
    CostVector cost,
    String transportId,
    Set<TileKey> footprint,
    Set<String> dependencies
) {
    public PlanEdge {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("edge id must not be blank");
        Objects.requireNonNull(kind, "edge kind");
        if (from == null || from.isBlank() || to == null || to.isBlank()) {
            throw new IllegalArgumentException("edge endpoints must not be blank");
        }
        if (!Float.isFinite(nominalCapacity) || nominalCapacity < 0f
            || !Float.isFinite(usableCapacity) || usableCapacity < 0f
            || !Float.isFinite(assignedFlow) || assignedFlow < 0f
            || usableCapacity > nominalCapacity + 0.0001f) {
            throw new IllegalArgumentException("invalid edge capacity");
        }
        Objects.requireNonNull(cost, "edge cost");
        transportId = transportId == null ? "" : transportId;
        footprint = immutableTiles(footprint);
        dependencies = immutableStrings(dependencies);
    }

    public static PlanEdge source(String id, String from, String to, float capacity) {
        return new PlanEdge(id, EdgeKind.SOURCE_EDGE, from, to, capacity, capacity, 0f,
            CostVector.empty(), "", Set.of(), Set.of());
    }

    public static PlanEdge sink(String id, String from, String to, float capacity) {
        return new PlanEdge(id, EdgeKind.SINK_EDGE, from, to, capacity, capacity, 0f,
            CostVector.empty(), "", Set.of(), Set.of());
    }

    public static PlanEdge transport(
        String id,
        EdgeKind kind,
        String from,
        String to,
        float nominalCapacity,
        float usableCapacity,
        CostVector cost,
        String transportId
    ) {
        if (kind != EdgeKind.GROUND_EDGE && kind != EdgeKind.BRIDGE_EDGE && kind != EdgeKind.EXISTING_EDGE) {
            throw new IllegalArgumentException("transport edge kind required");
        }
        return new PlanEdge(id, kind, from, to, nominalCapacity, usableCapacity, 0f,
            cost, transportId, Set.of(), Set.of());
    }

    public PlanEdge withAssignedFlow(float flow) {
        return new PlanEdge(id, kind, from, to, nominalCapacity, usableCapacity, flow,
            cost, transportId, footprint, dependencies);
    }

    public PlanEdge withUsableCapacity(float capacity) {
        return new PlanEdge(id, kind, from, to, nominalCapacity, capacity, assignedFlow,
            cost, transportId, footprint, dependencies);
    }

    public PlanEdge withFootprint(Set<TileKey> tiles) {
        return new PlanEdge(id, kind, from, to, nominalCapacity, usableCapacity, assignedFlow,
            cost, transportId, tiles, dependencies);
    }

    public PlanEdge withDependencies(Set<String> values) {
        return new PlanEdge(id, kind, from, to, nominalCapacity, usableCapacity, assignedFlow,
            cost, transportId, footprint, values);
    }

    private static Set<TileKey> immutableTiles(Set<TileKey> values) {
        LinkedHashSet<TileKey> copy = new LinkedHashSet<>();
        for (TileKey value : values) copy.add(Objects.requireNonNull(value, "edge footprint tile"));
        return Collections.unmodifiableSet(copy);
    }

    private static Set<String> immutableStrings(Set<String> values) {
        LinkedHashSet<String> copy = new LinkedHashSet<>();
        for (String value : values) copy.add(Objects.requireNonNull(value, "edge dependency"));
        return Collections.unmodifiableSet(copy);
    }
}
