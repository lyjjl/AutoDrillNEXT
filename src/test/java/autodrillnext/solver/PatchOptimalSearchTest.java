package autodrillnext.solver;

import autodrillnext.capability.spec.CapabilityDescriptor;
import autodrillnext.capability.spec.CapabilityKind;
import autodrillnext.capability.spec.CapabilitySnapshot;
import autodrillnext.capability.spec.CapabilityState;
import autodrillnext.capability.spec.CostVector;
import autodrillnext.capability.spec.ItemTransportSpec;
import autodrillnext.model.ContentId;
import autodrillnext.model.DrillCandidate;
import autodrillnext.model.ExistingNetwork;
import autodrillnext.model.Inventory;
import autodrillnext.model.ItemId;
import autodrillnext.model.PatchSearchScope;
import autodrillnext.model.PlanObjectiveOrder;
import autodrillnext.model.PlannerRequest;
import autodrillnext.model.PlanningProgress;
import autodrillnext.model.ProductionEstimate;
import autodrillnext.model.SearchStopReason;
import autodrillnext.model.SearchVerdict;
import autodrillnext.simulation.SimulationFidelity;
import autodrillnext.world.ExitAnchor;
import autodrillnext.world.ExitPort;
import autodrillnext.world.OrePatch;
import autodrillnext.world.PlacementFootprint;
import autodrillnext.world.PlacementRules;
import autodrillnext.world.TerrainRevision;
import autodrillnext.world.TileKey;
import autodrillnext.world.TileOffset;
import autodrillnext.world.TileState;
import autodrillnext.world.WorldSnapshot;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.function.LongSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PatchOptimalSearchTest {
    private final PatchSearchOracle oracle = new PatchSearchOracle();
    private final PlanCandidateEvaluator evaluator = new PlanCandidateEvaluator();

    @Test
    void exhaustedTinyDomainMatchesIndependentOracle() {
        Fixture fixture = tiny(PlannerRequest.Profile.THROUGHPUT, 1000,
            SimulationFidelity.BOUNDED_MODEL, true, true);
        ArrayList<PlanningProgress> progress = new ArrayList<>();

        OptimalPlanningResult actual = search(() -> 0L).search(
            fixture.scope(), fixture.candidates(), progress::add);
        EvaluatedMiningPlan expected = oracle.solve(
            fixture.scope(), fixture.candidates(), fixture.domain(), evaluator).orElseThrow();

        assertEquals(SearchVerdict.PATCH_OPTIMAL, actual.certificate().verdict());
        assertEquals(expected.objective(), actual.objective());
        assertEquals(expected.graph().placements(), actual.graph().placements());
        assertEquals(0, actual.certificate().pendingStates());
        assertTrue(progress.stream().anyMatch(value -> value.stage() == PlanningProgress.Stage.CERTIFYING));
        PlanningProgress completed = progress.get(progress.size() - 1);
        assertEquals(PlanningProgress.Stage.COMPLETE, completed.stage());
        assertEquals(actual.layout(), completed.attempted());
        assertEquals(actual.layout(), completed.bestLayout());
    }
    @Test
    void heuristicStrategyReturnsCurrentBestWithoutProofClaim() {
        Fixture fixture = tiny(PlannerRequest.Profile.THROUGHPUT, 1000,
            SimulationFidelity.BOUNDED_MODEL, true, true);
        PatchSearchScope scope = withStrategy(
            fixture.scope(), PlannerRequest.SearchStrategy.HEURISTIC);

        OptimalPlanningResult result = search(() -> 0L).search(
            scope, fixture.candidates(), ignored -> {});

        assertTrue(result.hasPlan());
        assertEquals(SearchVerdict.CURRENT_BEST, result.certificate().verdict());
        assertEquals(SearchStopReason.HEURISTIC_MODE, result.certificate().stopReason());
        assertFalse(result.certificate().optimal());
    }


    @Test
    void stateLimitWithoutIncumbentIsSearchIncomplete() {
        Fixture fixture = tiny(PlannerRequest.Profile.THROUGHPUT, 1,
            SimulationFidelity.BOUNDED_MODEL, true, true);
        LongSupplier warmAlreadyExpired = new LongSupplier() {
            private boolean first = true;

            @Override
            public long getAsLong() {
                if (first) {
                    first = false;
                    return 0L;
                }
                return 1_600_000_000L;
            }
        };

        OptimalPlanningResult result = search(warmAlreadyExpired).search(
            fixture.scope(), fixture.candidates(), ignored -> {});

        assertEquals(SearchVerdict.SEARCH_INCOMPLETE, result.certificate().verdict());
        assertFalse(result.hasPlan());
        assertTrue(result.certificate().pendingStates() > 0);
    }

    @Test
    void stateLimitWithWarmIncumbentIsCurrentBest() {
        Fixture fixture = tiny(PlannerRequest.Profile.THROUGHPUT, 1,
            SimulationFidelity.BOUNDED_MODEL, true, true);

        OptimalPlanningResult result = search(() -> 0L).search(
            fixture.scope(), fixture.candidates(), ignored -> {});

        assertEquals(SearchVerdict.CURRENT_BEST, result.certificate().verdict());
        assertTrue(result.hasPlan());
        assertTrue(PlanObjectiveOrder.forProfile(fixture.scope().request().profile()).compare(
            result.certificate().upperBound(), result.certificate().lowerBound()) >= 0);
    }

    @Test
    void allProfilesMatchOracle() {
        for (PlannerRequest.Profile profile : PlannerRequest.Profile.values()) {
            Fixture fixture = tiny(profile, 1000, SimulationFidelity.BOUNDED_MODEL, true, true);
            OptimalPlanningResult actual = search(() -> 0L).search(
                fixture.scope(), fixture.candidates(), ignored -> {});
            EvaluatedMiningPlan expected = oracle.solve(
                fixture.scope(), fixture.candidates(), fixture.domain(), evaluator).orElseThrow();

            assertEquals(expected.objective(), actual.objective(), profile.name());
            assertEquals(expected.graph().placements(), actual.graph().placements(), profile.name());
        }
    }

    @Test
    void exhaustedEmptyDomainIsProvenInfeasible() {
        Fixture fixture = tiny(PlannerRequest.Profile.THROUGHPUT, 1000,
            SimulationFidelity.BOUNDED_MODEL, true, false);

        OptimalPlanningResult result = search(() -> 0L).search(
            fixture.scope(), fixture.candidates(), ignored -> {});

        assertEquals(SearchVerdict.PROVEN_INFEASIBLE, result.certificate().verdict());
        assertFalse(result.hasPlan());
        assertEquals(0, result.certificate().pendingStates());
    }

    @Test
    void unsupportedModelCannotClaimProof() {
        Fixture fixture = tiny(PlannerRequest.Profile.THROUGHPUT, 1000,
            SimulationFidelity.UNSUPPORTED, true, true);

        OptimalPlanningResult result = search(() -> 0L).search(
            fixture.scope(), fixture.candidates(), ignored -> {});

        assertEquals(SearchVerdict.CURRENT_BEST, result.certificate().verdict());
        assertTrue(result.hasPlan());
        assertFalse(result.certificate().optimal());
    }

    @Test
    void repeatedRunsKeepPlanBoundsAndCounts() {
        Fixture fixture = tiny(PlannerRequest.Profile.BALANCED, 1000,
            SimulationFidelity.BOUNDED_MODEL, true, true);
        OptimalPlanningResult first = search(() -> 0L).search(
            fixture.scope(), fixture.candidates(), ignored -> {});
        OptimalPlanningResult second = search(() -> 0L).search(
            fixture.scope(), fixture.candidates(), ignored -> {});

        assertEquals(first.objective(), second.objective());
        assertEquals(first.graph().placements(), second.graph().placements());
        assertEquals(first.certificate().lowerBound(), second.certificate().lowerBound());
        assertEquals(first.certificate().upperBound(), second.certificate().upperBound());
        assertEquals(first.certificate().exploredStates(), second.certificate().exploredStates());
        assertEquals(first.certificate().prunedStates(), second.certificate().prunedStates());
    }

    @Test
    void interruptionStopsBeforeAnotherExpansion() {
        Fixture fixture = tiny(PlannerRequest.Profile.THROUGHPUT, 1000,
            SimulationFidelity.BOUNDED_MODEL, true, true);
        Thread.currentThread().interrupt();
        try {
            assertThrows(CancellationException.class, () -> search(() -> 0L).search(
                fixture.scope(), fixture.candidates(), ignored -> {}));
        } finally {
            Thread.interrupted();
        }
    }
    @Test
    void nonShortestCheapNetworkMatchesOracle() {
        Fixture fixture = nonShortestCheapFixture();
        assertMatchesOracle(fixture);
    }

    @Test
    void sharedTrunkCapacityMatchesOracle() {
        Fixture fixture = sharedTrunkFixture();
        assertMatchesOracle(fixture);
        assertEquals(2, fixture.candidates().size());
    }

    @Test
    void bridgeOrientationMatchesOracle() {
        Fixture fixture = bridgeFixture();
        assertMatchesOracle(fixture);
        assertTrue(fixture.domain().configuration(0).canonicalId().contains("=>"));
    }

    private void assertMatchesOracle(Fixture fixture) {
        OptimalPlanningResult actual = search(() -> 0L).search(
            fixture.scope(), fixture.candidates(), ignored -> {});
        EvaluatedMiningPlan expected = oracle.solve(
            fixture.scope(), fixture.candidates(), fixture.domain(), evaluator).orElseThrow();
        assertEquals(SearchVerdict.PATCH_OPTIMAL, actual.certificate().verdict());
        assertEquals(expected.objective(), actual.objective());
        assertEquals(expected.graph().placements(), actual.graph().placements());
    }

    private Fixture nonShortestCheapFixture() {
        ContentId drillId = ContentId.of("drill");
        ContentId expensiveId = ContentId.of("expensive");
        ContentId cheapId = ContentId.of("cheap");
        TileKey patchOrigin = new TileKey(1, 0);
        DrillCandidate drill = drill("source", drillId, new TileKey(0, 0), patchOrigin);
        CostVector expensiveCost = CostVector.of(Map.of(ItemId.of("copper"), 10));
        CostVector cheapCost = CostVector.of(Map.of(ItemId.of("copper"), 1));
        ItemTransportSpec expensive = ground(expensiveId, expensiveCost);
        ItemTransportSpec cheap = ground(cheapId, cheapCost);
        Map<TileKey, TileState> tiles = Map.of(
            new TileKey(0, 0), TileState.empty(),
            new TileKey(1, 0), TileState.empty(),
            new TileKey(2, 0), TileState.empty(),
            new TileKey(0, 1), TileState.empty(),
            new TileKey(1, 1), TileState.empty(),
            new TileKey(2, 1), TileState.empty()
        );
        Map<ContentId, Map<TileKey, Integer>> allowed = Map.of(
            drillId, Map.of(new TileKey(0, 0), 1),
            expensiveId, Map.of(new TileKey(1, 0), 1, new TileKey(2, 0), 1),
            cheapId, Map.of(
                new TileKey(0, 1), 1,
                new TileKey(1, 1), 1,
                new TileKey(2, 1), 1 << 3,
                new TileKey(2, 0), 1
            )
        );
        return fixture(
            patchOrigin, List.of(drill), List.of(expensive, cheap), tiles, allowed,
            PlannerRequest.Profile.LOW_COST);
    }

    private Fixture sharedTrunkFixture() {
        ContentId drillId = ContentId.of("drill");
        ContentId beltId = ContentId.of("belt");
        TileKey patchOrigin = new TileKey(1, 1);
        List<DrillCandidate> drills = List.of(
            drill("upper", drillId, new TileKey(0, 0), patchOrigin),
            drill("lower", drillId, new TileKey(0, 2), patchOrigin)
        );
        ItemTransportSpec belt = ground(beltId, CostVector.empty());
        Map<TileKey, TileState> tiles = Map.of(
            new TileKey(0, 0), TileState.empty(),
            new TileKey(0, 2), TileState.empty(),
            new TileKey(1, 0), TileState.empty(),
            new TileKey(1, 1), TileState.empty(),
            new TileKey(1, 2), TileState.empty(),
            new TileKey(2, 1), TileState.empty()
        );
        Map<ContentId, Map<TileKey, Integer>> allowed = Map.of(
            drillId, Map.of(new TileKey(0, 0), 1, new TileKey(0, 2), 1),
            beltId, Map.of(
                new TileKey(1, 0), 1 << 1,
                new TileKey(1, 2), 1 << 3,
                new TileKey(1, 1), 1,
                new TileKey(2, 1), 1
            )
        );
        return fixture(
            patchOrigin, drills, List.of(belt), tiles, allowed,
            PlannerRequest.Profile.THROUGHPUT);
    }

    private Fixture bridgeFixture() {
        ContentId drillId = ContentId.of("drill");
        ContentId bridgeId = ContentId.of("bridge");
        TileKey patchOrigin = new TileKey(0, 0);
        DrillCandidate drill = drill("source", drillId, new TileKey(-1, 0), patchOrigin);
        ItemTransportSpec bridge = new ItemTransportSpec(
            bridgeId, 1, 2f, 1, true, CostVector.empty(), SimulationFidelity.BOUNDED_MODEL,
            ItemTransportSpec.PortBehavior.ITEM_BRIDGE
        );
        Map<TileKey, TileState> tiles = Map.of(
            new TileKey(-1, 0), TileState.empty(),
            new TileKey(0, 0), TileState.empty(),
            new TileKey(1, 0), TileState.empty()
        );
        Map<ContentId, Map<TileKey, Integer>> allowed = Map.of(
            drillId, Map.of(new TileKey(-1, 0), 1),
            bridgeId, Map.of(new TileKey(0, 0), 1, new TileKey(1, 0), 1)
        );
        return fixture(
            patchOrigin, List.of(drill), List.of(bridge), tiles, allowed,
            PlannerRequest.Profile.THROUGHPUT);
    }

    private Fixture fixture(
        TileKey patchOrigin,
        List<DrillCandidate> candidates,
        List<ItemTransportSpec> transports,
        Map<TileKey, TileState> tiles,
        Map<ContentId, Map<TileKey, Integer>> allowed,
        PlannerRequest.Profile profile
    ) {
        OrePatch patch = OrePatch.of(
            ContentId.of("copper"), patchOrigin, Set.of(new TileOffset(0, 0)));
        ExitPort exitPort = ExitPort.forPreference(
            ExitPort.Side.RIGHT, ExitPort.Bias.CENTER, patch);
        CapabilitySnapshot capabilities = new CapabilitySnapshot(transports.stream().collect(
            java.util.stream.Collectors.toMap(
                ItemTransportSpec::id,
                this::descriptor,
                (first, second) -> first,
                java.util.LinkedHashMap::new
            )
        ));
        WorldSnapshot world = WorldSnapshot.captured(
            tiles,
            TerrainRevision.of(1),
            new PlacementRules(allowed, Map.of())
        );
        PlannerRequest request = new PlannerRequest(
            patchOrigin,
            "blue",
            exitPort,
            profile,
            0f,
            1.1f,
            PlannerRequest.BudgetMode.INFINITE_RESOURCES,
            false,
            64,
            1000,
            true
        );
        PatchSearchScope scope = PatchSearchScope.capture(
            world,
            patch,
            capabilities,
            Inventory.empty(),
            ExistingNetwork.empty(),
            exitPort.anchors().get(0),
            request,
            null,
            null,
            SimulationFidelity.BOUNDED_MODEL,
            true
        );
        return new Fixture(scope, candidates, TransportPlacementDomain.capture(scope));
    }

    private ItemTransportSpec ground(ContentId id, CostVector cost) {
        return new ItemTransportSpec(
            id, 1, 2f, 0, false, cost, SimulationFidelity.BOUNDED_MODEL,
            ItemTransportSpec.PortBehavior.CONVEYOR
        );
    }

    private DrillCandidate drill(String id, ContentId drillId, TileKey anchor, TileKey covered) {
        return new DrillCandidate(
            id,
            drillId,
            anchor,
            0,
            PlacementFootprint.of(Set.of(anchor)),
            Set.of(covered),
            new ProductionEstimate(ContentId.of("copper"), 0.5f),
            List.of(),
            CostVector.empty(),
            Set.of()
        );
    }

    private PatchSearchScope withStrategy(
        PatchSearchScope scope,
        PlannerRequest.SearchStrategy strategy
    ) {
        PlannerRequest request = scope.request();
        PlannerRequest changed = new PlannerRequest(
            request.seed(),
            request.teamId(),
            request.exit(),
            request.profile(),
            strategy,
            request.targetQout(),
            request.transportHeadroom(),
            request.budgetMode(),
            request.allowDestructiveRelayout(),
            request.maxTiles(),
            request.maxIterations(),
            request.allowMixedTransport()
        );
        return PatchSearchScope.capture(
            scope.world(),
            scope.patch(),
            scope.capabilities(),
            scope.inventory(),
            scope.existingNetwork(),
            scope.exit(),
            changed,
            scope.selectedDrillId(),
            scope.selectedLiquidId(),
            scope.simulationFidelity(),
            scope.modelComplete()
        );
    }

    private PatchOptimalSearch search(LongSupplier nanoTime) {
        return new PatchOptimalSearch(
            new MiningLayoutSolver(),
            new ItemRouteSolver(),
            new PlanCandidateEvaluator(),
            new ParetoPlanner(),
            nanoTime
        );
    }

    private Fixture tiny(
        PlannerRequest.Profile profile,
        int maxIterations,
        SimulationFidelity fidelity,
        boolean modelComplete,
        boolean withTransport
    ) {
        TileKey drillTile = new TileKey(0, 0);
        ContentId ore = ContentId.of("copper");
        ContentId drillId = ContentId.of("drill");
        ContentId beltId = ContentId.of("belt");
        OrePatch patch = OrePatch.of(ore, drillTile, Set.of(new TileOffset(0, 0)));
        ExitPort exitPort = ExitPort.forPreference(ExitPort.Side.RIGHT, ExitPort.Bias.CENTER, patch);
        ExitAnchor exit = exitPort.anchors().get(0);
        DrillCandidate drill = new DrillCandidate(
            "drill@0,0",
            drillId,
            drillTile,
            0,
            PlacementFootprint.of(Set.of(drillTile)),
            Set.of(drillTile),
            new ProductionEstimate(ore, 0.5f),
            List.of(),
            CostVector.empty(),
            Set.of()
        );
        ItemTransportSpec belt = new ItemTransportSpec(
            beltId, 1, 2f, 0, false, CostVector.empty(), fidelity,
            ItemTransportSpec.PortBehavior.CONVEYOR
        );
        CapabilitySnapshot capabilities = withTransport
            ? new CapabilitySnapshot(Map.of(beltId, descriptor(belt)))
            : new CapabilitySnapshot(Map.of());
        Map<ContentId, Map<TileKey, Integer>> allowed = withTransport
            ? Map.of(drillId, Map.of(drillTile, 1), beltId, Map.of(exit.tile(), 1))
            : Map.of(drillId, Map.of(drillTile, 1));
        PlacementRules rules = new PlacementRules(allowed, Map.of());
        WorldSnapshot world = WorldSnapshot.captured(Map.of(
            drillTile, TileState.empty(),
            exit.tile(), TileState.empty()
        ), TerrainRevision.of(1), rules);
        PlannerRequest request = new PlannerRequest(
            drillTile,
            "blue",
            exitPort,
            profile,
            0f,
            1.1f,
            PlannerRequest.BudgetMode.CURRENT_INVENTORY,
            false,
            32,
            maxIterations,
            true
        );
        PatchSearchScope scope = PatchSearchScope.capture(
            world,
            patch,
            capabilities,
            Inventory.empty(),
            ExistingNetwork.empty(),
            exit,
            request,
            null,
            null,
            fidelity,
            modelComplete
        );
        return new Fixture(scope, List.of(drill), TransportPlacementDomain.capture(scope));
    }

    private CapabilityDescriptor descriptor(ItemTransportSpec spec) {
        return new CapabilityDescriptor(
            spec.id(),
            CapabilityKind.ITEM_TRANSPORT,
            EnumSet.of(CapabilityState.DISCOVERED, CapabilityState.SUPPORTED,
                CapabilityState.AVAILABLE_NOW, CapabilityState.AFFORDABLE_NOW),
            Set.of(),
            spec.cost(),
            "test",
            spec
        );
    }

    private record Fixture(
        PatchSearchScope scope,
        List<DrillCandidate> candidates,
        TransportPlacementDomain domain
    ) {}
}
