package autodrillnext.simulation;

import autodrillnext.model.EdgeKind;
import autodrillnext.model.PlanEdge;
import autodrillnext.model.PlanGraph;
import autodrillnext.model.SearchBudget;
import autodrillnext.model.PlanNode;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Pure, bounded item-flow model; it never reads Mindustry runtime state. */
public final class ItemNetworkSimulator {
    private static final int TICKS_PER_SECOND = 60;
    private static final int WARMUP_TICKS = 600;
    private static final int MEASUREMENT_TICKS = 600;
    private static final float NODE_CAPACITY = 1f;
    private static final float EPSILON = 0.0001f;

    public SimulationResult simulate(PlanGraph graph) {
        return simulate(graph, SimulationFidelity.BOUNDED_MODEL, SearchBudget.unlimited());
    }

    public SimulationResult simulate(PlanGraph graph, SimulationFidelity fidelity) {
        return simulate(graph, fidelity, SearchBudget.unlimited());
    }

    public SimulationResult simulate(PlanGraph graph, SimulationFidelity fidelity, SearchBudget budget) {
        budget.checkpoint();
        Objects.requireNonNull(fidelity, "fidelity");
        if (fidelity == SimulationFidelity.UNSUPPORTED) return SimulationResult.unsupported();
        Objects.requireNonNull(graph, "graph");
        Map<String, NodeState> nodes = nodes(graph.nodes());
        List<EdgeState> edges = edges(graph.edges());
        Map<String, List<EdgeState>> outgoing = outgoing(edges);

        float produced = 0f;
        float drillBlocked = 0f;
        float output = 0f;
        float measuredOutput = 0f;
        float measuredOccupancy = 0f;
        int occupancySamples = 0;
        int transportSamples = 0;
        int transportBlocked = 0;
        ArrayList<Float> exitTicks = new ArrayList<>(MEASUREMENT_TICKS);

        int totalTicks = WARMUP_TICKS + MEASUREMENT_TICKS;
        for (int tick = 0; tick < totalTicks; tick++) {
            budget.checkpoint();
            float exited = drainEdges(edges, nodes);
            for (PlanNode source : graph.nodes()) {
                if (source.kind() != PlanNode.Kind.SOURCE) continue;
                float generated = source.productionPerSecond() / TICKS_PER_SECOND;
                produced += generated;
                NodeState state = nodes.get(source.id());
                float accepted = Math.min(generated, Math.max(0f, state.capacity - state.items));
                state.items += accepted;
                drillBlocked += generated - accepted;
            }
            fillEdges(nodes, outgoing);

            output += exited;
            if (tick >= WARMUP_TICKS) {
                measuredOutput += exited;
                exitTicks.add(exited);
                measuredOccupancy += occupancy(edges, nodes);
                occupancySamples++;
                for (EdgeState edge : edges) {
                    if (!edge.transport()) continue;
                    transportSamples++;
                    if (edge.items >= edge.capacity - EPSILON) transportBlocked++;
                }
            }
        }

        float durationSeconds = totalTicks / (float) TICKS_PER_SECOND;
        float measuredSeconds = MEASUREMENT_TICKS / (float) TICKS_PER_SECOND;
        return new SimulationResult(
            fidelity,
            output / durationSeconds,
            measuredOutput / measuredSeconds,
            steadyStateDetected(exitTicks),
            ratio(drillBlocked, produced),
            ratio(transportBlocked, transportSamples),
            burstiness(exitTicks),
            occupancySamples == 0 ? 0f : measuredOccupancy / occupancySamples,
            totalTicks
        );
    }

    private Map<String, NodeState> nodes(List<PlanNode> definitions) {
        HashMap<String, NodeState> result = new HashMap<>();
        for (PlanNode definition : definitions) {
            float capacity = definition.kind() == PlanNode.Kind.SINK ? Float.MAX_VALUE : NODE_CAPACITY;
            result.put(definition.id(), new NodeState(capacity, definition.kind() == PlanNode.Kind.SINK));
        }
        return result;
    }

    private List<EdgeState> edges(List<PlanEdge> definitions) {
        ArrayList<EdgeState> result = new ArrayList<>();
        for (PlanEdge definition : definitions) result.add(new EdgeState(definition));
        result.sort(Comparator.comparing(edge -> edge.definition.id()));
        return result;
    }

    private Map<String, List<EdgeState>> outgoing(List<EdgeState> edges) {
        HashMap<String, List<EdgeState>> result = new HashMap<>();
        for (EdgeState edge : edges) {
            result.computeIfAbsent(edge.definition.from(), ignored -> new ArrayList<>()).add(edge);
        }
        return result;
    }

    private float drainEdges(List<EdgeState> edges, Map<String, NodeState> nodes) {
        float exited = 0f;
        for (EdgeState edge : edges) {
            NodeState target = nodes.get(edge.definition.to());
            float accepted = Math.min(edge.items, Math.max(0f, target.capacity - target.items));
            edge.items -= accepted;
            target.items += accepted;
            if (target.sink) {
                exited += target.items;
                target.items = 0f;
            }
        }
        return exited;
    }

    private void fillEdges(Map<String, NodeState> nodes, Map<String, List<EdgeState>> outgoing) {
        for (Map.Entry<String, List<EdgeState>> entry : outgoing.entrySet()) {
            NodeState source = nodes.get(entry.getKey());
            for (EdgeState edge : entry.getValue()) {
                float transferable = Math.min(source.items, edge.perTickCapacity());
                float accepted = Math.min(transferable, Math.max(0f, edge.capacity - edge.items));
                source.items -= accepted;
                edge.items += accepted;
            }
        }
    }

    private float occupancy(List<EdgeState> edges, Map<String, NodeState> nodes) {
        float total = 0f;
        int count = 0;
        for (EdgeState edge : edges) {
            if (!edge.transport()) continue;
            total += ratio(edge.items, edge.capacity);
            count++;
        }
        for (NodeState node : nodes.values()) {
            if (node.capacity == Float.MAX_VALUE) continue;
            total += ratio(node.items, node.capacity);
            count++;
        }
        return count == 0 ? 0f : total / count;
    }

    private float burstiness(List<Float> values) {
        if (values.isEmpty()) return 0f;
        float mean = 0f;
        for (float value : values) mean += value;
        mean /= values.size();
        if (mean <= EPSILON) return 0f;
        float variance = 0f;
        for (float value : values) {
            float delta = value - mean;
            variance += delta * delta;
        }
        return (float) Math.sqrt(variance / values.size()) / mean;
    }

    private float ratio(float numerator, float denominator) {
        if (denominator <= EPSILON) return 0f;
        return Math.min(1f, Math.max(0f, numerator / denominator));
    }

    private boolean steadyStateDetected(List<Float> values) {
        int windows = 5;
        int windowSize = values.size() / windows;
        if (windowSize == 0) return false;
        float min = Float.MAX_VALUE;
        float max = 0f;
        for (int window = 0; window < windows; window++) {
            float total = 0f;
            for (int tick = 0; tick < windowSize; tick++) total += values.get(window * windowSize + tick);
            float rate = total * TICKS_PER_SECOND / windowSize;
            min = Math.min(min, rate);
            max = Math.max(max, rate);
        }
        return max - min <= Math.max(0.01f, max * 0.01f);
    }

    private static final class NodeState {
        private final float capacity;
        private final boolean sink;

        private float items;

        private NodeState(float capacity, boolean sink) {
            this.capacity = capacity;
            this.sink = sink;
        }
    }

    private static final class EdgeState {
        private final PlanEdge definition;
        private final float capacity;
        private float items;

        private EdgeState(PlanEdge definition) {
            this.definition = definition;
            this.capacity = definition.kind() == EdgeKind.SINK_EDGE || definition.kind() == EdgeKind.EXISTING_EDGE
                ? Float.MAX_VALUE
                : 1f;
        }

        private boolean transport() {
            return definition.kind() == EdgeKind.GROUND_EDGE || definition.kind() == EdgeKind.BRIDGE_EDGE;
        }

        private float perTickCapacity() {
            return definition.usableCapacity() / TICKS_PER_SECOND;
        }
    }
}
