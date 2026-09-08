package autodrillnext.solver;

import autodrillnext.capability.spec.CapabilitySnapshot;
import autodrillnext.capability.spec.CostVector;
import autodrillnext.capability.spec.SupportVariant;
import autodrillnext.model.ContentId;
import autodrillnext.model.DrillCandidate;
import autodrillnext.model.ExistingNetwork;
import autodrillnext.model.MiningLayout;
import autodrillnext.model.PlanEdge;
import autodrillnext.model.PlanGraph;
import autodrillnext.model.PlanNode;
import autodrillnext.model.ProductionEstimate;
import autodrillnext.model.SupportRequirement;
import autodrillnext.model.LiquidId;
import autodrillnext.world.PlacementFootprint;
import autodrillnext.world.TerrainRevision;
import autodrillnext.world.TerrainSnapshot;
import autodrillnext.world.TileKey;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SupportPlannerTest {
    @Test
    void mandatoryPowerFailureRejectsTheWholeLayout() {
        DrillCandidate candidate = candidate(
            List.of(new SupportRequirement(SupportRequirement.Kind.POWER, List.of(), 5f, true)),
            List.of()
        );

        SupportPlan plan = new SupportPlanner().solve(
            MiningLayout.of(List.of(candidate)),
            flowingGraph(10f),
            emptyTerrain(),
            new CapabilitySnapshot(Map.of()),
            ExistingNetwork.empty()
        );

        assertFalse(plan.feasible());
        assertEquals(autodrillnext.model.DiagnosticCode.NO_POWER_SOURCE, plan.diagnostics().get(0).code());
    }

    @Test
    void unsupportedOptionalBoostFallsBackToNoBoostUsingFinalQout() {
        LiquidId water = LiquidId.of("mindustry:water");
        SupportVariant boost = new SupportVariant(
            ContentId.of("mod:water-boost"),
            new SupportRequirement(SupportRequirement.Kind.BOOSTER, List.of(water), 2f, false),
            2f
        );
        DrillCandidate candidate = candidate(List.of(), List.of(boost));

        SupportPlan plan = new SupportPlanner().solve(
            MiningLayout.of(List.of(candidate)),
            flowingGraph(10f),
            emptyTerrain(),
            new CapabilitySnapshot(Map.of()),
            ExistingNetwork.empty()
        );

        assertEquals("none", plan.variantId());
        assertEquals(10f, plan.finalQout());
    }

    @Test
    void explicitNoLiquidSelectionSkipsOptionalBoost() {
        LiquidId water = LiquidId.of("mindustry:water");
        SupportVariant boost = new SupportVariant(
            ContentId.of("mod:water-boost"),
            new SupportRequirement(SupportRequirement.Kind.BOOSTER, List.of(water), 2f, false),
            2f
        );
        DrillCandidate candidate = candidate(List.of(), List.of(boost));

        SupportPlan plan = new SupportPlanner().solve(
            MiningLayout.of(List.of(candidate)),
            flowingGraph(10f),
            emptyTerrain(),
            new CapabilitySnapshot(Map.of()),
            ExistingNetwork.empty(),
            ""
        );

        assertEquals("none", plan.variantId());
        assertEquals(10f, plan.finalQout());
    }

    @Test
    void automaticLinksReserveSlotsOnEveryReachableGraph() {
        var demand = new SupportRequirement(SupportRequirement.Kind.POWER, List.of(), 1f, true);
        DrillCandidate first = candidate("first", 0, List.of(demand), List.of());
        DrillCandidate second = candidate("second", 4, List.of(demand), List.of());
        var firstAccess = new autodrillnext.world.PowerAccess(1, new TileKey(0, 5), 100f, 1, true);
        var secondAccess = new autodrillnext.world.PowerAccess(2, new TileKey(4, 5), 100f, 1, true);
        var rules = new autodrillnext.world.PlacementRules(
            Map.of(first.drillId(), Map.of(first.anchor(), 1, second.anchor(), 1)),
            Map.of(),
            Map.of(
                new autodrillnext.world.PlacementRules.Placement(first.drillId(), first.anchor(), 0),
                List.of(firstAccess, secondAccess),
                new autodrillnext.world.PlacementRules.Placement(second.drillId(), second.anchor(), 0),
                List.of(secondAccess)
            )
        );
        TerrainSnapshot terrain = TerrainSnapshot.of(autodrillnext.world.WorldSnapshot.captured(
            Map.of(), TerrainRevision.of(1), rules));

        SupportPlan plan = new SupportPlanner().solve(
            MiningLayout.of(List.of(first, second)), routedGraph(List.of(first, second), 100f),
            terrain, new CapabilitySnapshot(Map.of()), ExistingNetwork.empty());

        assertFalse(plan.feasible());
        assertEquals(autodrillnext.model.DiagnosticCode.NO_POWER_SOURCE, plan.diagnostics().get(0).code());
    }

    @Test
    void mandatoryAndOptionalLiquidCannotSpendTheSameSupply() {
        LiquidId water = LiquidId.of("mindustry:water");
        DrillCandidate drill = candidate(
            List.of(new SupportRequirement(SupportRequirement.Kind.LIQUID, List.of(water), 2f, true)),
            List.of(waterBoost(water)));
        SupportPlan plan = new SupportPlanner().solve(
            MiningLayout.of(List.of(drill)), routedGraph(List.of(drill), 100f),
            emptyTerrain(), new CapabilitySnapshot(Map.of()),
            new ExistingNetwork(Set.of(), Set.of(), 0f, Map.of(water, 3f)));

        assertTrue(plan.feasible());
        assertEquals(10f, plan.finalQout(), 0.0001f);
        assertEquals(2f, plan.liquidDemands().stream().map(demand -> demand.perSecond())
            .reduce(0f, Float::sum), 0.0001f);
    }

    @Test
    void limitedBoosterSupplyOnlyIncreasesTheSupportedDrillsOutput() {
        LiquidId water = LiquidId.of("mindustry:water");
        DrillCandidate first = candidate("first", 0, List.of(), List.of(waterBoost(water)));
        DrillCandidate second = candidate("second", 4, List.of(), List.of(waterBoost(water)));
        SupportPlan plan = new SupportPlanner().solve(
            MiningLayout.of(List.of(first, second)), routedGraph(List.of(first, second), 100f),
            emptyTerrain(), new CapabilitySnapshot(Map.of()),
            new ExistingNetwork(Set.of(), Set.of(), 0f, Map.of(water, 3f)));

        assertTrue(plan.feasible());
        assertEquals(30f, plan.finalQout(), 0.0001f);
    }

    @Test
    void boostingCannotExceedTheUnchangedTransportCapacity() {
        LiquidId water = LiquidId.of("mindustry:water");
        DrillCandidate drill = candidate(List.of(), List.of(waterBoost(water)));
        SupportPlan plan = new SupportPlanner().solve(
            MiningLayout.of(List.of(drill)), routedGraph(List.of(drill), 10f),
            emptyTerrain(), new CapabilitySnapshot(Map.of()),
            new ExistingNetwork(Set.of(), Set.of(), 0f, Map.of(water, 3f)),
            water.value());

        assertTrue(plan.feasible());
        assertEquals(10f, plan.finalQout(), 0.0001f);
    }

    @Test
    void solveAllReturnsEveryFeasibleBoostCombinationInCanonicalOrder() {
        LiquidId water = LiquidId.of("mindustry:water");
        DrillCandidate first = candidate("a", 0, List.of(), List.of(waterBoost(water)));
        DrillCandidate second = candidate("b", 4, List.of(), List.of(waterBoost(water)));

        List<SupportPlan> plans = new SupportPlanner().solveAll(
            MiningLayout.of(List.of(first, second)),
            routedGraph(List.of(first, second), 100f),
            emptyTerrain(),
            new CapabilitySnapshot(Map.of()),
            new ExistingNetwork(Set.of(), Set.of(), 0f, Map.of(water, 5f)),
            null
        );

        assertEquals(
            List.of(
                "none",
                "a=mod:water-boost",
                "b=mod:water-boost",
                "a=mod:water-boost;b=mod:water-boost"
            ),
            plans.stream().map(SupportPlan::variantId).toList()
        );
    }

    @Test
    void runtimePowerBacktracksAcrossReachableGraphs() {
        SupportRequirement demand =
            new SupportRequirement(SupportRequirement.Kind.POWER, List.of(), 1f, true);
        DrillCandidate first = candidate("a", 0, List.of(demand), List.of());
        DrillCandidate second = candidate("b", 4, List.of(demand), List.of());
        var graphOne = new autodrillnext.world.PowerAccess(1, new TileKey(0, 5), 1.1f, 2, false);
        var graphTwo = new autodrillnext.world.PowerAccess(2, new TileKey(4, 5), 1.1f, 2, false);
        var rules = new autodrillnext.world.PlacementRules(
            Map.of(first.drillId(), Map.of(first.anchor(), 1, second.anchor(), 1)),
            Map.of(),
            Map.of(
                new autodrillnext.world.PlacementRules.Placement(first.drillId(), first.anchor(), 0),
                List.of(graphOne, graphTwo),
                new autodrillnext.world.PlacementRules.Placement(second.drillId(), second.anchor(), 0),
                List.of(graphOne)
            )
        );
        TerrainSnapshot terrain = TerrainSnapshot.of(autodrillnext.world.WorldSnapshot.captured(
            Map.of(first.anchor(), autodrillnext.world.TileState.empty(),
                second.anchor(), autodrillnext.world.TileState.empty()), TerrainRevision.of(1), rules));

        SupportPlan plan = new SupportPlanner().solve(
            MiningLayout.of(List.of(first, second)),
            routedGraph(List.of(first, second), 100f),
            terrain,
            new CapabilitySnapshot(Map.of()),
            ExistingNetwork.empty()
        );

        assertTrue(plan.feasible(), plan.diagnostics().toString());
    }

    private SupportVariant waterBoost(LiquidId water) {
        return new SupportVariant(ContentId.of("mod:water-boost"),
            new SupportRequirement(SupportRequirement.Kind.BOOSTER, List.of(water), 2f, false), 2f);
    }

    private PlanGraph routedGraph(List<DrillCandidate> drills, float capacity) {
        java.util.ArrayList<PlanNode> nodes = new java.util.ArrayList<>();
        java.util.ArrayList<PlanEdge> edges = new java.util.ArrayList<>();
        nodes.add(PlanNode.junction("merge", new TileKey(5, 0)));
        nodes.add(PlanNode.sink("sink", new TileKey(6, 0)));
        for (DrillCandidate drill : drills) {
            String id = "source:" + drill.id();
            nodes.add(PlanNode.source(id, drill.production().transportPerSecond(), drill.anchor()));
            edges.add(PlanEdge.source(id + ":feed", id, "merge", drill.production().transportPerSecond()));
        }
        edges.add(PlanEdge.transport("belt", autodrillnext.model.EdgeKind.GROUND_EDGE, "merge", "sink",
            capacity, capacity, CostVector.empty(), "belt"));
        return PlanGraph.of(nodes, edges, "sink");
    }

    private DrillCandidate candidate(List<SupportRequirement> mandatory, List<SupportVariant> variants) {
        return candidate("drill", 0, mandatory, variants);
    }

    private DrillCandidate candidate(String id, int x, List<SupportRequirement> mandatory, List<SupportVariant> variants) {
        TileKey anchor = new TileKey(x, 0);
        return new DrillCandidate(
            id,
            ContentId.of("mod:drill"),
            anchor,
            0,
            PlacementFootprint.of(Set.of(anchor)),
            Set.of(anchor),
            new ProductionEstimate(ContentId.of("mindustry:copper"), 10f),
            mandatory,
            variants,
            CostVector.empty(),
            Set.of()
        );
    }

    private PlanGraph flowingGraph(float qout) {
        PlanNode source = PlanNode.source("source", qout, new TileKey(0, 0));
        PlanNode sink = PlanNode.sink("sink", new TileKey(1, 0));
        PlanEdge edge = PlanEdge.transport("flow", autodrillnext.model.EdgeKind.GROUND_EDGE, "source", "sink",
            qout, qout, CostVector.empty(), "belt");
        return PlanGraph.of(List.of(source, sink), List.of(edge), "sink")
            .withFlow(new autodrillnext.model.FlowAssignment(qout, Map.of("flow", qout), Map.of("flow", 1f), true));
    }

    private TerrainSnapshot emptyTerrain() {
        return TerrainSnapshot.of(Map.of(), TerrainRevision.of(1));
    }
}
