package autodrillnext.solver;

import autodrillnext.capability.spec.*;
import autodrillnext.compile.TransportGeometry;
import autodrillnext.compile.PhysicalTransportGraphBuilder;
import autodrillnext.model.*;
import autodrillnext.world.*;

import java.util.*;
import java.util.function.Consumer;

import static autodrillnext.compile.TransportGeometry.*;

public final class ItemRouteSolver {
    private static final float FLOW_EPSILON = 0.0001f;
    private static final int MAX_DOWNGRADE_ATTEMPTS = 64;
    private static final int MAX_DOWNGRADE_CANDIDATES = 4;
    private static final int MAX_RETAINED_CANDIDATES = 12;
    private static final PhysicalTransportGraphBuilder PHYSICAL_GRAPHS =
        new PhysicalTransportGraphBuilder();
    private static final int UNREACHABLE = Integer.MAX_VALUE;
    private static final Comparator<PlanGraph> PLAN_ORDER = Comparator
        .comparingLong((PlanGraph graph) -> ObjectiveValue.outputRank(graph.qOut())).reversed()
        .thenComparing(PlanGraph::cost);
    private static final Comparator<SearchState> MATERIAL_ORDER = Comparator.comparingDouble(SearchState::estimatedMaterials)
        .thenComparingInt(SearchState::estimatedTiles).thenComparingInt(SearchState::remainingDistance)
        .thenComparing(state -> state.placement.tile())
        .thenComparing(state -> state.placement.spec().id().value())
        .thenComparingInt(state -> state.placement.rotation());
    private static final Comparator<SearchState> COMPACT_ORDER = Comparator.comparingInt(SearchState::estimatedTiles)
        .thenComparingDouble(SearchState::estimatedMaterials).thenComparingInt(SearchState::remainingDistance)
        .thenComparing(state -> state.placement.tile())
        .thenComparing(state -> state.placement.spec().id().value())
        .thenComparingInt(state -> state.placement.rotation());

    public PlanGraph route(MiningLayout layout, ExitAnchor sink, TerrainSnapshot terrain,
                           CapabilitySnapshot capabilities, ExistingNetwork existing, Set<TileKey> reserved,
                           boolean allowMixedTransport, float transportHeadroom) {
        return route(layout, sink, terrain, capabilities, existing, reserved, allowMixedTransport,
            transportHeadroom, null, SearchBudget.unlimited());
    }

    public PlanGraph route(MiningLayout layout, ExitAnchor sink, TerrainSnapshot terrain,
                           CapabilitySnapshot capabilities, ExistingNetwork existing, Set<TileKey> reserved,
                           boolean allowMixedTransport, float transportHeadroom, Consumer<RoutingProgress> observer) {
        return route(layout, sink, terrain, capabilities, existing, reserved, allowMixedTransport,
            transportHeadroom, observer, SearchBudget.unlimited());
    }

    public PlanGraph route(MiningLayout layout, ExitAnchor sink, TerrainSnapshot terrain,
                           CapabilitySnapshot capabilities, ExistingNetwork existing, Set<TileKey> reserved,
                           boolean allowMixedTransport, float transportHeadroom,
                           Consumer<RoutingProgress> observer, SearchBudget budget) {
        budget.checkpoint();
        Objects.requireNonNull(layout);
        Objects.requireNonNull(sink);
        Objects.requireNonNull(terrain);
        Objects.requireNonNull(existing);
        if (!Float.isFinite(transportHeadroom) || transportHeadroom < 1f) {
            throw new IllegalArgumentException("transport headroom must be at least one");
        }
        List<ItemTransportSpec> transports = capabilities.descriptors().values().stream()
            .filter(d -> d.kind() == CapabilityKind.ITEM_TRANSPORT && d.states().contains(CapabilityState.AVAILABLE_NOW))
            .map(CapabilityDescriptor::spec).filter(ItemTransportSpec.class::isInstance).map(ItemTransportSpec.class::cast)
            .filter(spec -> spec.supportedGeometry() && spec.capacityPerSecond() > 0f)
            .sorted(Comparator.comparing(spec -> spec.id().value())).toList();
        LinkedHashSet<TileKey> blocked = new LinkedHashSet<>(reserved);
        blocked.addAll(existing.occupiedTiles());
        // ExistingNetwork has no rotations or block identities. Its undirected links cannot prove item connectivity.
        blocked.addAll(existing.traversableTiles());
        for (DrillCandidate drill : layout.candidates()) blocked.addAll(drill.footprint().tiles());
        ArrayList<PlanGraph> choices = new ArrayList<>();
        // Quadratic family enumeration, not a powerset: one ground ID and one bridge ID at most.
        // Keeping every available spec retains unusual mod placement rules and high-throughput tiers.
        List<ItemTransportSpec> ground = transports.stream().filter(spec -> !spec.bridge()).toList();
        List<ItemTransportSpec> bridges = transports.stream().filter(ItemTransportSpec::bridge).toList();
        LinkedHashSet<List<ItemTransportSpec>> families = new LinkedHashSet<>();
        if (allowMixedTransport) {
            // Evaluate capacity-capable mixed families before low-tier homogeneous dead ends.
            List<Float> thresholds = transports.stream().map(ItemTransportSpec::capacityPerSecond).distinct()
                .sorted(Comparator.reverseOrder()).toList();
            for (float threshold : thresholds) {
                families.add(transports.stream()
                    .filter(spec -> spec.capacityPerSecond() >= threshold)
                    .toList());
            }
        }
        for (ItemTransportSpec spec : transports) families.add(List.of(spec));
        for (ItemTransportSpec belt : ground) {
            for (ItemTransportSpec bridge : bridges) {
                families.add(belt.id().value().compareTo(bridge.id().value()) < 0
                    ? List.of(belt, bridge) : List.of(bridge, belt));
            }
        }
        RouteFacts facts = new RouteFacts(layout, sink, terrain, blocked,
            observer == null ? null : new RouteObserver(observer, layout.candidates().size()), budget);
        Set<Set<TransportPlacement>> seen = new HashSet<>();
        Set<BuildKey> built = new HashSet<>();
        for (List<ItemTransportSpec> family : families) {
            addChoice(choices, seen, build(layout, sink, family, facts, Policy.MATERIAL,
                facts.highRateFirst, false, transportHeadroom, built));
        }
        // Add a small portfolio, not family × objective × source-order enumeration.
        // Original families remain available: unusual mod placement/ports/ranges are not dominated away.
        choices.sort(PLAN_ORDER);
        List<PlanGraph> leaders = new ArrayList<>(choices.subList(0, Math.min(2, choices.size())));
        for (PlanGraph leader : leaders) {
            List<ItemTransportSpec> family = leader.placements().stream().map(TransportPlacement::spec).distinct()
                .sorted(Comparator.comparing(spec -> spec.id().value())).toList();
            addChoice(choices, seen, build(layout, sink, family, facts, Policy.COMPACT,
                facts.nearFirst, false, transportHeadroom, built));
        }
        if (allowMixedTransport) {
            // Reserve a strong initial spine, then search each branch with its own flow requirement.
            // This can introduce a new bridge/ground topology, unlike fixed-node material substitution.
            addChoice(choices, seen, build(layout, sink, transports, facts, Policy.MATERIAL,
                facts.highRateFirst, true, transportHeadroom, built));
            addChoice(choices, seen, build(layout, sink, transports, facts, Policy.MATERIAL,
                facts.farFirst, true, transportHeadroom, built));
            addChoice(choices, seen, build(layout, sink, transports, facts, Policy.COMPACT,
                facts.nearFirst, true, transportHeadroom, built));
            choices.sort(PLAN_ORDER);
            int baselineCount = Math.min(choices.size(), MAX_DOWNGRADE_CANDIDATES);
            List<PlanGraph> baselines = new ArrayList<>(choices.subList(0, baselineCount));
            List<ItemTransportSpec> cheaperFirst = transports.stream()
                .sorted(Comparator.comparingDouble((ItemTransportSpec spec) -> spec.cost().economicValue())
                    .thenComparing(spec -> spec.id().value())).toList();
            for (PlanGraph baseline : baselines) {
                PlanGraph cheaper = downgrade(baseline, layout, sink, facts, cheaperFirst, transportHeadroom);
                if (cheaper != baseline) {
                    if (facts.observer != null) facts.observer.candidate(RoutingProgress.Phase.CANDIDATE, cheaper, 0);
                    cheaper = simplify(cheaper, layout, sink, transportHeadroom, facts);
                    if (seen.add(Set.copyOf(cheaper.placements()))) retain(choices, cheaper);
                }
            }
        }
        if (choices.isEmpty()) {
            if (facts.observer != null && families.isEmpty()) facts.observer.noRoute(Map.of(), 0);
            return PlanGraph.of(List.of(PlanNode.sink("sink", sink.tile())), List.of(), "sink")
                .withDiagnostics(List.of(new PlannerDiagnostic(DiagnosticCode.NO_ROUTE, Map.of("reason",
                    transports.isEmpty() ? "no-supported-1x1-item-transport" : "no-physical-route"))));
        }
        choices.sort(PLAN_ORDER);
        PlanGraph primary = choices.get(0);
        return primary.withAlternatives(choices.subList(1, choices.size()));
    }

    private void addChoice(List<PlanGraph> choices, Set<Set<TransportPlacement>> seen,
                           PlanGraph graph) {
        if (graph != null && seen.add(Set.copyOf(graph.placements()))) {
            retain(choices, graph);
        }
    }

    private void retain(List<PlanGraph> choices, PlanGraph graph) {
        choices.add(graph);
        if (choices.size() > MAX_RETAINED_CANDIDATES) {
            choices.sort(PLAN_ORDER);
            choices.remove(choices.size() - 1);
        }
    }

    private PlanGraph build(MiningLayout layout, ExitAnchor sink, List<ItemTransportSpec> specs,
                            RouteFacts facts, Policy policy, List<DrillCandidate> drills,
                            boolean reserveSpine, float headroom, Set<BuildKey> built) {
        reserveSpine &= specs.stream().anyMatch(spec ->
            spec.capacityPerSecond() + FLOW_EPSILON < facts.totalDemand * headroom);
        if (!built.add(new BuildKey(specs, policy, drills, reserveSpine))) return null;
        facts.budget.checkpoint();
        LinkedHashMap<TileKey, TransportPlacement> placed = new LinkedHashMap<>();
        int connected = 0;
        Map<Float, SearchFamily> searchFamilies = new HashMap<>();
        for (DrillCandidate drill : drills) {
            float required = reserveSpine
                ? (placed.isEmpty() ? facts.totalDemand : drill.production().transportPerSecond()) * headroom : 0f;
            SearchFamily family = searchFamilies.computeIfAbsent(required, demand ->
                new SearchFamily(specs.stream().filter(spec -> spec.capacityPerSecond() + FLOW_EPSILON >= demand).toList()));
            if (family.specs.isEmpty()) {
                if (facts.observer != null) facts.observer.noRoute(placed, connected);
                return null;
            }
            SearchState route = findRoute(drill, sink, family, facts, policy, placed, connected);
            if (route == null) {
                if (facts.observer != null) facts.observer.noRoute(placed, connected);
                return null;
            }
            for (SearchState state = route; state != null; state = state.next) {
                placed.putIfAbsent(state.placement.tile(), state.placement);
            }
            connected++;
            if (facts.observer != null) facts.observer.connected(placed, route.placement.tile(), connected);
        }
        PlanGraph graph = PHYSICAL_GRAPHS.build(layout, sink, placed.values()).orElse(null);
        if (graph == null) {
            if (facts.observer != null) facts.observer.noRoute(placed, connected);
            return null;
        }
        graph = assignWithHeadroom(graph, headroom, facts.budget);
        if (facts.observer != null) facts.observer.candidate(RoutingProgress.Phase.CANDIDATE, graph, 0);
        return simplify(graph, layout, sink, headroom, facts);
    }


    private PlanGraph assignWithHeadroom(PlanGraph graph, float headroom, SearchBudget budget) {
        // Evaluate with reserve, but return raw capacities: the planner applies reserve to all alternatives.
        return graph.withFlow(new FlowSolver().assign(graph.withTransportHeadroom(headroom), budget));
    }

    private PlanGraph simplify(PlanGraph baseline, MiningLayout layout, ExitAnchor sink, float headroom,
                               RouteFacts facts) {
        RouteObserver observer = facts.observer;
        LinkedHashMap<TileKey, TransportPlacement> placed = new LinkedHashMap<>();
        for (TransportPlacement placement : baseline.placements()) placed.put(placement.tile(), placement);
        PlanGraph current = baseline;
        if (observer != null) observer.candidate(RoutingProgress.Phase.SIMPLIFYING, baseline, 0);
        boolean improved = true;
        while (improved) {
            facts.budget.checkpoint();
            improved = false;
            Map<TileKey, List<TileKey>> incoming = new HashMap<>();
            for (TransportPlacement placement : placed.values()) {
                incoming.computeIfAbsent(placement.output(), ignored -> new ArrayList<>()).add(placement.tile());
            }
            Map<String, Float> service = sourceService(current);
            for (TileKey seed : placed.keySet()) {
                if (seed.equals(sink.tile())) continue;
                // Try whole branches, then individual relays with their surviving predecessor
                // reconnected. Rebuild physical admission before accepting any removal.
                Set<TileKey> upstream = upstreamClosure(seed, incoming, facts.budget);
                LinkedHashSet<TileKey> branch = new LinkedHashSet<>(upstream);
                TileKey next = placed.get(seed).output();
                while (placed.containsKey(next) && !next.equals(sink.tile()) && !branch.contains(next)
                    && branch.containsAll(incoming.getOrDefault(next, List.of()))) {
                    branch.add(next);
                    next = placed.get(next).output();
                }
                LinkedHashSet<Set<TileKey>> removals = new LinkedHashSet<>();
                removals.add(branch);
                removals.add(upstream);
                removals.add(Set.of(seed));
                for (Set<TileKey> removal : removals) {
                    facts.budget.checkpoint();
                    for (LinkedHashMap<TileKey, TransportPlacement> changed :
                        removalVariants(placed, removal, incoming, sink, facts)) {
                        if (observer != null) observer.simplifying(changed, seed, current);
                        PlanGraph candidate = PHYSICAL_GRAPHS
                            .build(layout, sink, changed.values()).orElse(null);
                        if (candidate == null) continue;
                        candidate = assignWithHeadroom(candidate, headroom, facts.budget);
                        if (!candidate.flow().capacitySafe() || candidate.cost().compareTo(current.cost()) >= 0
                            || !preservesService(service, candidate)) continue;
                        current = candidate;
                        placed = changed;
                        improved = true;
                        if (observer != null) {
                            observer.candidate(RoutingProgress.Phase.SIMPLIFIED, current, removal.size());
                        }
                        break;
                    }
                    if (improved) break;
                }
                if (improved) break;
            }
        }
        return current;
    }


    private List<LinkedHashMap<TileKey, TransportPlacement>> removalVariants(
        Map<TileKey, TransportPlacement> placed, Set<TileKey> removal,
        Map<TileKey, List<TileKey>> incoming, ExitAnchor sink, RouteFacts facts
    ) {
        LinkedHashMap<TileKey, TransportPlacement> removed = new LinkedHashMap<>(placed);
        removal.forEach(removed::remove);
        ArrayList<LinkedHashMap<TileKey, TransportPlacement>> result = new ArrayList<>();
        result.add(removed);
        if (removal.size() != 1) return result;
        TileKey seed = removal.iterator().next();
        List<TileKey> boundary = incoming.getOrDefault(seed, List.of()).stream()
            .filter(tile -> !removal.contains(tile)).toList();
        if (boundary.size() != 1) return result;
        TransportPlacement predecessor = placed.get(boundary.get(0));
        if (predecessor == null) return result;
        int reach = predecessor.spec().bridge() ? predecessor.spec().range() : 1;
        ArrayList<TileKey> receivers = new ArrayList<>();
        for (int direction = 0; direction < 4; direction++) {
            for (int length = 1; length <= reach; length++) {
                TileKey receiver = step(predecessor.tile(), direction, length);
                if (removed.containsKey(receiver)
                    && hopsToSink(receiver, removed, sink, predecessor.tile()) != UNREACHABLE) {
                    receivers.add(receiver);
                }
            }
        }
        receivers.sort(Comparator.comparingInt(tile -> hopsToSink(tile, removed, sink, predecessor.tile())));
        for (TileKey receiverTile : receivers) {
            int rotation = direction(predecessor.tile(), receiverTile);
            if (!facts.canPlace(predecessor.spec(), predecessor.tile(), rotation)) continue;
            TransportPlacement receiver = removed.get(receiverTile);
            TileKey link = predecessor.spec().bridge()
                && predecessor.spec().id().equals(receiver.spec().id()) ? receiverTile : null;
            if (link == null && distance(predecessor.tile(), receiverTile) != 1) continue;
            TransportPlacement rerouted = new TransportPlacement(predecessor.spec(), predecessor.tile(),
                rotation, receiverTile, link);
            if (!pairConnects(rerouted, removed.get(receiverTile))) continue;
            LinkedHashMap<TileKey, TransportPlacement> changed = new LinkedHashMap<>(removed);
            changed.put(rerouted.tile(), rerouted);
            result.add(changed);
        }
        return result;
    }

    private int hopsToSink(TileKey start, Map<TileKey, TransportPlacement> placed,
                           ExitAnchor sink, TileKey excluded) {
        HashSet<TileKey> seen = new HashSet<>();
        TileKey current = start;
        int hops = 0;
        while (!current.equals(excluded) && seen.add(current)) {
            TransportPlacement placement = placed.get(current);
            if (placement == null) return UNREACHABLE;
            if (placement.tile().equals(sink.tile()) && placement.bridgeLink() == null
                && placement.output().equals(step(sink.tile(), exitDirection(sink), 1))) return hops;
            current = placement.output();
            hops++;
        }
        return UNREACHABLE;
    }

    private Set<TileKey> upstreamClosure(
        TileKey seed,
        Map<TileKey, List<TileKey>> incoming,
        SearchBudget budget
    ) {
        LinkedHashSet<TileKey> result = new LinkedHashSet<>();
        ArrayDeque<TileKey> pending = new ArrayDeque<>();
        result.add(seed);
        pending.add(seed);
        while (!pending.isEmpty()) {
            budget.checkpoint();
            for (TileKey predecessor : incoming.getOrDefault(pending.removeFirst(), List.of())) {
                if (result.add(predecessor)) pending.addLast(predecessor);
            }
        }
        return result;
    }

    private Map<String, Float> sourceService(PlanGraph graph) {
        Map<String, Float> service = new HashMap<>();
        for (PlanEdge edge : graph.edges()) {
            if (edge.kind() == EdgeKind.SOURCE_EDGE) {
                service.merge(edge.from(), graph.flow().flow(edge.id()), Float::sum);
            }
        }
        return service;
    }

    private boolean preservesService(Map<String, Float> required, PlanGraph candidate) {
        Map<String, Float> supplied = sourceService(candidate);
        for (var entry : required.entrySet()) {
            if (supplied.getOrDefault(entry.getKey(), 0f) + FLOW_EPSILON < entry.getValue()) return false;
        }
        return true;
    }

    private PlanGraph downgrade(PlanGraph baseline, MiningLayout layout, ExitAnchor sink, RouteFacts facts,
                                List<ItemTransportSpec> cheaperFirst, float headroom) {
        double demand = layout.candidates().stream().mapToDouble(drill -> drill.production().transportPerSecond()).sum();
        if (baseline.qOut() + FLOW_EPSILON < demand) return baseline;
        float requiredFlow = (float) demand;
        LinkedHashMap<TileKey, TransportPlacement> placed = new LinkedHashMap<>();
        for (TransportPlacement placement : baseline.placements()) placed.put(placement.tile(), placement);
        List<Set<TileKey>> groups = downgradeGroups(placed, baseline);
        groups.sort(Comparator.comparingDouble(group -> groupLoad(group, baseline)));
        PlanGraph current = baseline;
        int attempts = 0;
        ArrayDeque<Set<TileKey>> pendingGroups = new ArrayDeque<>(groups);
        while (!pendingGroups.isEmpty()) {
            Set<TileKey> group = pendingGroups.removeFirst();
            boolean attempted = false;
            boolean accepted = false;
            TransportPlacement original = placed.get(group.iterator().next());
            float load = groupLoad(group, current);
            for (ItemTransportSpec spec : cheaperFirst) {
                if (spec.bridge() != original.spec().bridge()
                    || spec.cost().economicValue() >= original.spec().cost().economicValue()
                    || spec.capacityPerSecond() / headroom + FLOW_EPSILON < load) continue;
                facts.budget.checkpoint();
                if (++attempts > MAX_DOWNGRADE_ATTEMPTS) return current;
                attempted = true;
                LinkedHashMap<TileKey, TransportPlacement> changed = new LinkedHashMap<>(placed);
                boolean placeable = true;
                for (TileKey tile : group) {
                    TransportPlacement old = placed.get(tile);
                    if (!facts.canPlace(spec, tile, old.rotation())) {
                        placeable = false;
                        break;
                    }
                    changed.put(tile, new TransportPlacement(spec, tile, old.rotation(), old.output(), old.bridgeLink()));
                }
                if (!placeable) continue;
                // Port behavior can change admission on this tile AND its neighbors. Rebuild every capacity,
                // validate all links/source outlets, then solve cumulative flow rather than one drill's demand.
                PlanGraph candidate = PHYSICAL_GRAPHS
                    .build(layout, sink, changed.values()).orElse(null);
                if (candidate == null) continue;
                candidate = assignWithHeadroom(candidate, headroom, facts.budget);
                if (candidate.flow().capacitySafe() && candidate.qOut() + FLOW_EPSILON >= requiredFlow
                    && candidate.cost().materials().economicValue() < current.cost().materials().economicValue()) {
                    current = candidate;
                    placed = changed;
                    accepted = true;
                    break;
                }
            }
            // A source port or a tile-specific mod rule can prevent a whole-branch substitution.
            // Keep the original per-node opportunity as a bounded fallback; bridge components stay atomic.
            if (attempted && !accepted && group.size() > 1 && !original.spec().bridge()) {
                for (TileKey tile : group) pendingGroups.addLast(Set.of(tile));
            }
        }
        return current;
    }

    private float groupLoad(Set<TileKey> group, PlanGraph graph) {
        float load = 0f;
        for (TileKey tile : group) load = Math.max(load, graph.flow().flow("transport:" + tileId(tile)));
        return load;
    }

    private List<Set<TileKey>> downgradeGroups(Map<TileKey, TransportPlacement> placed, PlanGraph graph) {
        Map<TileKey, List<TileKey>> linked = new HashMap<>();
        for (TransportPlacement placement : placed.values()) {
            TileKey target = placement.bridgeLink();
            if (target == null && !placement.spec().bridge()) {
                TransportPlacement next = placed.get(placement.output());
                if (next != null && next.spec().id().equals(placement.spec().id())
                    && ObjectiveValue.outputRank(graph.flow().flow("transport:" + tileId(placement.tile())))
                        == ObjectiveValue.outputRank(graph.flow().flow("transport:" + tileId(next.tile())))) {
                    target = next.tile();
                }
            }
            if (target == null) continue;
            linked.computeIfAbsent(placement.tile(), ignored -> new ArrayList<>()).add(target);
            linked.computeIfAbsent(target, ignored -> new ArrayList<>()).add(placement.tile());
        }
        List<Set<TileKey>> groups = new ArrayList<>();
        Set<TileKey> visited = new HashSet<>();
        for (TileKey tile : placed.keySet()) {
            if (!visited.add(tile)) continue;
            LinkedHashSet<TileKey> group = new LinkedHashSet<>();
            ArrayDeque<TileKey> pending = new ArrayDeque<>();
            pending.add(tile);
            while (!pending.isEmpty()) {
                TileKey next = pending.removeFirst();
                group.add(next);
                for (TileKey neighbor : linked.getOrDefault(next, List.of())) {
                    if (visited.add(neighbor)) pending.addLast(neighbor);
                }
            }
            groups.add(group);
        }
        return groups;
    }

    private SearchState findRoute(DrillCandidate drill, ExitAnchor sink, SearchFamily family,
                                  RouteFacts facts, Policy policy, Map<TileKey, TransportPlacement> placed,
                                  int connected) {
        SourceFacts source = facts.sources.get(drill.id());
        RelaxedReachability reachability = facts.reachability(drill.id(), family.specs);
        Comparator<SearchState> order = policy == Policy.MATERIAL ? MATERIAL_ORDER : COMPACT_ORDER;
        PriorityQueue<SearchState> pending = new PriorityQueue<>(order);
        Map<TransportPlacement, SearchState> best = new HashMap<>();
        if (placed.isEmpty()) {
            if (reachability.distance(sink.tile()) == UNREACHABLE) return null;
            int rotation = exitDirection(sink);
            for (ItemTransportSpec spec : family.specs) {
                if (!facts.canPlace(spec, sink.tile(), rotation)) continue;
                TransportPlacement terminal = new TransportPlacement(spec, sink.tile(), rotation,
                    step(sink.tile(), rotation, 1), null);
                offer(terminal, null, pending, best, family, reachability, order);
            }
        } else {
            boolean reachable = false;
            for (TransportPlacement placement : placed.values()) {
                int remaining = reachability.attachmentDistance(placement.tile());
                if (remaining == UNREACHABLE) continue;
                SearchState state = family.state(placement, null, 0d, 0, remaining);
                pending.add(state);
                best.put(placement, state);
                reachable = true;
            }
            if (!reachable) return null;
        }
        boolean firstExpansion = true;
        while (!pending.isEmpty()) {
            facts.budget.checkpoint();
            SearchState state = pending.remove();
            if (best.get(state.placement) != state) continue;
            if (facts.observer != null) {
                facts.observer.expanded(state, pending, placed, connected, firstExpansion);
                firstExpansion = false;
            }
            Set<TileKey> sides = source.perimeter.get(state.placement.tile());
            if (sides != null) {
                for (TileKey side : sides) {
                    if (!acceptsAdjacent(state.placement, side, null)) continue;
                    LinkedHashMap<TileKey, TransportPlacement> combined = new LinkedHashMap<>(placed);
                    for (SearchState path = state; path != null; path = path.next) {
                        combined.put(path.placement.tile(), path.placement);
                    }
                    if (TransportGeometry.networkConsistent(combined, sink)) {
                        for (TileKey input : sides) {
                            if (acceptsAdjacent(state.placement, input, null, combined)) return state;
                        }
                    }
                    break;
                }
            }
            for (int direction = 0; direction < 4; direction++) {
                TileKey tile = step(state.placement.tile(), direction, 1);
                if (placed.containsKey(tile) || reachability.distance(tile) == UNREACHABLE) continue;
                int rotation = (direction + 2) % 4;
                for (ItemTransportSpec spec : family.specs) {
                    if (!facts.canPlace(spec, tile, rotation)) continue;
                    TransportPlacement previous = new TransportPlacement(spec, tile, rotation,
                        state.placement.tile(), null);
                    if (pairConnects(previous, state.placement)) {
                        offer(previous, state, pending, best, family, reachability, order);
                    }
                }
            }
            if (state.placement.spec().bridge()) {
                ItemTransportSpec spec = state.placement.spec();
                for (int direction = 0; direction < 4; direction++) {
                    int rotation = (direction + 2) % 4;
                    for (int length = 1; length <= spec.range(); length++) {
                        TileKey tile = step(state.placement.tile(), direction, length);
                        if (placed.containsKey(tile) || reachability.distance(tile) == UNREACHABLE
                            || !facts.canPlace(spec, tile, rotation)) continue;
                        TransportPlacement previous = new TransportPlacement(spec, tile, rotation,
                            state.placement.tile(), state.placement.tile());
                        if (pairConnects(previous, state.placement)) {
                            offer(previous, state, pending, best, family, reachability, order);
                        }
                    }
                }
            }
        }
        return null;
    }

    private void offer(TransportPlacement placement, SearchState next, PriorityQueue<SearchState> pending,
                       Map<TransportPlacement, SearchState> best, SearchFamily family,
                       RelaxedReachability reachability, Comparator<SearchState> order) {
        int remaining = reachability.distance(placement.tile());
        if (remaining == UNREACHABLE) return;
        SearchState state = family.state(placement, next,
            (next == null ? 0d : next.materials) + placement.spec().cost().economicValue(),
            (next == null ? 0 : next.tiles) + 1, remaining);
        SearchState previous = best.get(placement);
        if (previous != null && order.compare(state, previous) >= 0) return;
        for (SearchState path = next; path != null; path = path.next) {
            if (path.placement.tile().equals(placement.tile())) return;
        }
        best.put(placement, state);
        pending.add(state);
    }

    private String tileId(TileKey tile) { return "tile:" + tile.x() + "," + tile.y(); }

    private enum Policy { MATERIAL, COMPACT }

    private record BuildKey(List<ItemTransportSpec> specs, Policy policy, List<DrillCandidate> drills,
                            boolean reserveSpine) {}

    private record ReachKey(String sourceId, List<ContentId> specs) {}

    private record RelaxedReachability(Map<TileKey, Integer> components, Set<Integer> sourceComponents,
                                       SourceFacts source) {
        private int distance(TileKey tile) {
            Integer component = components.get(tile);
            return component != null && sourceComponents.contains(component) ? source.distance(tile) : UNREACHABLE;
        }

        private int attachmentDistance(TileKey tile) {
            int result = distance(tile);
            for (int direction = 0; direction < 4; direction++) {
                int neighbor = distance(step(tile, direction, 1));
                if (neighbor != UNREACHABLE) result = Math.min(result, neighbor + 1);
            }
            return result;
        }
    }

    private record SearchState(TransportPlacement placement, SearchState next, double materials, int tiles,
                               double estimatedMaterials, int estimatedTiles, int remainingDistance) {}

    private record RelaxedTopology(Map<TileKey, Integer> components) {}


    private static final class RouteObserver {
        private static final long SNAPSHOT_INTERVAL_NANOS = 16_000_000L;
        private static final int MAX_FRONTIER_TILES = 32;
        private final Consumer<RoutingProgress> consumer;
        private final int totalSources;
        private long expandedStates;
        private long nextSnapshot;

        private RouteObserver(Consumer<RoutingProgress> consumer, int totalSources) {
            this.consumer = consumer;
            this.totalSources = totalSources;
        }

        private void expanded(SearchState state, PriorityQueue<SearchState> pending,
                              Map<TileKey, TransportPlacement> placed, int connected, boolean force) {
            expandedStates++;
            long now = System.nanoTime();
            if (!force && now < nextSnapshot) return;
            nextSnapshot = now + SNAPSHOT_INTERVAL_NANOS;
            LinkedHashMap<TileKey, TransportPlacement> partial = new LinkedHashMap<>(placed);
            for (SearchState path = state; path != null; path = path.next) {
                partial.putIfAbsent(path.placement.tile(), path.placement);
            }
            LinkedHashSet<TileKey> frontier = new LinkedHashSet<>();
            frontier.add(state.placement.tile());
            int inspected = 0;
            for (SearchState queued : pending) {
                if (frontier.size() >= MAX_FRONTIER_TILES || inspected++ >= MAX_FRONTIER_TILES * 2) break;
                frontier.add(queued.placement.tile());
            }
            consumer.accept(new RoutingProgress(RoutingProgress.Phase.SEARCHING, new ArrayList<>(partial.values()),
                new ArrayList<>(frontier), state.placement.tile(), expandedStates, connected, totalSources, null, 0));
        }

        private void connected(Map<TileKey, TransportPlacement> placed, TileKey focus, int connected) {
            consumer.accept(new RoutingProgress(RoutingProgress.Phase.CONNECTED, new ArrayList<>(placed.values()),
                List.of(), focus, expandedStates, connected, totalSources, null, 0));
        }

        private void noRoute(Map<TileKey, TransportPlacement> placed, int connected) {
            consumer.accept(new RoutingProgress(RoutingProgress.Phase.NO_ROUTE, new ArrayList<>(placed.values()),
                List.of(), null, expandedStates, connected, totalSources, null, 0));
        }

        private void candidate(RoutingProgress.Phase phase, PlanGraph graph, int removed) {
            consumer.accept(new RoutingProgress(phase, graph.placements(), List.of(), null,
                expandedStates, totalSources, totalSources, graph, removed));
        }

        private void simplifying(Map<TileKey, TransportPlacement> placed, TileKey focus, PlanGraph baseline) {
            long now = System.nanoTime();
            if (now < nextSnapshot) return;
            nextSnapshot = now + SNAPSHOT_INTERVAL_NANOS;
            consumer.accept(new RoutingProgress(RoutingProgress.Phase.SIMPLIFYING, new ArrayList<>(placed.values()),
                List.of(), focus, expandedStates, totalSources, totalSources, baseline, 0));
        }
    }

    private static final class SearchFamily {
        private final List<ItemTransportSpec> specs;
        private final int maxSpan;
        private final double materialPerDistance;

        private SearchFamily(List<ItemTransportSpec> specs) {
            this.specs = specs;
            int span = 1;
            double rate = Double.POSITIVE_INFINITY;
            for (ItemTransportSpec spec : specs) {
                int reach = spec.bridge() ? Math.max(1, spec.range()) : 1;
                span = Math.max(span, reach);
                rate = Math.min(rate, spec.cost().economicValue() / reach);
            }
            maxSpan = span;
            materialPerDistance = Math.max(0d, Math.nextDown(rate));
        }

        private SearchState state(TransportPlacement placement, SearchState next, double materials, int tiles,
                                  int remaining) {
            double materialBound = Math.max(0d, Math.nextDown(remaining * materialPerDistance));
            return new SearchState(placement, next, materials, tiles, materials + materialBound,
                tiles + (remaining + maxSpan - 1) / maxSpan, remaining);
        }
    }

    private static final class SourceFacts {
        private final Map<TileKey, Set<TileKey>> perimeter;
        private final int minX, minY, maxX, maxY;

        private SourceFacts(DrillCandidate drill) {
            Map<TileKey, Set<TileKey>> sides = new HashMap<>();
            int left = Integer.MAX_VALUE, bottom = Integer.MAX_VALUE;
            int right = Integer.MIN_VALUE, top = Integer.MIN_VALUE;
            for (TileKey tile : drill.footprint().tiles()) {
                left = Math.min(left, tile.x());
                bottom = Math.min(bottom, tile.y());
                right = Math.max(right, tile.x());
                top = Math.max(top, tile.y());
                for (int direction = 0; direction < 4; direction++) {
                    TileKey adjacent = step(tile, direction, 1);
                    if (!drill.footprint().tiles().contains(adjacent)) {
                        sides.computeIfAbsent(adjacent, ignored -> new LinkedHashSet<>()).add(tile);
                    }
                }
            }
            sides.replaceAll((tile, neighbors) -> Set.copyOf(neighbors));
            perimeter = Map.copyOf(sides);
            minX = left;
            minY = bottom;
            maxX = right;
            maxY = top;
        }

        private int distance(TileKey tile) {
            int dx = Math.max(0, Math.max(minX - tile.x(), tile.x() - maxX));
            int dy = Math.max(0, Math.max(minY - tile.y(), tile.y() - maxY));
            return Math.max(0, dx + dy - 1);
        }
    }

    private static final class RouteFacts {
        private final TerrainSnapshot terrain;
        private final Set<TileKey> blocked;
        private final Map<String, SourceFacts> sources;
        private final List<DrillCandidate> highRateFirst, nearFirst, farFirst;
        private final float totalDemand;
        private final Map<ContentId, Map<TileKey, Integer>> placementMasks = new HashMap<>();
        private final Map<ReachKey, RelaxedReachability> reachability = new HashMap<>();
        private final Map<List<ContentId>, RelaxedTopology> topologies = new HashMap<>();
        private final RouteObserver observer;
        private final SearchBudget budget;

        private RouteFacts(MiningLayout layout, ExitAnchor sink, TerrainSnapshot terrain, Set<TileKey> blocked,
                           RouteObserver observer, SearchBudget budget) {
            this.terrain = terrain;
            this.blocked = blocked;
            this.observer = observer;
            this.budget = budget;
            Map<String, SourceFacts> sourceFacts = new HashMap<>();
            for (DrillCandidate drill : layout.candidates()) sourceFacts.put(drill.id(), new SourceFacts(drill));
            sources = Map.copyOf(sourceFacts);
            highRateFirst = layout.candidates().stream()
                .sorted(Comparator.comparingDouble((DrillCandidate drill) -> drill.production().transportPerSecond()).reversed()
                    .thenComparing(DrillCandidate::id)).toList();
            Comparator<DrillCandidate> distanceOrder = Comparator.comparingInt(
                drill -> sources.get(drill.id()).distance(sink.tile()));
            nearFirst = layout.candidates().stream().sorted(distanceOrder.thenComparing(DrillCandidate::id)).toList();
            farFirst = layout.candidates().stream().sorted(distanceOrder.reversed().thenComparing(DrillCandidate::id)).toList();
            totalDemand = (float) layout.candidates().stream().mapToDouble(drill -> drill.production().transportPerSecond()).sum();
        }

        private RelaxedReachability reachability(String sourceId, List<ItemTransportSpec> specs) {
            List<ContentId> ids = specs.stream().map(ItemTransportSpec::id).toList();
            ReachKey key = new ReachKey(sourceId, ids);
            return reachability.computeIfAbsent(key, ignored -> {
                SourceFacts source = sources.get(sourceId);
                RelaxedTopology topology = topologies.computeIfAbsent(ids, unused -> topology(specs));
                LinkedHashSet<Integer> sourceComponents = new LinkedHashSet<>();
                for (TileKey tile : source.perimeter.keySet()) {
                    Integer component = topology.components().get(tile);
                    if (component != null) sourceComponents.add(component);
                }
                return new RelaxedReachability(topology.components(), Set.copyOf(sourceComponents), source);
            });
        }

        private RelaxedTopology topology(List<ItemTransportSpec> specs) {
            LinkedHashSet<TileKey> usable = new LinkedHashSet<>();
            Map<ContentId, Set<TileKey>> bridgeUsable = new HashMap<>();
            for (TileKey tile : terrain.tiles().keySet()) {
                for (ItemTransportSpec spec : specs) {
                    boolean placeable = false;
                    for (int rotation = 0; rotation < 4 && !placeable; rotation++) {
                        placeable = canPlace(spec, tile, rotation);
                    }
                    if (!placeable) continue;
                    usable.add(tile);
                    if (spec.bridge()) {
                        bridgeUsable.computeIfAbsent(spec.id(), unused -> new LinkedHashSet<>()).add(tile);
                    }
                }
            }
            List<ItemTransportSpec> bridges = specs.stream().filter(ItemTransportSpec::bridge).toList();
            Map<TileKey, Integer> components = new HashMap<>();
            ArrayDeque<TileKey> pending = new ArrayDeque<>();
            int component = 0;
            for (TileKey start : usable) {
                if (components.putIfAbsent(start, component) != null) continue;
                pending.addLast(start);
                while (!pending.isEmpty()) {
                    budget.checkpoint();
                    TileKey tile = pending.removeFirst();
                    for (int direction = 0; direction < 4; direction++) {
                        addComponent(step(tile, direction, 1), component, usable, components, pending);
                    }
                    for (ItemTransportSpec spec : bridges) {
                        Set<TileKey> endpoints = bridgeUsable.get(spec.id());
                        if (endpoints == null || !endpoints.contains(tile)) continue;
                        for (int direction = 0; direction < 4; direction++) {
                            for (int length = 2; length <= spec.range(); length++) {
                                TileKey endpoint = step(tile, direction, length);
                                if (endpoints.contains(endpoint)) {
                                    addComponent(endpoint, component, usable, components, pending);
                                }
                            }
                        }
                    }
                }
                component++;
            }
            return new RelaxedTopology(Map.copyOf(components));
        }

        private void addComponent(TileKey tile, int component, Set<TileKey> usable,
                                  Map<TileKey, Integer> components, ArrayDeque<TileKey> pending) {
            if (usable.contains(tile) && components.putIfAbsent(tile, component) == null) pending.addLast(tile);
        }

        private boolean canPlace(ItemTransportSpec spec, TileKey tile, int rotation) {
            if (blocked.contains(tile) || !terrain.contains(tile)) return false;
            Map<TileKey, Integer> masks = placementMasks.computeIfAbsent(spec.id(), ignored -> new HashMap<>());
            Integer cached = masks.get(tile);
            int mask = cached == null ? 0 : cached;
            int known = 1 << (rotation + 4);
            if ((mask & known) == 0) {
                if (cached == null && conflictsWithExistingDirectionalBridge(spec, tile, terrain)) {
                    masks.put(tile, 240);
                    return false;
                }
                mask |= known;
                if (terrain.canPlace(spec.id(), tile, rotation)) mask |= 1 << rotation;
                masks.put(tile, mask);
            }
            return (mask & (1 << rotation)) != 0;
        }
    }
}
