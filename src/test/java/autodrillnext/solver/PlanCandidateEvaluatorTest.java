package autodrillnext.solver;

import autodrillnext.capability.spec.CapabilityDescriptor;
import autodrillnext.capability.spec.CapabilityKind;
import autodrillnext.capability.spec.CapabilitySnapshot;
import autodrillnext.capability.spec.CapabilityState;
import autodrillnext.capability.spec.CostVector;
import autodrillnext.capability.spec.ItemTransportSpec;
import autodrillnext.compile.PhysicalTransportGraphBuilder;
import autodrillnext.model.ContentId;
import autodrillnext.model.DiagnosticCode;
import autodrillnext.model.DrillCandidate;
import autodrillnext.model.ExistingNetwork;
import autodrillnext.model.Inventory;
import autodrillnext.model.ItemId;
import autodrillnext.model.MiningLayout;
import autodrillnext.model.PatchSearchScope;
import autodrillnext.model.PlannerRequest;
import autodrillnext.model.ProductionEstimate;
import autodrillnext.model.SearchBudget;
import autodrillnext.model.SupportRequirement;
import autodrillnext.model.TransportPlacement;
import autodrillnext.simulation.SimulationFidelity;
import autodrillnext.world.ExitAnchor;
import autodrillnext.world.ExitPort;
import autodrillnext.world.OrePatch;
import autodrillnext.world.PlacementFootprint;
import autodrillnext.world.TerrainRevision;
import autodrillnext.world.TileKey;
import autodrillnext.world.TileOffset;
import autodrillnext.world.TileState;
import autodrillnext.world.WorldSnapshot;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlanCandidateEvaluatorTest {
    private final PlanCandidateEvaluator evaluator = new PlanCandidateEvaluator();

    @Test
    void onlyFullyValidatedAffordablePlansBecomeIncumbents() {
        Fixture fixture = fixture(0.5f, List.of(), CostVector.empty(), Inventory.empty(), false,
            SimulationFidelity.BOUNDED_MODEL, true);

        PlanCandidateEvaluator.Evaluation evaluation = evaluator.evaluate(
            fixture.layout(), fixture.graph(), fixture.scope(), SearchBudget.unlimited());

        assertEquals(1, evaluation.plans().size());
        EvaluatedMiningPlan plan = evaluation.plans().get(0);
        assertTrue(plan.graph().flow().capacitySafe());
        assertTrue(plan.bundle().cost().materials().componentWiseAtMost(fixture.scope().inventory()));
    }

    @Test
    void unsupportedSimulationAllowsIncumbentButBlocksProof() {
        Fixture fixture = fixture(0.5f, List.of(), CostVector.empty(), Inventory.empty(), false,
            SimulationFidelity.UNSUPPORTED, true);

        EvaluatedMiningPlan plan = evaluator.evaluate(
            fixture.layout(), fixture.graph(), fixture.scope(), SearchBudget.unlimited()
        ).plans().get(0);

        assertFalse(plan.proofEligible());
        assertEquals(SimulationFidelity.UNSUPPORTED, plan.simulation().fidelity());
    }

    @Test
    void zeroFlowIsRejectedWithNoRoute() {
        assertRejected(
            fixture(0f, List.of(), CostVector.empty(), Inventory.empty(), false,
                SimulationFidelity.BOUNDED_MODEL, true),
            DiagnosticCode.NO_ROUTE
        );
    }

    @Test
    void unsatisfiedSupportPreservesNoPowerSource() {
        SupportRequirement power = new SupportRequirement(
            SupportRequirement.Kind.POWER, List.of(), 1f, true);
        assertRejected(
            fixture(0.5f, List.of(power), CostVector.empty(), Inventory.empty(), false,
                SimulationFidelity.BOUNDED_MODEL, true),
            DiagnosticCode.NO_POWER_SOURCE
        );
    }

    @Test
    void physicalFailurePreservesTerrainBlocked() {
        assertRejected(
            fixture(0.5f, List.of(), CostVector.empty(), Inventory.empty(), true,
                SimulationFidelity.BOUNDED_MODEL, true),
            DiagnosticCode.TERRAIN_OR_RULE_BLOCKED
        );
    }

    @Test
    void overBudgetPreservesBudgetDeficit() {
        CostVector drillCost = CostVector.of(Map.of(ItemId.of("copper"), 1));
        assertRejected(
            fixture(0.5f, List.of(), drillCost, Inventory.empty(), false,
                SimulationFidelity.BOUNDED_MODEL, true),
            DiagnosticCode.BUDGET_DEFICIT
        );
    }

    private void assertRejected(Fixture fixture, DiagnosticCode code) {
        PlanCandidateEvaluator.Evaluation evaluation = evaluator.evaluate(
            fixture.layout(), fixture.graph(), fixture.scope(), SearchBudget.unlimited());
        assertTrue(evaluation.plans().isEmpty());
        assertTrue(evaluation.diagnostics().stream().anyMatch(value -> value.code() == code),
            evaluation.diagnostics().toString());
    }

    private Fixture fixture(
        float production,
        List<SupportRequirement> mandatorySupport,
        CostVector drillCost,
        Inventory inventory,
        boolean blockedTransport,
        SimulationFidelity fidelity,
        boolean modelComplete
    ) {
        TileKey drillTile = new TileKey(0, 0);
        TileKey transportTile = new TileKey(1, 0);
        ContentId ore = ContentId.of("copper");
        ContentId drillId = ContentId.of("drill");
        ContentId beltId = ContentId.of("belt");
        DrillCandidate drill = new DrillCandidate(
            "drill@0,0",
            drillId,
            drillTile,
            0,
            PlacementFootprint.of(Set.of(drillTile)),
            Set.of(drillTile),
            new ProductionEstimate(ore, production),
            mandatorySupport,
            List.of(),
            drillCost,
            Set.of()
        );
        MiningLayout layout = MiningLayout.of(List.of(drill));
        ItemTransportSpec belt = new ItemTransportSpec(
            beltId, 1, 2f, 0, false, CostVector.empty(), fidelity,
            ItemTransportSpec.PortBehavior.CONVEYOR
        );
        CapabilityDescriptor descriptor = new CapabilityDescriptor(
            beltId,
            CapabilityKind.ITEM_TRANSPORT,
            EnumSet.of(CapabilityState.DISCOVERED, CapabilityState.SUPPORTED,
                CapabilityState.AVAILABLE_NOW, CapabilityState.AFFORDABLE_NOW),
            Set.of(),
            CostVector.empty(),
            "test",
            belt
        );
        CapabilitySnapshot capabilities = new CapabilitySnapshot(Map.of(beltId, descriptor));
        OrePatch patch = OrePatch.of(ore, drillTile, Set.of(new TileOffset(0, 0)));
        ExitPort exitPort = ExitPort.forPreference(ExitPort.Side.RIGHT, ExitPort.Bias.CENTER, patch);
        ExitAnchor exit = exitPort.anchors().get(0);
        WorldSnapshot world = WorldSnapshot.of(Map.of(
            drillTile, TileState.empty(),
            transportTile, blockedTransport
                ? new TileState(false, true, false, null, null, null, null)
                : TileState.empty()
        ), TerrainRevision.of(1));
        PatchSearchScope scope = PatchSearchScope.capture(
            world,
            patch,
            capabilities,
            inventory,
            ExistingNetwork.empty(),
            exit,
            PlannerRequest.defaults(drillTile, "blue", exitPort),
            null,
            null,
            fidelity,
            modelComplete
        );
        TransportPlacement placement = new TransportPlacement(
            belt,
            transportTile,
            0,
            new TileKey(2, 0),
            null
        );
        var graph = new PhysicalTransportGraphBuilder().build(layout, exit, List.of(placement)).orElseThrow();
        return new Fixture(scope, layout, graph);
    }

    private record Fixture(PatchSearchScope scope, MiningLayout layout, autodrillnext.model.PlanGraph graph) {}
}
