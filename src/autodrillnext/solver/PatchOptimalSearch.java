package autodrillnext.solver;

import autodrillnext.capability.spec.CostVector;
import autodrillnext.capability.spec.CapabilityDescriptor;
import autodrillnext.capability.spec.CapabilitySnapshot;
import autodrillnext.capability.spec.CapabilityKind;
import autodrillnext.capability.spec.CapabilityState;
import autodrillnext.capability.spec.ItemTransportSpec;
import autodrillnext.compile.TransportGeometry;
import autodrillnext.model.DiagnosticCode;
import autodrillnext.model.DrillCandidate;
import autodrillnext.model.MiningLayout;
import autodrillnext.model.ObjectiveValue;
import autodrillnext.model.OptimalityCertificate;
import autodrillnext.model.PatchSearchScope;
import autodrillnext.model.PlanObjectiveOrder;
import autodrillnext.model.PlannerDiagnostic;
import autodrillnext.model.PlannerRequest;
import autodrillnext.model.PlanningProgress;
import autodrillnext.model.SearchBudget;
import autodrillnext.model.SearchStopReason;
import autodrillnext.model.SearchVerdict;
import autodrillnext.model.ServiceBundle;
import autodrillnext.model.TransportPlacement;
import autodrillnext.world.TileKey;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/** Deterministic anytime branch-and-bound over the complete captured patch domain. */
public final class PatchOptimalSearch {
    private static final long SEARCH_NANOS = TimeUnit.SECONDS.toNanos(2);
    private static final long WARM_NANOS = SEARCH_NANOS * 3 / 4;

    private final MiningLayoutSolver layouts;
    private final ItemRouteSolver routes;
    private final PlanCandidateEvaluator evaluator;
    private final ParetoPlanner pareto;
    private final LongSupplier nanoTime;

    public PatchOptimalSearch() {
        this(new MiningLayoutSolver(), new ItemRouteSolver(), new PlanCandidateEvaluator(),
            new ParetoPlanner(), System::nanoTime);
    }

    PatchOptimalSearch(
        MiningLayoutSolver layouts,
        ItemRouteSolver routes,
        PlanCandidateEvaluator evaluator,
        ParetoPlanner pareto,
        LongSupplier nanoTime
    ) {
        this.layouts = Objects.requireNonNull(layouts, "layout solver");
        this.routes = Objects.requireNonNull(routes, "route solver");
        this.evaluator = Objects.requireNonNull(evaluator, "candidate evaluator");
        this.pareto = Objects.requireNonNull(pareto, "pareto planner");
        this.nanoTime = Objects.requireNonNull(nanoTime, "nano time");
    }

    public OptimalPlanningResult search(
        PatchSearchScope scope,
        List<DrillCandidate> candidates,
        Consumer<PlanningProgress> progress
    ) {
        Objects.requireNonNull(scope, "scope");
        candidates = List.copyOf(candidates);
        Consumer<PlanningProgress> listener = progress == null ? ignored -> {} : progress;
        checkCancelled();
        long start = nanoTime.getAsLong();
        long deadline = saturatingAdd(start, SEARCH_NANOS);
        SearchBudget globalBudget = SearchBudget.until(deadline, nanoTime);
        PlanObjectiveOrder order = PlanObjectiveOrder.forProfile(scope.request().profile());
        SearchRun run = new SearchRun(scope, candidates, order, listener, globalBudget);
        LayoutState root = run.layoutState(0, List.of());
        run.rootUpper = root.optimistic();
        run.pending.add(root);

        run.publish(
            PlanningProgress.Stage.HEURISTIC_WARM_START,
            MiningLayout.of(List.of()),
            null,
            null
        );
        if (scope.request().searchStrategy() == PlannerRequest.SearchStrategy.HEURISTIC) {
            try {
                warmStart(run, globalBudget);
            } catch (SearchBudget.Expired expired) {
                checkCancelled();
            }
            return run.heuristic();
        }
        SearchBudget warmBudget = SearchBudget.until(
            saturatingAdd(start, WARM_NANOS), nanoTime);
        try {
            warmStart(run, warmBudget);
        } catch (SearchBudget.Expired expired) {
            checkCancelled();
        }

        run.publish(
            PlanningProgress.Stage.CERTIFYING,
            MiningLayout.of(List.of()),
            null,
            null
        );
        try {
            run.domain = TransportPlacementDomain.capture(scope, globalBudget);
        } catch (SearchBudget.Expired expired) {
            checkCancelled();
            return run.limited(SearchStopReason.DEADLINE);
        }
        while (!run.pending.isEmpty()) {
            checkCancelled();
            if (nanoTime.getAsLong() >= deadline) {
                return run.limited(SearchStopReason.DEADLINE);
            }
            if (run.explored >= scope.request().maxIterations()) {
                return run.limited(SearchStopReason.STATE_LIMIT);
            }
            SearchState state = run.pending.poll();
            if (run.incumbent != null
                && !order.canBeat(state.optimistic(), run.incumbent.objective())) {
                run.pruned++;
                continue;
            }
            ObjectiveValue prior = run.seen.putIfAbsent(state.canonicalId(), state.optimistic());
            if (prior != null && order.compare(prior, state.optimistic()) >= 0) {
                run.pruned++;
                continue;
            }
            try {
                List<SearchState> children = run.expand(state);
                run.explored++;
                run.pending.addAll(children);
            } catch (SearchBudget.Expired expired) {
                checkCancelled();
                run.pending.add(state);
                return run.limited(SearchStopReason.DEADLINE);
            }
        }
        return run.exhausted();
    }

    private void warmStart(SearchRun run, SearchBudget budget) {
        DrillCandidate fallback = run.candidates.stream()
            .sorted(Comparator
                .comparingDouble((DrillCandidate drill) -> drill.production().perSecond())
                .reversed()
                .thenComparing(
                    Comparator.comparingInt(
                        (DrillCandidate drill) -> drill.coveredOreCells().size()
                    ).reversed()
                )
                .thenComparing(DrillCandidate::id))
            .findFirst()
            .orElse(null);
        if (run.scope.request().searchStrategy() == PlannerRequest.SearchStrategy.HEURISTIC) {
            heuristicWarmStart(run, fallback, budget);
            return;
        }
        LinkedHashSet<Set<String>> attempted = new LinkedHashSet<>();
        if (fallback != null) {
            MiningLayout single = MiningLayout.of(List.of(fallback));
            attempted.add(candidateIds(single));
            tryWarmLayout(run, single, budget);
        }
        float capacity = maxTransportCapacity(run);
        tryWarmPrefixes(run, capacitySpread(run.candidates, capacity), attempted, budget);
        tryWarmPrefixes(
            run,
            capacityBounded(layouts.primarySeed(run.candidates, budget), capacity),
            attempted,
            budget
        );
        for (MiningLayout seed : layouts.seeds(run.candidates, run.scope.request(), budget)) {
            tryWarmPrefixes(run, capacityBounded(seed, capacity), attempted, budget);
        }
    }

    private void heuristicWarmStart(SearchRun run, DrillCandidate fallback, SearchBudget budget) {
        MiningLayout primary = layouts.primarySeed(run.candidates, budget);
        boolean primaryFits = !primary.candidates().isEmpty()
            && primary.productionPerSecond() <= maxTransportCapacity(run) + 0.0001f;
        boolean primaryFirst = primaryFits && !hasAvailableBridge(run);
        if (primaryFirst) tryWarmLayout(run, primary, budget);
        if (run.incumbent == null && fallback != null) {
            tryWarmLayout(run, MiningLayout.of(List.of(fallback)), budget);
        }
        if (!primaryFirst && !primary.candidates().isEmpty()) tryWarmLayout(run, primary, budget);
        for (MiningLayout layout : layouts.seeds(run.candidates, run.scope.request(), budget)) {
            tryWarmLayout(run, layout, budget);
        }
    }

    private void tryWarmPrefixes(
        SearchRun run,
        MiningLayout layout,
        Set<Set<String>> attempted,
        SearchBudget budget
    ) {
        for (int count = 2; count <= layout.candidates().size(); count++) {
            budget.checkpoint();
            MiningLayout prefix = MiningLayout.of(layout.candidates().subList(0, count));
            if (attempted.add(candidateIds(prefix))) tryWarmLayout(run, prefix, budget);
        }
    }

    private float maxTransportCapacity(SearchRun run) {
        return run.scope.capabilities().descriptors().values().stream()
            .filter(descriptor -> descriptor.kind() == CapabilityKind.ITEM_TRANSPORT)
            .filter(descriptor -> descriptor.states().contains(CapabilityState.AVAILABLE_NOW))
            .map(descriptor -> descriptor.spec())
            .filter(ItemTransportSpec.class::isInstance)
            .map(ItemTransportSpec.class::cast)
            .filter(ItemTransportSpec::supportedGeometry)
            .map(ItemTransportSpec::capacityPerSecond)
            .max(Float::compare)
            .orElse(0f) / run.scope.request().transportHeadroom();
    }
    private boolean hasAvailableBridge(SearchRun run) {
        return run.scope.capabilities().descriptors().values().stream()
            .filter(descriptor -> descriptor.kind() == CapabilityKind.ITEM_TRANSPORT)
            .filter(descriptor -> descriptor.states().contains(CapabilityState.AVAILABLE_NOW))
            .map(descriptor -> descriptor.spec())
            .filter(ItemTransportSpec.class::isInstance)
            .map(ItemTransportSpec.class::cast)
            .anyMatch(ItemTransportSpec::bridge);
    }

    private MiningLayout capacityBounded(MiningLayout layout, float capacity) {
        ArrayList<DrillCandidate> selected = new ArrayList<>();
        float load = 0f;
        for (DrillCandidate drill : layout.candidates()) {
            float demand = drill.production().transportPerSecond();
            if (load + demand <= capacity + 0.0001f) {
                selected.add(drill);
                load += demand;
            }
        }
        return MiningLayout.of(selected);
    }

    private MiningLayout capacitySpread(List<DrillCandidate> candidates, float capacity) {
        ArrayList<DrillCandidate> selected = new ArrayList<>();
        float load = 0f;
        while (true) {
            DrillCandidate best = null;
            int bestDistance = -1;
            for (DrillCandidate candidate : candidates) {
                float demand = candidate.production().transportPerSecond();
                if (load + demand > capacity + 0.0001f || conflicts(candidate, selected)) continue;
                int distance = Integer.MAX_VALUE;
                for (DrillCandidate current : selected) {
                    distance = Math.min(
                        distance,
                        Math.abs(current.anchor().x() - candidate.anchor().x())
                            + Math.abs(current.anchor().y() - candidate.anchor().y())
                    );
                }
                if (best == null
                    || candidate.production().perSecond() > best.production().perSecond()
                    || candidate.production().perSecond() == best.production().perSecond()
                        && (distance > bestDistance
                            || distance == bestDistance && candidate.id().compareTo(best.id()) < 0)) {
                    best = candidate;
                    bestDistance = distance;
                }
            }
            if (best == null) break;
            selected.add(best);
            load += best.production().transportPerSecond();
        }
        return MiningLayout.of(selected);
    }

    private boolean conflicts(DrillCandidate candidate, List<DrillCandidate> selected) {
        for (DrillCandidate current : selected) {
            if (candidate.footprint().overlaps(current.footprint())
                || candidate.conflictIds().contains(current.id())
                || current.conflictIds().contains(candidate.id())) {
                return true;
            }
        }
        return false;
    }

    private Set<String> candidateIds(MiningLayout layout) {
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        for (DrillCandidate drill : layout.candidates()) ids.add(drill.id());
        return Set.copyOf(ids);
    }

    private boolean tryWarmLayout(SearchRun run, MiningLayout layout, SearchBudget budget) {
        budget.checkpoint();
        CapabilitySnapshot routingCapabilities =
            run.scope.request().searchStrategy() == PlannerRequest.SearchStrategy.HEURISTIC
                ? run.scope.capabilities()
                : warmCapabilities(run, layout);
        var graph = routes.route(
            layout,
            run.scope.exit(),
            run.scope.routingTerrain(),
            routingCapabilities,
            run.scope.existingNetwork(),
            reservedTiles(layout),
            run.scope.request().allowMixedTransport(),
            run.scope.request().transportHeadroom(),
            event -> run.publish(
                PlanningProgress.Stage.HEURISTIC_WARM_START,
                layout,
                event.candidate(),
                event
            ),
            budget
        );
        ArrayList<autodrillnext.model.PlanGraph> options = new ArrayList<>();
        options.add(graph);
        options.addAll(graph.alternatives());
        for (var option : options) {
            budget.checkpoint();
            PlanCandidateEvaluator.Evaluation evaluation = evaluator.evaluate(
                layout, option, run.scope, budget);
            run.diagnostics.addAll(evaluation.diagnostics());
            if (!evaluation.plans().isEmpty()) {
                run.consider(evaluation.plans().get(0), false);
                run.publish(
                    PlanningProgress.Stage.HEURISTIC_WARM_START,
                    layout,
                    option,
                    null
                );
                return true;
            }
        }
        return false;
    }

    private CapabilitySnapshot warmCapabilities(SearchRun run, MiningLayout layout) {
        float totalDemand = 0f;
        float branchDemand = 0f;
        for (DrillCandidate drill : layout.candidates()) {
            float demand = drill.production().transportPerSecond();
            totalDemand += demand;
            branchDemand = Math.max(branchDemand, demand);
        }
        float headroom = run.scope.request().transportHeadroom();
        CapabilityDescriptor ground = preferredTransport(run, false, totalDemand * headroom);
        CapabilityDescriptor bridge = preferredTransport(run, true, branchDemand * headroom);
        LinkedHashMap<autodrillnext.model.ContentId, CapabilityDescriptor> selected = new LinkedHashMap<>();
        if (ground != null) selected.put(ground.id(), ground);
        if (bridge != null) selected.put(bridge.id(), bridge);
        return new CapabilitySnapshot(selected);
    }

    private CapabilityDescriptor preferredTransport(SearchRun run, boolean bridge, float demand) {
        CapabilityDescriptor best = null;
        for (CapabilityDescriptor descriptor : run.scope.capabilities().descriptors().values()) {
            if (descriptor.kind() != CapabilityKind.ITEM_TRANSPORT
                || !descriptor.states().contains(CapabilityState.AVAILABLE_NOW)
                || !(descriptor.spec() instanceof ItemTransportSpec spec)
                || !spec.supportedGeometry()
                || spec.bridge() != bridge
                || spec.capacityPerSecond() + 0.0001f < demand) {
                continue;
            }
            if (best == null || compareWarmTransport(descriptor, best) < 0) best = descriptor;
        }
        return best;
    }

    private int compareWarmTransport(CapabilityDescriptor left, CapabilityDescriptor right) {
        ItemTransportSpec leftSpec = (ItemTransportSpec) left.spec();
        ItemTransportSpec rightSpec = (ItemTransportSpec) right.spec();
        int cost = Double.compare(leftSpec.cost().economicValue(), rightSpec.cost().economicValue());
        if (cost != 0) return cost;
        int capacity = Float.compare(rightSpec.capacityPerSecond(), leftSpec.capacityPerSecond());
        if (capacity != 0) return capacity;
        return left.id().value().compareTo(right.id().value());
    }

    private Set<TileKey> reservedTiles(MiningLayout layout) {
        LinkedHashSet<TileKey> result = new LinkedHashSet<>();
        for (DrillCandidate drill : layout.candidates()) result.addAll(drill.footprint().tiles());
        return Set.copyOf(result);
    }

    private long saturatingAdd(long left, long right) {
        return left > Long.MAX_VALUE - right ? Long.MAX_VALUE : left + right;
    }

    private static void checkCancelled() {
        if (Thread.currentThread().isInterrupted()) throw new CancellationException();
    }

    private sealed interface SearchState permits LayoutState, TransportState {
        ObjectiveValue optimistic();
        String canonicalId();
    }

    private record LayoutState(
        int cursor,
        List<DrillCandidate> selected,
        ObjectiveValue optimistic,
        String canonicalId
    ) implements SearchState {
        private LayoutState {
            selected = List.copyOf(selected);
        }
    }

    private record TransportState(
        MiningLayout layout,
        BitSet enabled,
        BitSet excluded,
        BitSet conflictBlocked,
        boolean evaluated,
        ObjectiveValue optimistic,
        String canonicalId
    ) implements SearchState {
        private TransportState {
            enabled = (BitSet) enabled.clone();
            excluded = (BitSet) excluded.clone();
            conflictBlocked = (BitSet) conflictBlocked.clone();
        }
    }

    private final class SearchRun {
        private final PatchSearchScope scope;
        private final List<DrillCandidate> candidates;
        private final PlanObjectiveOrder order;
        private final Consumer<PlanningProgress> progress;
        private final SearchBudget budget;
        private final PriorityQueue<SearchState> pending;
        private final Map<String, ObjectiveValue> seen = new HashMap<>();
        private final LinkedHashSet<PlannerDiagnostic> diagnostics = new LinkedHashSet<>();
        private final ArrayList<ServiceBundle> frontier = new ArrayList<>();

        private TransportPlacementDomain domain;
        private EvaluatedMiningPlan incumbent;
        private boolean incumbentFromProof;
        private ObjectiveValue rootUpper = ObjectiveValue.zero("bound");
        private int explored;
        private int pruned;
        private int accepted;
        private int layoutStates;
        private int transportStates;

        private SearchRun(
            PatchSearchScope scope,
            List<DrillCandidate> candidates,
            PlanObjectiveOrder order,
            Consumer<PlanningProgress> progress,
            SearchBudget budget
        ) {
            this.scope = scope;
            this.candidates = candidates;
            this.order = order;
            this.progress = progress;
            this.budget = budget;
            this.pending = new PriorityQueue<>(Comparator
                .comparing(SearchState::optimistic, order.bestFirst())
                .thenComparing(SearchState::canonicalId));
        }

        private List<SearchState> expand(SearchState state) {
            if (state instanceof LayoutState layout) {
                layoutStates++;
                return expandLayout(layout);
            }
            transportStates++;
            return expandTransport((TransportState) state);
        }

        private List<SearchState> expandLayout(LayoutState state) {
            publish(
                PlanningProgress.Stage.CERTIFYING,
                MiningLayout.of(state.selected()),
                null,
                null
            );
            if (state.cursor() == candidates.size()) {
                if (state.selected().isEmpty()) return List.of();
                return List.of(transportState(
                    MiningLayout.of(state.selected()),
                    new BitSet(),
                    new BitSet(),
                    new BitSet(),
                    false
                ));
            }
            ArrayList<SearchState> children = new ArrayList<>(2);
            children.add(layoutState(state.cursor() + 1, state.selected()));
            DrillCandidate candidate = candidates.get(state.cursor());
            if (canInclude(candidate, state.selected())) {
                ArrayList<DrillCandidate> selected = new ArrayList<>(state.selected());
                selected.add(candidate);
                children.add(layoutState(state.cursor() + 1, selected));
            }
            return List.copyOf(children);
        }

        private List<SearchState> expandTransport(TransportState state) {
            budget.checkpoint();
            boolean fed = allDrillsFed(state.layout(), state.enabled());
            boolean evaluated = state.evaluated();
            if (fed && !evaluated) {
                evaluated = true;
                domain.exactGraph(state.layout(), state.enabled(), budget).ifPresent(graph -> {
                    PlanCandidateEvaluator.Evaluation evaluation = evaluator.evaluate(
                        state.layout(), graph, scope, budget);
                    diagnostics.addAll(evaluation.diagnostics());
                    for (EvaluatedMiningPlan plan : evaluation.plans()) consider(plan, true);
                });
            }

            List<Integer> choices = frontierChoices(state);
            if (choices.isEmpty()) return List.of();
            int choice = choices.get(0);
            ArrayList<SearchState> children = new ArrayList<>(2);

            BitSet excluded = (BitSet) state.excluded().clone();
            excluded.set(choice);
            children.add(transportState(
                state.layout(), state.enabled(), excluded, state.conflictBlocked(), evaluated));

            if (!state.conflictBlocked().get(choice)) {
                BitSet enabled = (BitSet) state.enabled().clone();
                enabled.set(choice);
                BitSet blocked = (BitSet) state.conflictBlocked().clone();
                blocked.or(domain.conflicts(choice));
                children.add(transportState(
                    state.layout(), enabled, state.excluded(), blocked, false));
            }
            return List.copyOf(children);
        }

        private LayoutState layoutState(int cursor, List<DrillCandidate> selected) {
            budget.checkpoint();
            ArrayList<DrillCandidate> potential = new ArrayList<>(selected);
            potential.addAll(candidates.subList(cursor, candidates.size()));
            float production = 0f;
            LinkedHashSet<TileKey> coverage = new LinkedHashSet<>();
            CostVector materials = CostVector.empty();
            int space = 0;
            for (DrillCandidate drill : potential) {
                production += drill.production().perSecond();
                coverage.addAll(drill.coveredOreCells());
            }
            for (DrillCandidate drill : selected) {
                materials = materials.plus(drill.cost());
                space += drill.footprint().tiles().size();
            }
            ObjectiveValue optimistic = new ObjectiveValue(
                production,
                coverage.size(),
                materials,
                space,
                selected.size(),
                "bound"
            );
            return new LayoutState(cursor, selected, optimistic,
                "L:" + cursor + ":" + candidateIds(selected));
        }

        private TransportState transportState(
            MiningLayout layout,
            BitSet enabled,
            BitSet excluded,
            BitSet conflictBlocked,
            boolean evaluated
        ) {
            budget.checkpoint();
            LinkedHashSet<TileKey> coverage = new LinkedHashSet<>();
            CostVector materials = CostVector.empty();
            int space = 0;
            for (DrillCandidate drill : layout.candidates()) {
                coverage.addAll(drill.coveredOreCells());
                materials = materials.plus(drill.cost());
                space += drill.footprint().tiles().size();
            }
            int transportBlocks = 0;
            for (int index = enabled.nextSetBit(0); index >= 0; index = enabled.nextSetBit(index + 1)) {
                for (TransportPlacement placement : domain.configuration(index).placements()) {
                    materials = materials.plus(placement.spec().cost());
                    space++;
                    transportBlocks++;
                }
            }
            ObjectiveValue optimistic = new ObjectiveValue(
                layout.productionPerSecond(),
                coverage.size(),
                materials,
                space,
                layout.candidates().size() + transportBlocks,
                "bound"
            );
            String id = "T:" + candidateIds(layout.candidates()) + ":E" + bits(enabled)
                + ":X" + bits(excluded) + ":B" + bits(conflictBlocked) + ":V" + evaluated;
            return new TransportState(
                layout, enabled, excluded, conflictBlocked, evaluated, optimistic, id);
        }

        private List<Integer> frontierChoices(TransportState state) {
            BitSet choices = new BitSet(domain.size());
            if (state.enabled().isEmpty()) {
                for (int index : domain.terminatingAt(scope.exit())) choices.set(index);
            } else {
                for (int index = state.enabled().nextSetBit(0); index >= 0;
                     index = state.enabled().nextSetBit(index + 1)) {
                    for (TransportPlacement receiver : domain.configuration(index).placements()) {
                        for (int feeder : domain.feeding(receiver)) choices.set(feeder);
                    }
                }
            }
            choices.andNot(state.enabled());
            choices.andNot(state.excluded());
            choices.andNot(state.conflictBlocked());
            ArrayList<Integer> result = new ArrayList<>();
            for (int index = choices.nextSetBit(0); index >= 0; index = choices.nextSetBit(index + 1)) {
                result.add(index);
            }
            return List.copyOf(result);
        }

        private boolean allDrillsFed(MiningLayout layout, BitSet enabled) {
            ArrayList<TransportPlacement> receivers = new ArrayList<>();
            for (int index = enabled.nextSetBit(0); index >= 0; index = enabled.nextSetBit(index + 1)) {
                receivers.addAll(domain.configuration(index).placements());
            }
            for (DrillCandidate drill : layout.candidates()) {
                boolean fed = false;
                for (TransportPlacement receiver : receivers) {
                    for (TileKey source : drill.footprint().tiles()) {
                        if (TransportGeometry.acceptsAdjacent(receiver, source, null)) {
                            fed = true;
                            break;
                        }
                    }
                    if (fed) break;
                }
                if (!fed) return false;
            }
            return true;
        }

        private OptimalPlanningResult heuristic() {
            diagnostics.add(PlannerDiagnostic.of(DiagnosticCode.SEARCH_LIMIT_REACHED));
            ObjectiveValue lower = incumbent == null
                ? ObjectiveValue.zero("none") : incumbent.objective();
            return result(
                incumbent == null ? SearchVerdict.SEARCH_INCOMPLETE : SearchVerdict.CURRENT_BEST,
                lower,
                rootUpper,
                SearchStopReason.HEURISTIC_MODE,
                pending.size()
            );
        }

        private boolean canInclude(DrillCandidate candidate, List<DrillCandidate> selected) {
            for (DrillCandidate current : selected) {
                if (candidate.id().equals(current.id())
                    || candidate.conflictIds().contains(current.id())
                    || current.conflictIds().contains(candidate.id())
                    || candidate.footprint().overlaps(current.footprint())) {
                    return false;
                }
            }
            return true;
        }

        private void consider(EvaluatedMiningPlan candidate, boolean fromProof) {
            if (incumbent == null
                || order.compare(candidate.objective(), incumbent.objective()) > 0
                || fromProof && !incumbentFromProof
                    && order.equivalentRank(candidate.objective(), incumbent.objective())) {
                incumbent = candidate;
                incumbentFromProof = fromProof;
                accepted++;
            }
            ArrayList<ServiceBundle> values = new ArrayList<>(frontier);
            values.add(candidate.bundle());
            frontier.clear();
            frontier.addAll(pareto.frontier(values));
        }

        private OptimalPlanningResult limited(SearchStopReason reason) {
            ObjectiveValue lower = incumbent == null
                ? ObjectiveValue.zero("none") : incumbent.objective();
            ObjectiveValue upper = pending.isEmpty() ? lower : pending.peek().optimistic();
            if (order.compare(upper, lower) < 0) upper = lower;
            diagnostics.add(PlannerDiagnostic.of(DiagnosticCode.SEARCH_LIMIT_REACHED));
            SearchVerdict verdict = incumbent == null
                ? SearchVerdict.SEARCH_INCOMPLETE : SearchVerdict.CURRENT_BEST;
            return result(verdict, lower, upper, reason, pending.size());
        }

        private OptimalPlanningResult exhausted() {
            boolean proofEligible = scope.modelComplete()
                && (incumbent == null || incumbent.proofEligible());
            if (!proofEligible) {
                diagnostics.add(new PlannerDiagnostic(
                    DiagnosticCode.UNSUPPORTED_CONTENT,
                    Map.of("detail", "model-incomplete")
                ));
                ObjectiveValue lower = incumbent == null
                    ? ObjectiveValue.zero("none") : incumbent.objective();
                return result(
                    incumbent == null ? SearchVerdict.SEARCH_INCOMPLETE : SearchVerdict.CURRENT_BEST,
                    lower,
                    rootUpper,
                    SearchStopReason.MODEL_INCOMPLETE,
                    0
                );
            }
            if (incumbent == null) {
                ObjectiveValue zero = ObjectiveValue.zero("none");
                return result(SearchVerdict.PROVEN_INFEASIBLE, zero, zero, SearchStopReason.NONE, 0);
            }
            return result(
                SearchVerdict.PATCH_OPTIMAL,
                incumbent.objective(),
                incumbent.objective(),
                SearchStopReason.NONE,
                0
            );
        }


        private OptimalPlanningResult result(
            SearchVerdict verdict,
            ObjectiveValue lower,
            ObjectiveValue upper,
            SearchStopReason reason,
            int pendingStates
        ) {
            OptimalityCertificate certificate = new OptimalityCertificate(
                verdict,
                scope,
                explored,
                pruned,
                pendingStates,
                lower,
                upper,
                reason,
                List.copyOf(diagnostics)
            );
            publish(
                PlanningProgress.Stage.COMPLETE,
                incumbent == null ? MiningLayout.of(List.of()) : incumbent.layout(),
                incumbent == null ? null : incumbent.graph(),
                null
            );
            return new OptimalPlanningResult(
                incumbent == null ? null : incumbent.layout(),
                incumbent == null ? null : incumbent.graph(),
                incumbent == null ? null : incumbent.support(),
                incumbent == null ? null : incumbent.bundle(),
                incumbent == null ? null : incumbent.simulation(),
                incumbent == null ? null : incumbent.objective(),
                List.copyOf(frontier),
                certificate,
                List.copyOf(diagnostics)
            );
        }

        private void publish(
            PlanningProgress.Stage stage,
            MiningLayout attempted,
            autodrillnext.model.PlanGraph route,
            autodrillnext.model.RoutingProgress routing
        ) {
            progress.accept(new PlanningProgress(
                attempted,
                route,
                incumbent == null ? null : incumbent.layout(),
                incumbent == null ? null : incumbent.graph(),
                routing,
                stage,
                new PlanningProgress.Counters(
                    layoutStates,
                    transportStates,
                    explored,
                    pruned,
                    pending.size(),
                    accepted,
                    diagnostics.size()
                ),
                List.of()
            ));
        }

        private String candidateIds(List<DrillCandidate> values) {
            return String.join(",", values.stream().map(DrillCandidate::id).sorted().toList());
        }

        private String bits(BitSet value) {
            return value.stream().mapToObj(Integer::toString)
                .collect(java.util.stream.Collectors.joining(","));
        }
    }
}
