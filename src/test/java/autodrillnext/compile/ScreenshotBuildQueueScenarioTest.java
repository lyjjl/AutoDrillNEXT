package autodrillnext.compile;

import autodrillnext.LocalMiningPlanner;
import autodrillnext.capability.spec.CapabilityDescriptor;
import autodrillnext.capability.spec.CapabilityKind;
import autodrillnext.capability.spec.CapabilitySnapshot;
import autodrillnext.capability.spec.CapabilityState;
import autodrillnext.capability.spec.CapabilitySpec;
import autodrillnext.capability.spec.CostVector;
import autodrillnext.capability.spec.DrillSpec;
import autodrillnext.capability.spec.ItemTransportSpec;
import autodrillnext.model.ContentId;
import autodrillnext.model.EdgeKind;
import autodrillnext.model.ExistingNetwork;
import autodrillnext.model.Inventory;
import autodrillnext.model.PlannerRequest;
import autodrillnext.model.PlannerResult;
import autodrillnext.model.PlanEdge;
import autodrillnext.simulation.SimulationFidelity;
import autodrillnext.world.ExitAnchor;
import autodrillnext.world.ExitPort;
import autodrillnext.world.TileKey;
import autodrillnext.world.TileState;
import autodrillnext.world.WorldSnapshot;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScreenshotBuildQueueScenarioTest {
    private static final ContentId COAL = ContentId.of("coal");
    private static final ContentId CONVEYOR = ContentId.of("test:conveyor");
    private static final float TARGET_QOUT = 1f;

    @TestFactory
    Stream<DynamicTest> plansEveryExitForEveryCompatibleDrillCombinationFromMapOnly() {
        return Stream.of(floorScenario(), wallScenario()).flatMap(scenario -> drillCombinations(scenario.drills()).stream()
            .flatMap(drills -> Stream.of(ExitPort.Side.values()).map(side -> DynamicTest.dynamicTest(
                scenario.id() + "/" + drillIds(drills) + "/" + side,
                () -> assertCompleteMiningPlan(scenario, drills, side)
            ))));
    }
    @Test
    void throughputUsesEveryFloorOreCellWhenTransportIsNotLimiting() {
        Scenario scenario = sparseFloorScenario();
        List<DrillFixture> drills = List.of(new DrillFixture("floor-1x1", 1, 0));
        autodrillnext.world.OrePatch patch = new autodrillnext.world.OrePatchAnalyzer().analyze(scenario.world(), scenario.seed());
        float target = patch.relativeCells().size();
        PlannerResult result = new LocalMiningPlanner().plan(
            new PlannerRequest(scenario.seed(),
            "sharded",
            mapEdgeExit(ExitPort.Side.TOP),
            PlannerRequest.Profile.THROUGHPUT,
            target,
            1.10f,
            PlannerRequest.BudgetMode.INFINITE_RESOURCES,
            false,
            512,
            512, true),
            scenario.world(),
            Inventory.empty(),
            capabilities(drills),
            ExistingNetwork.empty()
        );

        String context = "diagnostics=" + result.diagnostics()
            + "; staticQout=" + result.graph().qOut()
            + "; candidates=" + result.layout().candidates().size()
            + "; simulation=" + result.itemSimulation();
        assertTrue(result.graph().qOut() >= target, context);
        Set<TileKey> coveredOre = result.layout().candidates().stream()
            .flatMap(candidate -> candidate.coveredOreCells().stream())
            .collect(java.util.stream.Collectors.toSet());
        assertEquals(patch.relativeCells().size(), coveredOre.size(), context);
    }


    private void assertCompleteMiningPlan(Scenario scenario, List<DrillFixture> drills, ExitPort.Side side) {
        assertTrue(scenario.world().tiles().values().stream().allMatch(this::isMapOnly));
        CapabilitySnapshot capabilities = capabilities(drills);
        PlannerRequest request = new PlannerRequest(scenario.seed(),
        "sharded",
        mapEdgeExit(side),
        PlannerRequest.Profile.THROUGHPUT,
        TARGET_QOUT,
        1.10f,
        PlannerRequest.BudgetMode.INFINITE_RESOURCES,
        false,
        512,
        512, true);

        PlannerResult result = new LocalMiningPlanner().plan(
            request,
            scenario.world(),
            Inventory.empty(),
            capabilities,
            ExistingNetwork.empty()
        );

        String context = scenario.id() + "/" + drillIds(drills) + "/" + side + "; diagnostics=" + result.diagnostics();
        assertTrue(result.compileReady(), context);
        assertNotNull(result.layout(), context);
        assertNotNull(result.graph(), context);
        assertTrue(result.graph().qOut() >= TARGET_QOUT, context);
        assertTrue(result.graph().flow().capacitySafe(), context);
        assertEquals(result.exit().anchor().tile(), result.graph().node(result.graph().sinkId()).tile(), context);
        assertFalse(result.layout().candidates().isEmpty(), context);
        assertFalse(result.compileRecords().isEmpty(), context);
        assertTrue(result.layout().candidates().stream().allMatch(candidate -> drills.stream()
            .map(DrillFixture::contentId)
            .anyMatch(candidate.drillId()::equals)), context);
        assertTrue(result.layout().candidates().stream().allMatch(candidate -> result.compileRecords().stream().anyMatch(record ->
            record.blockId().equals(candidate.drillId().value()) && record.tile().equals(candidate.anchor())
        )), context);

        List<PlanEdge> transportEdges = result.graph().edges().stream()
            .filter(edge -> edge.kind() == EdgeKind.GROUND_EDGE)
            .toList();
        assertFalse(transportEdges.isEmpty(), context);
        assertTrue(transportEdges.stream().allMatch(edge -> result.compileRecords().stream().anyMatch(record ->
            record.blockId().equals(edge.transportId())
                && record.tile().equals(edge.footprint().stream().min(TileKey::compareTo).orElseThrow())
        )), context);
    }
    private ExitPort mapEdgeExit(ExitPort.Side side) {
        TileKey anchor = switch (side) {
            case TOP -> new TileKey(7, 0);
            case BOTTOM -> new TileKey(7, 14);
            case LEFT -> new TileKey(0, 7);
            case RIGHT -> new TileKey(14, 7);
        };
        return new ExitPort(
            "map-edge-" + side.name().toLowerCase(java.util.Locale.ROOT),
            side,
            ExitPort.Bias.CENTER,
            List.of(new ExitAnchor(anchor, side, ExitPort.Bias.CENTER, 0))
        );
    }


    private Scenario floorScenario() {
        return new Scenario(
            "floor-ore",
            terrain(false),
            new TileKey(7, 7),
            List.of(
                new DrillFixture("floor-1x1", 1, 0),
                new DrillFixture("floor-2x2", 2, 0),
                new DrillFixture("floor-3x3", 3, 0)
            )
        );
    }
    private Scenario sparseFloorScenario() {
        return new Scenario(
            "sparse-floor-ore",
            sparseFloorTerrain(),
            new TileKey(7, 7),
            List.of(new DrillFixture("floor-1x1", 1, 0))
        );
    }


    private Scenario wallScenario() {
        return new Scenario(
            "wall-ore",
            terrain(true),
            new TileKey(7, 7),
            List.of(
                new DrillFixture("beam-1x1-r1", 1, 1),
                new DrillFixture("beam-2x2-r3", 2, 3)
            )
        );
    }

    private WorldSnapshot terrain(boolean wallOre) {
        LinkedHashMap<TileKey, TileState> tiles = new LinkedHashMap<>();
        for (int x = 0; x <= 14; x++) {
            for (int y = 0; y <= 14; y++) {
                boolean ore = x >= 5 && x <= 9 && y >= 5 && y <= 9;
                tiles.put(new TileKey(x, y), new TileState(
                    false,
                    wallOre && ore,
                    false,
                    null,
                    null,
                    wallOre || !ore ? null : COAL,
                    wallOre && ore ? COAL : null
                ));
            }
        }
        return WorldSnapshot.of(tiles);
    }
    private WorldSnapshot sparseFloorTerrain() {
        LinkedHashMap<TileKey, TileState> tiles = new LinkedHashMap<>();
        for (int x = 0; x <= 14; x++) {
            for (int y = 0; y <= 14; y++) {
                boolean ore = x >= 5 && x <= 9 && x == y;
                tiles.put(new TileKey(x, y), new TileState(false, false, false, null, null, ore ? COAL : null, null));
            }
        }
        return WorldSnapshot.of(tiles);
    }


    private CapabilitySnapshot capabilities(List<DrillFixture> drills) {
        LinkedHashMap<ContentId, CapabilityDescriptor> descriptors = new LinkedHashMap<>();
        for (DrillFixture drill : drills) {
            descriptors.put(drill.contentId(), descriptor(
                drill.contentId(),
                CapabilityKind.DRILL,
                new DrillSpec(drill.contentId(), drill.size(), drill.range(), 60f, 0f, List.of(), List.of(), CostVector.empty())
            ));
        }
        descriptors.put(CONVEYOR, descriptor(
            CONVEYOR,
            CapabilityKind.ITEM_TRANSPORT,
            new ItemTransportSpec(CONVEYOR, 1, 50f, 0, false, CostVector.empty(), SimulationFidelity.BOUNDED_MODEL)
        ));
        return new CapabilitySnapshot(descriptors);
    }

    private CapabilityDescriptor descriptor(ContentId id, CapabilityKind kind, CapabilitySpec spec) {
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

    private List<List<DrillFixture>> drillCombinations(List<DrillFixture> drills) {
        List<List<DrillFixture>> combinations = new ArrayList<>();
        for (int mask = 1; mask < 1 << drills.size(); mask++) {
            ArrayList<DrillFixture> combination = new ArrayList<>();
            for (int index = 0; index < drills.size(); index++) {
                if ((mask & 1 << index) != 0) combination.add(drills.get(index));
            }
            combinations.add(List.copyOf(combination));
        }
        return combinations;
    }

    private boolean isMapOnly(TileState tile) {
        return tile.existingBlock() == null && tile.existingTeam() == null;
    }

    private String drillIds(List<DrillFixture> drills) {
        return drills.stream().map(drill -> drill.contentId().value()).collect(java.util.stream.Collectors.joining("+"));
    }

    private record Scenario(String id, WorldSnapshot world, TileKey seed, List<DrillFixture> drills) {
    }

    private record DrillFixture(String name, int size, int range) {
        private ContentId contentId() {
            return ContentId.of("test:" + name);
        }
    }
}
