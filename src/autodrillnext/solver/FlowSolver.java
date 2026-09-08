package autodrillnext.solver;

import autodrillnext.model.FlowAssignment;
import autodrillnext.model.PlanEdge;
import autodrillnext.model.PlanGraph;
import autodrillnext.model.SearchBudget;
import autodrillnext.model.PlanNode;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class FlowSolver {
    private static final String SUPER_SOURCE = "__source__";
    private static final String SUPER_SINK = "__sink__";
    private static final float EPSILON = 0.0001f;

    public FlowAssignment assign(PlanGraph graph) {
        return assign(graph, SearchBudget.unlimited());
    }

    public FlowAssignment assign(PlanGraph graph, SearchBudget budget) {
        budget.checkpoint();
        ArrayList<ResidualArc> arcs = new ArrayList<>();
        Map<String, List<Integer>> adjacency = new HashMap<>();
        Map<String, Float> edgeFlows = new LinkedHashMap<>();
        for (PlanEdge edge : graph.edges()) {
            edgeFlows.put(edge.id(), 0f);
            addPair(arcs, adjacency, edge.from(), edge.to(), edge.usableCapacity(), edge.id(), 1);
        }
        for (PlanNode node : graph.nodes()) {
            if (node.kind() == PlanNode.Kind.SOURCE && node.productionPerSecond() > EPSILON) {
                addPair(arcs, adjacency, SUPER_SOURCE, node.id(), node.productionPerSecond(), null, 0);
            }
        }
        addPair(arcs, adjacency, graph.sinkId(), SUPER_SINK, Float.MAX_VALUE / 4f, null, 0);

        float qOut = 0f;
        while (true) {
            budget.checkpoint();
            if (Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException();
            Path path = findPath(arcs, adjacency, edgeFlows, budget);
            if (path == null || path.amount <= EPSILON) break;
            qOut += path.amount;
            for (int arcIndex : path.arcIndexes) {
                ResidualArc arc = arcs.get(arcIndex);
                if (arc.edgeId != null) {
                    float next = edgeFlows.get(arc.edgeId) + arc.direction * path.amount;
                    edgeFlows.put(arc.edgeId, Math.max(0f, next));
                }
                arc.remaining -= path.amount;
                arcs.get(arc.reverseIndex).remaining += path.amount;
            }
        }

        LinkedHashMap<String, Float> utilizations = new LinkedHashMap<>();
        boolean safe = true;
        for (PlanEdge edge : graph.edges()) {
            float flow = edgeFlows.getOrDefault(edge.id(), 0f);
            float utilization = edge.usableCapacity() <= EPSILON ? 0f : flow / edge.usableCapacity();
            utilizations.put(edge.id(), utilization);
            if (flow > edge.usableCapacity() + EPSILON) safe = false;
        }
        return new FlowAssignment(qOut, edgeFlows, utilizations, safe);
    }

    private Path findPath(
        List<ResidualArc> arcs,
        Map<String, List<Integer>> adjacency,
        Map<String, Float> edgeFlows,
        SearchBudget budget
    ) {
        Map<String, Integer> previousArc = new HashMap<>();
        ArrayDeque<String> pending = new ArrayDeque<>();
        pending.add(SUPER_SOURCE);
        previousArc.put(SUPER_SOURCE, -1);
        while (!pending.isEmpty() && !previousArc.containsKey(SUPER_SINK)) {
            budget.checkpoint();
            String node = pending.remove();
            for (int arcIndex : adjacency.getOrDefault(node, List.of())) {
                ResidualArc arc = arcs.get(arcIndex);
                if (arc.remaining <= 0f || previousArc.containsKey(arc.to)) continue;
                previousArc.put(arc.to, arcIndex);
                pending.add(arc.to);
                if (arc.to.equals(SUPER_SINK)) break;
            }
        }
        if (!previousArc.containsKey(SUPER_SINK)) return null;

        ArrayList<Integer> path = new ArrayList<>();
        float amount = Float.MAX_VALUE / 4f;
        String node = SUPER_SINK;
        while (!node.equals(SUPER_SOURCE)) {
            int arcIndex = previousArc.get(node);
            ResidualArc arc = arcs.get(arcIndex);
            path.add(0, arcIndex);
            amount = Math.min(amount, arc.remaining);
            node = arc.from;
        }
        return new Path(path, amount);
    }

    private void addPair(
        List<ResidualArc> arcs,
        Map<String, List<Integer>> adjacency,
        String from,
        String to,
        float capacity,
        String edgeId,
        int direction
    ) {
        int forward = arcs.size();
        int reverse = forward + 1;
        arcs.add(new ResidualArc(from, to, capacity, edgeId, direction, reverse));
        arcs.add(new ResidualArc(to, from, 0f, edgeId, -direction, forward));
        adjacency.computeIfAbsent(from, ignored -> new ArrayList<>()).add(forward);
        adjacency.computeIfAbsent(to, ignored -> new ArrayList<>()).add(reverse);
    }

    private static final class ResidualArc {
        private final String from;
        private final String to;
        private float remaining;
        private final String edgeId;
        private final int direction;
        private final int reverseIndex;

        private ResidualArc(
            String from,
            String to,
            float remaining,
            String edgeId,
            int direction,
            int reverseIndex
        ) {
            this.from = from;
            this.to = to;
            this.remaining = remaining;
            this.edgeId = edgeId;
            this.direction = direction;
            this.reverseIndex = reverseIndex;
        }
    }

    private record Path(List<Integer> arcIndexes, float amount) {
    }
}
