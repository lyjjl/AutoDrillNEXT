package autodrillnext.model;

import autodrillnext.capability.spec.CostVector;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class PlanGraph {
    private final List<PlanNode> nodes;
    private final List<PlanEdge> edges;
    private final String sinkId;
    private final List<PlanGraph> alternatives;
    private final List<PlannerDiagnostic> diagnostics;
    private final FlowAssignment flow;
    private final List<TransportPlacement> placements;
    private final autodrillnext.world.ExitAnchor exit;
    private final PlanCost cost;

    private PlanGraph(
        List<PlanNode> nodes,
        List<PlanEdge> edges,
        String sinkId,
        List<PlanGraph> alternatives,
        List<PlannerDiagnostic> diagnostics,
        FlowAssignment flow,
        List<TransportPlacement> placements,
        autodrillnext.world.ExitAnchor exit,
        PlanCost cost
    ) {
        this.nodes = List.copyOf(nodes);
        this.edges = List.copyOf(edges);
        this.sinkId = Objects.requireNonNull(sinkId, "sink id");
        this.alternatives = List.copyOf(alternatives);
        this.diagnostics = List.copyOf(diagnostics);
        this.flow = flow;
        this.placements = List.copyOf(placements);
        this.exit = exit;
        validate();
        this.cost = cost == null ? calculateCost() : cost;
    }

    public static PlanGraph of(List<PlanNode> nodes, List<PlanEdge> edges, String sinkId) {
        return new PlanGraph(nodes, edges, sinkId, List.of(), List.of(), null, List.of(), null, null);
    }

    public PlanGraph withAlternatives(List<PlanGraph> values) {
        return new PlanGraph(nodes, edges, sinkId, values, diagnostics, flow, placements, exit, cost);
    }

    public PlanGraph withDiagnostics(List<PlannerDiagnostic> values) {
        return new PlanGraph(nodes, edges, sinkId, alternatives, values, flow, placements, exit, cost);
    }

    public PlanGraph withFlow(FlowAssignment assignment) {
        Map<String, Float> flows = assignment.flows();
        ArrayList<PlanEdge> assigned = new ArrayList<>();
        for (PlanEdge edge : edges) assigned.add(edge.withAssignedFlow(flows.getOrDefault(edge.id(), 0f)));
        return new PlanGraph(nodes, assigned, sinkId, alternatives, diagnostics, assignment, placements, exit, cost);
    }

    public PlanGraph withTransportHeadroom(float headroom) {
        if (!Float.isFinite(headroom) || headroom < 1f) {
            throw new IllegalArgumentException("transport headroom must be at least one");
        }
        ArrayList<PlanEdge> adjusted = new ArrayList<>();
        for (PlanEdge edge : edges) {
            if (edge.kind() == EdgeKind.GROUND_EDGE || edge.kind() == EdgeKind.BRIDGE_EDGE) {
                adjusted.add(edge.withUsableCapacity(edge.nominalCapacity() / headroom));
            } else {
                adjusted.add(edge);
            }
        }
        ArrayList<PlanGraph> adjustedAlternatives = new ArrayList<>();
        for (PlanGraph alternative : alternatives) adjustedAlternatives.add(alternative.withTransportHeadroom(headroom));
        return new PlanGraph(nodes, adjusted, sinkId, adjustedAlternatives, diagnostics, null, placements, exit, cost);
    }

    public PlanGraph withPlacements(List<TransportPlacement> values, autodrillnext.world.ExitAnchor anchor) {
        return new PlanGraph(nodes, edges, sinkId, alternatives, diagnostics, flow, values, anchor, null);
    }

    public List<TransportPlacement> placements() {
        return placements;
    }

    public autodrillnext.world.ExitAnchor exit() {
        return exit;
    }

    public List<PlanNode> nodes() {
        return nodes;
    }

    public List<PlanEdge> edges() {
        return edges;
    }

    public String sinkId() {
        return sinkId;
    }

    public List<PlanGraph> alternatives() {
        return alternatives;
    }

    public List<PlannerDiagnostic> diagnostics() {
        return diagnostics;
    }

    public FlowAssignment flow() {
        return flow;
    }

    public float qOut() {
        return flow == null ? 0f : flow.qOut();
    }

    public PlanCost cost() {
        return cost;
    }

    private PlanCost calculateCost() {
        CostVector materials = CostVector.empty();
        int space = 0;
        int complexity = 0;
        if (!placements.isEmpty()) {
            for (TransportPlacement placement : placements) materials = materials.plus(placement.spec().cost());
            return new PlanCost(materials, placements.size(), placements.size());
        }
        for (PlanEdge edge : edges) {
            if (edge.kind() == EdgeKind.SOURCE_EDGE || edge.kind() == EdgeKind.SINK_EDGE) continue;
            int blocks = edge.kind() == EdgeKind.BRIDGE_EDGE
                ? (edge.footprint().isEmpty() ? 2 : edge.footprint().size()) : 1;
            for (int block = 0; block < blocks; block++) materials = materials.plus(edge.cost());
            space += edge.footprint().size();
            complexity += blocks;
        }
        return new PlanCost(materials, space, complexity);
    }

    public List<String> transportIds() {
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        for (PlanEdge edge : edges) {
            if (!edge.transportId().isBlank()) ids.add(edge.transportId());
        }
        return List.copyOf(ids);
    }

    public PlanNode node(String id) {
        for (PlanNode node : nodes) if (node.id().equals(id)) return node;
        throw new IllegalArgumentException("unknown plan node: " + id);
    }

    private void validate() {
        Set<String> nodeIds = new LinkedHashSet<>();
        for (PlanNode node : nodes) {
            if (!nodeIds.add(node.id())) throw new IllegalArgumentException("duplicate plan node: " + node.id());
        }
        Set<String> edgeIds = new LinkedHashSet<>();
        for (PlanEdge edge : edges) {
            if (!edgeIds.add(edge.id())) throw new IllegalArgumentException("duplicate plan edge: " + edge.id());
            if (!nodeIds.contains(edge.from()) || !nodeIds.contains(edge.to())) {
                throw new IllegalArgumentException("edge endpoint is not in graph: " + edge.id());
            }
        }
        node(sinkId);
    }
}
