package autodrillnext;

import autodrillnext.capability.spec.CapabilityDescriptor;
import autodrillnext.capability.spec.CapabilityKind;
import autodrillnext.capability.spec.CapabilitySnapshot;
import autodrillnext.capability.spec.CapabilityState;
import autodrillnext.capability.spec.CostVector;
import autodrillnext.capability.spec.DrillSpec;
import autodrillnext.capability.spec.ItemTransportSpec;
import autodrillnext.simulation.SimulationFidelity;
import autodrillnext.model.ContentId;
import autodrillnext.model.ExistingNetwork;
import autodrillnext.model.Inventory;
import autodrillnext.model.ItemId;
import autodrillnext.model.PlannerRequest;
import autodrillnext.model.PlannerResult;
import autodrillnext.model.PlanningProgress;
import autodrillnext.model.SearchVerdict;
import autodrillnext.world.ExitAnchor;
import autodrillnext.world.ExitPort;
import autodrillnext.world.TileKey;
import autodrillnext.world.TileState;
import autodrillnext.world.WorldSnapshot;
import autodrillnext.world.TerrainRevision;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalMiningPlannerIntegrationTest {
    @Test
    void tinyProductionPlanReturnsProvenPatchOptimalCertificate() {
        TinyScenario scenario = tinyScenario();

        PlannerResult result = scenario.planner().plan(
            scenario.request(), scenario.snapshot(), null, null);

        assertTrue(result.compileReady(), result.diagnostics().toString());
        assertEquals(SearchVerdict.PATCH_OPTIMAL, result.certificate().verdict());
        assertEquals(result.certificate().lowerBound(), result.certificate().upperBound());
    }

    @Test
    void unrelatedTerrainRevisionDoesNotBlockSubmission() {
        TinyScenario scenario = tinyScenario();
        PlannerResult planned = scenario.planner().plan(
            scenario.request(), scenario.snapshot(), null, null);
        WorldSnapshot changedWorld = WorldSnapshot.of(
            scenario.snapshot().world().tiles(),
            scenario.snapshot().world().revision().next()
        );
        LocalMiningPlanner.PlanningSnapshot changed = new LocalMiningPlanner.PlanningSnapshot(
            changedWorld,
            scenario.snapshot().inventory(),
            scenario.snapshot().capabilities(),
            scenario.snapshot().existingNetwork()
        );

        assertDoesNotThrow(() -> scenario.planner().validateSubmission(
            scenario.request(), changed, planned, null));
    }

    @Test
    void inventoryChangeDoesNotBlockSubmission() {
        TinyScenario scenario = tinyScenario();
        PlannerResult planned = scenario.planner().plan(
            scenario.request(), scenario.snapshot(), null, null);
        LocalMiningPlanner.PlanningSnapshot changed = new LocalMiningPlanner.PlanningSnapshot(
            scenario.snapshot().world(),
            Inventory.of(Map.of(ItemId.of("copper"), 1)),
            scenario.snapshot().capabilities(),
            scenario.snapshot().existingNetwork()
        );

        assertDoesNotThrow(() -> scenario.planner().validateSubmission(
            scenario.request(), changed, planned, null));
    }

    @Test
    void staleLiquidSelectionIsRejected() {
        TinyScenario scenario = tinyScenario();
        PlannerResult planned = scenario.planner().plan(
            scenario.request(), scenario.snapshot(), null, null);

        assertThrows(IllegalStateException.class, () -> scenario.planner().validateSubmission(
            scenario.request(), scenario.snapshot(), planned, "water"));
    }
    @Test
    void completeSyntheticPlanIsReachableAndCompileReady() {
        ContentId ore = ContentId.of("mindustry:copper");
        ContentId drill = ContentId.of("test:drill");
        ContentId conveyor = ContentId.of("test:conveyor");
        CapabilitySnapshot capabilities = new CapabilitySnapshot(Map.of(
            drill, descriptor(drill, CapabilityKind.DRILL, new DrillSpec(
                drill, 1, 0, 20f, 0f, java.util.List.of(), java.util.List.of(), CostVector.empty()
            )),
            conveyor, descriptor(conveyor, CapabilityKind.ITEM_TRANSPORT, new ItemTransportSpec(
                conveyor, 1, 10f, 0, false, CostVector.empty(), SimulationFidelity.BOUNDED_MODEL
            ))
        ));

        LinkedHashMap<TileKey, TileState> tiles = new LinkedHashMap<>();
        for (int x = 0; x <= 4; x++) {
            tiles.put(new TileKey(x, 0), new TileState(
                false, false, false, null, null, x == 0 ? ore : null, null
            ));
            tiles.put(new TileKey(x, 1), new TileState(false, false, false, null, null, null, null));
        }
        WorldSnapshot world = WorldSnapshot.of(tiles);
        ExitPort exit = new ExitPort(
            "right-center",
            ExitPort.Side.RIGHT,
            ExitPort.Bias.CENTER,
            java.util.List.of(new ExitAnchor(new TileKey(4, 1), ExitPort.Side.RIGHT, ExitPort.Bias.CENTER, 0))
        );
        PlannerRequest request = new PlannerRequest(new TileKey(0, 0),
        "sharded",
        exit,
        PlannerRequest.Profile.THROUGHPUT,
        3f,
        1.10f,
        PlannerRequest.BudgetMode.INFINITE_RESOURCES,
        false,
        100,
        100, true);

        var progress = new java.util.ArrayList<autodrillnext.model.PlanningProgress>();
        PlannerResult result = new LocalMiningPlanner().plan(
            request, new LocalMiningPlanner.PlanningSnapshot(world, Inventory.empty(), capabilities, ExistingNetwork.empty()),
            null, null, progress::add);

        assertFalse(progress.isEmpty());
        assertTrue(progress.stream().anyMatch(frame ->
            frame.stage() == PlanningProgress.Stage.HEURISTIC_WARM_START
                && frame.routing() != null
                && frame.routing().phase() == autodrillnext.model.RoutingProgress.Phase.SEARCHING
                && frame.routing().expandedStates() > 0),
            "warm start must expose actual route expansion");
        var finished = progress.get(progress.size() - 1);
        assertEquals(PlanningProgress.Stage.COMPLETE, finished.stage());
        assertNotNull(finished.bestRoute());
        assertTrue(finished.bestRoute().qOut() >= 3f,
            "the live best must represent a serviced plan");
        assertTrue(finished.counters().exploredStates() > 0);
        assertEquals(result.certificate().pendingStates(), finished.counters().pendingStates());

        assertTrue(result.compileReady(), () -> "diagnostics=" + result.diagnostics());
        assertNotNull(result.graph());
        assertTrue(result.graph().qOut() >= 3f);
        assertTrue(result.graph().flow().capacitySafe());
        assertTrue(result.graph().edges().stream().noneMatch(edge -> edge.assignedFlow() > edge.usableCapacity() + 0.0001f));
        assertNotNull(result.itemSimulation());
        assertEquals(SimulationFidelity.BOUNDED_MODEL, result.itemSimulation().fidelity());
        assertTrue(result.itemSimulation().steadyStateDetected());
        assertFalse(result.compileRecords().isEmpty());
        assertTrue(result.compileRecords().stream().allMatch(record -> record.dependencies() != null));
        assertTrue(result.compileRecords().stream().noneMatch(record -> record.config() == null && record.blockId().isBlank()));
    }

    @Test
    void drillSelectionReturnsPickerDataWithoutRunningThePlan() {
        ContentId ore = ContentId.of("mindustry:copper");
        ContentId drill = ContentId.of("test:drill");
        CapabilitySnapshot capabilities = new CapabilitySnapshot(Map.of(
            drill, descriptor(drill, CapabilityKind.DRILL, new DrillSpec(
                drill, 1, 0, 20f, 0f, java.util.List.of(), java.util.List.of(), CostVector.empty()
            ))
        ));
        WorldSnapshot world = WorldSnapshot.of(Map.of(
            new TileKey(0, 0), new TileState(false, false, false, null, null, ore, null)
        ));
        ExitPort exit = ExitPort.forPreference(
            ExitPort.Side.RIGHT,
            ExitPort.Bias.CENTER,
            autodrillnext.world.OrePatch.of(ore, new TileKey(0, 0), Set.of(new autodrillnext.world.TileOffset(0, 0)))
        );

        PlannerResult result = new LocalMiningPlanner().drillSelection(
            PlannerRequest.defaults(new TileKey(0, 0), "sharded", exit),
            new LocalMiningPlanner.PlanningSnapshot(world, Inventory.empty(), capabilities, ExistingNetwork.empty())
        );

        assertNotNull(result.patch());
        assertEquals(capabilities, result.capabilities());
        assertNull(result.graph());
    }

    @Test
    void largePatchCompletesTheActualBuildPipeline() {
        ContentId ore = ContentId.of("mindustry:coal");
        ContentId drill = ContentId.of("test:pneumatic");
        LinkedHashMap<ContentId, CapabilityDescriptor> available = new LinkedHashMap<>();
        available.put(drill, descriptor(drill, CapabilityKind.DRILL, new DrillSpec(
            drill, 2, 0, 60f, 0f, java.util.List.of(), java.util.List.of(), CostVector.empty())));
        for (int i = 0; i < 4; i++) {
            ContentId belt = ContentId.of("test:belt-" + i);
            available.put(belt, descriptor(belt, CapabilityKind.ITEM_TRANSPORT,
                new ItemTransportSpec(belt, 1, 10f + i * 5f, 0, false, CostVector.empty())));
        }
        ContentId bridge = ContentId.of("test:bridge");
        available.put(bridge, descriptor(bridge, CapabilityKind.ITEM_TRANSPORT,
            new ItemTransportSpec(bridge, 1, 20f, 4, true, CostVector.empty())));
        LinkedHashMap<TileKey, TileState> tiles = new LinkedHashMap<>();
        for (int x = 0; x < 64; x++) {
            for (int y = 0; y < 64; y++) {
                tiles.put(new TileKey(x, y), new TileState(false, false, false, null, null,
                    x >= 25 && x < 37 && y >= 25 && y < 37 ? ore : null, null));
            }
        }
        WorldSnapshot world = WorldSnapshot.of(tiles);
        autodrillnext.world.OrePatch patch = new autodrillnext.world.OrePatchAnalyzer().analyze(world, new TileKey(25, 25));
        PlannerRequest request = new PlannerRequest(new TileKey(25, 25), "sharded",
            ExitPort.forPreference(ExitPort.Side.RIGHT, ExitPort.Bias.CENTER, patch),
            PlannerRequest.Profile.BALANCED, 0f, 1.10f,
            PlannerRequest.BudgetMode.INFINITE_RESOURCES, false, 4096, 1000, false);
        java.util.ArrayList<PlanningProgress> progress = new java.util.ArrayList<>();
        long start = System.nanoTime();
        PlannerResult result = new LocalMiningPlanner().plan(
            request,
            new LocalMiningPlanner.PlanningSnapshot(
                world, Inventory.empty(), new CapabilitySnapshot(available), ExistingNetwork.empty()),
            drill.value(),
            "",
            progress::add
        );
        double seconds = (System.nanoTime() - start) / 1_000_000_000.0;
        System.out.println("large-patch seconds=" + seconds + " records=" + result.compileRecords().size());
        assertTrue(result.compileReady(), () -> result.diagnostics().toString());
        assertFalse(result.compileRecords().isEmpty());
        assertTrue(result.layout().candidates().size() > 1,
            () -> "default search collapsed to one drill; progress=" + progress.stream()
                .map(frame -> frame.stage() + ":" + frame.attempted().candidates().size())
                .toList());
        assertTrue(result.layout().productionPerSecond() > 4f,
            () -> "default search did not beat the best single drill: " + result.layout().productionPerSecond());
        assertFalse(result.certificate().optimal(), "time-limited search must not claim optimality");
        assertTrue(result.diagnostics().stream().anyMatch(diagnostic ->
            diagnostic.code() == autodrillnext.model.DiagnosticCode.SEARCH_LIMIT_REACHED));
        assertTrue(seconds < 10, "large patch took " + seconds + " seconds");
    }

    private TinyScenario tinyScenario() {
        ContentId ore = ContentId.of("mindustry:copper");
        ContentId drill = ContentId.of("test:tiny-drill");
        ContentId belt = ContentId.of("test:tiny-belt");
        CapabilitySnapshot capabilities = new CapabilitySnapshot(Map.of(
            drill, descriptor(drill, CapabilityKind.DRILL, new DrillSpec(
                drill, 1, 0, 60f, 0f, java.util.List.of(), java.util.List.of(),
                CostVector.empty()
            )),
            belt, descriptor(belt, CapabilityKind.ITEM_TRANSPORT, new ItemTransportSpec(
                belt, 1, 2f, 0, false, CostVector.empty(), SimulationFidelity.BOUNDED_MODEL
            ))
        ));
        TileKey seed = new TileKey(0, 0);
        WorldSnapshot world = WorldSnapshot.of(Map.of(
            seed, new TileState(false, false, false, null, null, ore, null),
            new TileKey(1, 0), TileState.empty()
        ), TerrainRevision.of(1));
        autodrillnext.world.OrePatch patch =
            new autodrillnext.world.OrePatchAnalyzer().analyze(world, seed);
        ExitPort exit = ExitPort.forPreference(
            ExitPort.Side.RIGHT, ExitPort.Bias.CENTER, patch);
        PlannerRequest request = new PlannerRequest(
            seed,
            "sharded",
            exit,
            PlannerRequest.Profile.THROUGHPUT,
            0f,
            1.1f,
            PlannerRequest.BudgetMode.INFINITE_RESOURCES,
            false,
            16,
            1000,
            false
        );
        LocalMiningPlanner planner = new LocalMiningPlanner();
        LocalMiningPlanner.PlanningSnapshot snapshot = new LocalMiningPlanner.PlanningSnapshot(
            world, Inventory.empty(), capabilities, ExistingNetwork.empty());
        return new TinyScenario(planner, request, snapshot);
    }

    private record TinyScenario(
        LocalMiningPlanner planner,
        PlannerRequest request,
        LocalMiningPlanner.PlanningSnapshot snapshot
    ) {}

    private CapabilityDescriptor descriptor(ContentId id, CapabilityKind kind, autodrillnext.capability.spec.CapabilitySpec spec) {
        return new CapabilityDescriptor(
            id,
            kind,
            EnumSet.of(CapabilityState.DISCOVERED, CapabilityState.SUPPORTED, CapabilityState.AVAILABLE_NOW),
            Set.of(),
            CostVector.empty(),
            "test",
            spec
        );
    }
}
