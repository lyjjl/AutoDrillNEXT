package autodrillnext.solver;

import autodrillnext.capability.spec.CapabilitySnapshot;
import autodrillnext.capability.spec.SupportVariant;
import autodrillnext.model.DependencyKind;
import autodrillnext.model.DiagnosticCode;
import autodrillnext.model.DrillCandidate;
import autodrillnext.model.ExistingNetwork;
import autodrillnext.model.LiquidDemand;
import autodrillnext.model.MiningLayout;
import autodrillnext.model.PlannerDiagnostic;
import autodrillnext.model.PowerDemand;
import autodrillnext.model.SearchBudget;
import autodrillnext.model.SupportRequirement;
import autodrillnext.world.TerrainSnapshot;
import autodrillnext.world.TileKey;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Set;

public final class SupportPlanner {
    private static final float DEFAULT_MARGIN = 1.10f;
    private final PowerSupportSolver power = new PowerSupportSolver();
    private final LiquidSupportSolver liquid = new LiquidSupportSolver();

    public SupportPlan solve(
        MiningLayout layout,
        autodrillnext.model.PlanGraph graph,
        TerrainSnapshot terrain,
        CapabilitySnapshot capabilities,
        ExistingNetwork existingNetwork
    ) {
        return solve(layout, graph, terrain, capabilities, existingNetwork, null);
    }

    public SupportPlan solve(
        MiningLayout layout,
        autodrillnext.model.PlanGraph graph,
        TerrainSnapshot terrain,
        CapabilitySnapshot capabilities,
        ExistingNetwork existingNetwork,
        String selectedLiquidId
    ) {
        return solve(layout, graph, terrain, capabilities, existingNetwork, selectedLiquidId,
            SearchBudget.unlimited());
    }

    public SupportPlan solve(
        MiningLayout layout,
        autodrillnext.model.PlanGraph graph,
        TerrainSnapshot terrain,
        CapabilitySnapshot capabilities,
        ExistingNetwork existingNetwork,
        String selectedLiquidId,
        SearchBudget budget
    ) {
        EnumerationResult result = enumerateAll(
            layout, graph, terrain, capabilities, existingNetwork, selectedLiquidId, budget);
        if (result.plans().isEmpty()) return result.failure();
        return result.plans().stream()
            .sorted(java.util.Comparator.comparingDouble(SupportPlan::finalQout).reversed()
                .thenComparing(SupportPlan::variantId))
            .findFirst()
            .orElseThrow();
    }

    public List<SupportPlan> solveAll(
        MiningLayout layout,
        autodrillnext.model.PlanGraph graph,
        TerrainSnapshot terrain,
        CapabilitySnapshot capabilities,
        ExistingNetwork existingNetwork,
        String selectedLiquidId
    ) {
        return solveAll(layout, graph, terrain, capabilities, existingNetwork, selectedLiquidId,
            SearchBudget.unlimited());
    }

    public List<SupportPlan> solveAll(
        MiningLayout layout,
        autodrillnext.model.PlanGraph graph,
        TerrainSnapshot terrain,
        CapabilitySnapshot capabilities,
        ExistingNetwork existingNetwork,
        String selectedLiquidId,
        SearchBudget budget
    ) {
        return enumerateAll(
            layout, graph, terrain, capabilities, existingNetwork, selectedLiquidId, budget).plans();
    }

    private EnumerationResult enumerateAll(
        MiningLayout layout,
        autodrillnext.model.PlanGraph graph,
        TerrainSnapshot terrain,
        CapabilitySnapshot capabilities,
        ExistingNetwork existingNetwork,
        String selectedLiquidId,
        SearchBudget budget
    ) {
        ArrayList<PowerDemand> basePower = new ArrayList<>();
        ArrayList<LiquidDemand> baseLiquid = new ArrayList<>();
        for (DrillCandidate candidate : layout.candidates()) {
            for (SupportRequirement requirement : candidate.mandatorySupport()) {
                addDemand(requirement, basePower, baseLiquid);
            }
        }
        for (var placement : graph.placements()) {
            if (placement.spec().powerPerSecond() > 0f) {
                basePower.add(new PowerDemand(placement.spec().powerPerSecond(), true));
            }
        }

        ArrayList<DrillCandidate> candidates = new ArrayList<>(layout.candidates());
        candidates.sort(java.util.Comparator.comparing(DrillCandidate::id));
        EnumerationState state = new EnumerationState();
        enumerate(
            0,
            candidates,
            new LinkedHashMap<>(),
            new SupportFacts(
                layout,
                graph,
                terrain,
                capabilities,
                existingNetwork,
                selectedLiquidId,
                List.copyOf(basePower),
                List.copyOf(baseLiquid),
                budget,
                state
            )
        );
        ArrayList<SupportPlan> plans = new ArrayList<>(state.plans.values());
        plans.sort(java.util.Comparator.comparingInt(this::selectedVariantCount)
            .thenComparing(SupportPlan::variantId));
        SupportPlan failure = state.failure == null
            ? SupportPlan.invalid(
                0f,
                "selected-liquid",
                new PowerDemand(0f, false),
                baseLiquid,
                Set.of(),
                PlannerDiagnostic.of(DiagnosticCode.NO_LIQUID_SOURCE)
            )
            : state.failure;
        return new EnumerationResult(List.copyOf(plans), failure);
    }

    private void enumerate(
        int index,
        List<DrillCandidate> drills,
        LinkedHashMap<String, SupportVariant> selected,
        SupportFacts facts
    ) {
        facts.budget().checkpoint();
        if (index == drills.size()) {
            evaluateSelection(selected, facts);
            return;
        }
        DrillCandidate drill = drills.get(index);
        enumerate(index + 1, drills, selected, facts);
        for (SupportVariant variant : allowedVariants(drill, facts.selectedLiquidId())) {
            selected.put(drill.id(), variant);
            enumerate(index + 1, drills, selected, facts);
            selected.remove(drill.id());
        }
    }

    private List<SupportVariant> allowedVariants(
        DrillCandidate drill,
        String selectedLiquidId
    ) {
        if (selectedLiquidId != null && selectedLiquidId.isBlank()) return List.of();
        ArrayList<SupportVariant> variants = new ArrayList<>(drill.supportVariants());
        variants.sort(java.util.Comparator.comparing(variant -> variant.id().value()));
        if (selectedLiquidId == null) return variants;
        return variants.stream()
            .filter(variant -> usesLiquid(variant, selectedLiquidId))
            .map(variant -> restrictToLiquid(variant, selectedLiquidId))
            .toList();
    }

    private void evaluateSelection(
        LinkedHashMap<String, SupportVariant> selected,
        SupportFacts facts
    ) {
        if (facts.selectedLiquidId() != null
            && !facts.selectedLiquidId().isBlank()
            && selected.isEmpty()) {
            return;
        }
        ArrayList<PowerDemand> powerDemands = new ArrayList<>(facts.basePower());
        ArrayList<LiquidDemand> liquidDemands = new ArrayList<>(facts.baseLiquid());
        selected.values().forEach(variant ->
            addDemand(variant.requirement(), powerDemands, liquidDemands));
        SupportPlan support = mandatorySupport(
            powerDemands,
            liquidDemands,
            facts.capabilities(),
            facts.layout(),
            facts.graph(),
            facts.terrain(),
            facts.existingNetwork(),
            selected,
            facts.budget()
        );
        if (!support.feasible()) {
            if (facts.state().failure == null) facts.state().failure = support;
            return;
        }
        String variantId = selected.isEmpty()
            ? "none"
            : selected.entrySet().stream()
                .map(entry -> entry.getKey() + "=" + entry.getValue().id().value())
                .collect(java.util.stream.Collectors.joining(";"));
        facts.state().plans.putIfAbsent(
            variantId,
            SupportPlan.valid(
                transportedOutput(facts.layout(), facts.graph(), selected, facts.budget()),
                variantId,
                support.powerDemand(),
                support.liquidDemands(),
                support.dependencies()
            )
        );
    }

    private int selectedVariantCount(SupportPlan plan) {
        return plan.variantId().equals("none") ? 0 : plan.variantId().split(";").length;
    }

    private record SupportFacts(
        MiningLayout layout,
        autodrillnext.model.PlanGraph graph,
        TerrainSnapshot terrain,
        CapabilitySnapshot capabilities,
        ExistingNetwork existingNetwork,
        String selectedLiquidId,
        List<PowerDemand> basePower,
        List<LiquidDemand> baseLiquid,
        SearchBudget budget,
        EnumerationState state
    ) {}

    private record EnumerationResult(List<SupportPlan> plans, SupportPlan failure) {}

    private static final class EnumerationState {
        final LinkedHashMap<String, SupportPlan> plans = new LinkedHashMap<>();
        SupportPlan failure;
    }

    private boolean usesLiquid(SupportVariant variant, String selectedLiquidId) {
        return variant.requirement().allowedLiquids().stream()
            .anyMatch(liquid -> liquid.value().equals(selectedLiquidId));
    }

    private SupportVariant restrictToLiquid(SupportVariant variant, String selectedLiquidId) {
        SupportRequirement requirement = variant.requirement();
        return new SupportVariant(
            variant.id(),
            new SupportRequirement(
                requirement.kind(),
                List.of(autodrillnext.model.LiquidId.of(selectedLiquidId)),
                requirement.demandPerSecond(),
                requirement.mandatory()
            ),
            variant.productionMultiplier()
        );
    }

    private SupportPlan mandatorySupport(
        List<PowerDemand> powerDemands,
        List<LiquidDemand> liquidDemands,
        CapabilitySnapshot capabilities,
        MiningLayout layout,
        autodrillnext.model.PlanGraph graph,
        TerrainSnapshot terrain,
        ExistingNetwork existingNetwork,
        Map<String, SupportVariant> selected,
        SearchBudget budget
    ) {
        SupportPlan powerPlan = terrain.hasRuntimeRules()
            ? runtimePower(layout, graph, terrain, selected, budget)
            : power.solve(powerDemands, existingNetwork, capabilities);
        if (!powerPlan.feasible()) return powerPlan;
        SupportPlan liquidPlan = liquid.solve(
            liquidDemands, existingNetwork, capabilities, DEFAULT_MARGIN, budget);
        if (!liquidPlan.feasible()) {
            return SupportPlan.invalid(
                0f,
                "mandatory",
                powerPlan.powerDemand(),
                liquidDemands,
                powerPlan.dependencies(),
                liquidPlan.diagnostics().get(0)
            );
        }
        LinkedHashSet<DependencyKind> dependencies = new LinkedHashSet<>(powerPlan.dependencies());
        dependencies.addAll(liquidPlan.dependencies());
        return SupportPlan.valid(
            0f,
            "mandatory",
            powerPlan.powerDemand(),
            liquidDemands,
            dependencies
        );
    }

    private float transportedOutput(
        MiningLayout layout,
        autodrillnext.model.PlanGraph graph,
        Map<String, SupportVariant> selected,
        SearchBudget budget
    ) {
        if (selected.isEmpty()) {
            return graph.flow() == null ? new FlowSolver().assign(graph, budget).qOut() : graph.qOut();
        }
        Map<autodrillnext.world.TileKey, Float> anchorMultipliers = new java.util.HashMap<>();
        for (DrillCandidate drill : layout.candidates()) {
            SupportVariant variant = selected.get(drill.id());
            if (variant != null) anchorMultipliers.put(drill.anchor(), variant.productionMultiplier());
        }
        Map<String, Float> sourceMultipliers = new java.util.HashMap<>();
        ArrayList<autodrillnext.model.PlanNode> nodes = new ArrayList<>();
        for (var node : graph.nodes()) {
            float multiplier = node.kind() == autodrillnext.model.PlanNode.Kind.SOURCE
                ? anchorMultipliers.getOrDefault(node.tile(), 1f) : 1f;
            if (multiplier != 1f) sourceMultipliers.put(node.id(), multiplier);
            nodes.add(multiplier == 1f ? node : new autodrillnext.model.PlanNode(
                node.id(), node.kind(), node.tile(), node.productionPerSecond() * multiplier, node.output()));
        }
        ArrayList<autodrillnext.model.PlanEdge> edges = new ArrayList<>();
        for (var edge : graph.edges()) {
            float multiplier = edge.kind() == autodrillnext.model.EdgeKind.SOURCE_EDGE
                ? sourceMultipliers.getOrDefault(edge.from(), 1f) : 1f;
            // Only the source feed grows. Belts, bridges and sink limits remain physical bottlenecks.
            edges.add(multiplier == 1f ? edge : new autodrillnext.model.PlanEdge(
                edge.id(), edge.kind(), edge.from(), edge.to(),
                edge.nominalCapacity() * multiplier, edge.usableCapacity() * multiplier, 0f,
                edge.cost(), edge.transportId(), edge.footprint(), edge.dependencies()));
        }
        return new FlowSolver().assign(
            autodrillnext.model.PlanGraph.of(nodes, edges, graph.sinkId()), budget).qOut();
    }

    private SupportPlan runtimePower(
        MiningLayout layout,
        autodrillnext.model.PlanGraph graph,
        TerrainSnapshot terrain,
        Map<String, SupportVariant> selected,
        SearchBudget budget
    ) {
        ArrayList<PowerNeed> needs = new ArrayList<>();
        for (DrillCandidate drill : layout.candidates()) {
            float demand = 0f;
            for (SupportRequirement requirement : drill.mandatorySupport()) {
                if (powerRequirement(requirement)) demand += requirement.demandPerSecond();
            }
            SupportVariant variant = selected.get(drill.id());
            if (variant != null && powerRequirement(variant.requirement())) {
                demand += variant.requirement().demandPerSecond();
            }
            if (demand > 0f || !terrain.powerAccess(
                drill.drillId(), drill.anchor(), drill.rotation()).isEmpty()) {
                needs.add(new PowerNeed(drill.drillId(), drill.anchor(), drill.rotation(), demand));
            }
        }
        for (var placement : graph.placements()) {
            if (placement.spec().powerPerSecond() > 0f
                || !terrain.powerAccess(
                    placement.spec().id(), placement.tile(), placement.rotation()).isEmpty()) {
                needs.add(new PowerNeed(
                    placement.spec().id(),
                    placement.tile(),
                    placement.rotation(),
                    placement.spec().powerPerSecond()
                ));
            }
        }
        needs.sort(java.util.Comparator.comparing(PowerNeed::id).thenComparing(PowerNeed::tile));

        java.util.Map<autodrillnext.world.TileKey, Integer> slots = new java.util.HashMap<>();
        java.util.Set<LinkUse> reserved = new java.util.HashSet<>();
        java.util.Map<Integer, Float> remaining = new java.util.TreeMap<>();
        ArrayList<PowerAssignment> assignments = new ArrayList<>();
        float total = 0f;
        for (PowerNeed need : needs) {
            total += need.amount();
            var accesses = terrain.powerAccess(need.id(), need.tile(), need.rotation());
            java.util.TreeSet<Integer> graphIds = new java.util.TreeSet<>();
            for (var access : accesses) {
                remaining.merge(access.graphId(), access.availablePerSecond(), Math::min);
                graphIds.add(access.graphId());
                if (!access.requiresLink()
                    || !reserved.add(new LinkUse(need.tile(), access.connector()))) {
                    continue;
                }
                int free = slots.computeIfAbsent(
                    access.connector(),
                    ignored -> access.freeConnections()
                );
                if (free <= 0) return noPower(total, need.tile());
                slots.put(access.connector(), free - 1);
            }
            if (need.amount() > 0f && graphIds.isEmpty()) return noPower(total, need.tile());
            assignments.add(new PowerAssignment(need, List.copyOf(graphIds)));
        }
        assignments.sort(java.util.Comparator
            .comparingInt((PowerAssignment assignment) -> assignment.graphIds().size())
            .thenComparing(
                java.util.Comparator.comparingDouble(
                    (PowerAssignment assignment) -> assignment.need().amount()
                ).reversed()
            )
            .thenComparing(assignment -> assignment.need().id())
            .thenComparing(assignment -> assignment.need().tile()));
        if (!assignPower(0, assignments, remaining, budget)) {
            TileKey tile = assignments.isEmpty()
                ? new TileKey(0, 0)
                : assignments.get(0).need().tile();
            return noPower(total, tile);
        }
        return SupportPlan.valid(
            0f,
            "power-existing",
            new PowerDemand(total, total > 0f),
            List.of(),
            total > 0f ? Set.of(DependencyKind.POWER_LINK) : Set.of()
        );
    }

    private boolean assignPower(
        int index,
        List<PowerAssignment> assignments,
        java.util.Map<Integer, Float> remaining,
        SearchBudget budget
    ) {
        budget.checkpoint();
        if (index == assignments.size()) return true;
        PowerAssignment assignment = assignments.get(index);
        float required = assignment.need().amount() * DEFAULT_MARGIN;
        for (int graphId : assignment.graphIds()) {
            float available = remaining.getOrDefault(graphId, 0f);
            if (available + 0.0001f < required) continue;
            remaining.put(graphId, available - required);
            if (assignPower(index + 1, assignments, remaining, budget)) return true;
            remaining.put(graphId, available);
        }
        return required <= 0f && assignPower(index + 1, assignments, remaining, budget);
    }

    private SupportPlan noPower(float total, TileKey tile) {
        return SupportPlan.invalid(
            0f,
            "power-existing",
            new PowerDemand(total, true),
            List.of(),
            Set.of(),
            new PlannerDiagnostic(
                DiagnosticCode.NO_POWER_SOURCE,
                java.util.Map.of("tile", tile.x() + "," + tile.y())
            )
        );
    }

    private record PowerAssignment(PowerNeed need, List<Integer> graphIds) {}
    private record LinkUse(TileKey source, TileKey connector) {}

    private record PowerNeed(autodrillnext.model.ContentId id, autodrillnext.world.TileKey tile, int rotation, float amount) {}

    private boolean powerRequirement(SupportRequirement requirement) {
        return requirement.kind() == SupportRequirement.Kind.POWER
            || (requirement.kind() == SupportRequirement.Kind.BOOSTER && requirement.allowedLiquids().isEmpty());
    }

    private boolean liquidRequirement(SupportRequirement requirement) {
        return requirement.kind() == SupportRequirement.Kind.LIQUID
            || (requirement.kind() == SupportRequirement.Kind.BOOSTER && !requirement.allowedLiquids().isEmpty());
    }

    private void addDemand(
        SupportRequirement requirement,
        List<PowerDemand> powerDemands,
        List<LiquidDemand> liquidDemands
    ) {
        if (powerRequirement(requirement)) {
            powerDemands.add(new PowerDemand(requirement.demandPerSecond(), requirement.mandatory()));
        } else if (liquidRequirement(requirement)) {
            liquidDemands.add(new LiquidDemand(
                requirement.allowedLiquids(), requirement.demandPerSecond(), requirement.mandatory()
            ));
        }
    }


}
