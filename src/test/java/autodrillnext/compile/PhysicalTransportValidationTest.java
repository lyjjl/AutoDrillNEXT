package autodrillnext.compile;

import autodrillnext.capability.spec.*;
import autodrillnext.model.*;
import autodrillnext.simulation.SimulationFidelity;
import autodrillnext.world.*;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class PhysicalTransportValidationTest {
    @Test
    void sharedBuilderFeedsEveryAdmittedDrillPerimeterReceiver() {
        ItemTransportSpec conveyor = spec("conveyor", ItemTransportSpec.PortBehavior.CONVEYOR, 0);
        ExitAnchor exit = new ExitAnchor(
            new TileKey(1, 1),
            ExitPort.Side.RIGHT,
            ExitPort.Bias.CENTER,
            0
        );
        PlanGraph graph = new PhysicalTransportGraphBuilder().build(layout(), exit, List.of(
            placement(conveyor, 1, 0, 1, 1, 1),
            placement(conveyor, 0, 1, 0, 1, 1),
            placement(conveyor, 1, 1, 0, 2, 1)
        )).orElseThrow();

        long feeds = graph.edges().stream()
            .filter(edge -> edge.kind() == EdgeKind.SOURCE_EDGE)
            .count();
        assertEquals(2, feeds);
    }

    @Test
    void sharedBuilderRejectsAnUnpairedDirectionalBridgeRecord() {
        ItemTransportSpec bridge = spec("bridge", ItemTransportSpec.PortBehavior.ITEM_BRIDGE, 3);
        List<BuildPlanCompiler.CompileRecord> records = List.of(
            record("mod:drill", 0, 0, 0, null),
            record(bridge.id().value(), 1, 0, 0, new TileOffset(3, 0))
        );

        assertTrue(new PhysicalTransportGraphBuilder()
            .fromCompileRecords(records, layout(), new ExitAnchor(
                new TileKey(4, 0),
                ExitPort.Side.RIGHT,
                ExitPort.Bias.CENTER,
                0
            ), capabilities(bridge))
            .isEmpty());
    }

    @Test
    void armoredDuctSideMergeAcceptsDuctButRejectsConveyor() {
        ItemTransportSpec conveyor = spec("conveyor", ItemTransportSpec.PortBehavior.CONVEYOR, 0);
        ItemTransportSpec duct = spec("duct", ItemTransportSpec.PortBehavior.DUCT, 0);
        ItemTransportSpec armored = spec("armored", ItemTransportSpec.PortBehavior.ARMORED_DUCT, 0);
        CapabilitySnapshot capabilities = capabilities(conveyor, duct, armored);
        MiningLayout layout = layout();
        ExitAnchor exit = new ExitAnchor(new TileKey(2, 1), ExitPort.Side.TOP, ExitPort.Bias.CENTER, 0);
        List<BuildPlanCompiler.CompileRecord> records = List.of(
            record("mod:drill", 0, 0, 0, null), record(conveyor.id().value(), 1, 0, 0, null),
            record(armored.id().value(), 2, 0, 1, null), record(conveyor.id().value(), 2, 1, 1, null));
        assertFalse(new FinalValidator().validatePhysical(records, layout, exit, capabilities).valid());
        ArrayList<BuildPlanCompiler.CompileRecord> compatible = new ArrayList<>(records);
        compatible.set(1, record(duct.id().value(), 1, 0, 0, null));
        var accepted = new FinalValidator().validatePhysical(compatible, layout, exit, capabilities);
        assertTrue(accepted.valid(), accepted.diagnostics().toString());
    }

    @Test
    void bridgeTargetsRequireExactTypeRangeAndNoBacklink() {
        ItemTransportSpec bridge = spec("bridge", ItemTransportSpec.PortBehavior.ITEM_BRIDGE, 3);
        ItemTransportSpec other = spec("other-bridge", ItemTransportSpec.PortBehavior.ITEM_BRIDGE, 3);
        CapabilitySnapshot capabilities = capabilities(bridge, other);
        ExitAnchor exit = new ExitAnchor(new TileKey(4, 0), ExitPort.Side.RIGHT, ExitPort.Bias.CENTER, 0);
        List<BuildPlanCompiler.CompileRecord> valid = List.of(record("mod:drill", 0, 0, 0, null),
            record(bridge.id().value(), 1, 0, 0, new TileOffset(3, 0)), record(bridge.id().value(), 4, 0, 0, null));
        assertTrue(new FinalValidator().validatePhysical(valid, layout(), exit, capabilities).valid());
        for (var receiver : List.of(record(other.id().value(), 4, 0, 0, null),
            record(bridge.id().value(), 4, 0, 2, new TileOffset(-3, 0)))) {
            assertFalse(new FinalValidator().validatePhysical(List.of(valid.get(0), valid.get(1), receiver),
                layout(), exit, capabilities).valid());
        }
        var tooFar = List.of(valid.get(0), record(bridge.id().value(), 1, 0, 0, new TileOffset(4, 0)),
            record(bridge.id().value(), 5, 0, 0, null));
        assertFalse(new FinalValidator().validatePhysical(tooFar, layout(),
            new ExitAnchor(new TileKey(5, 0), ExitPort.Side.RIGHT, ExitPort.Bias.CENTER, 0), capabilities).valid());
    }

    @Test
    void locallyCompatibleTransportCycleIsNotAConsistentSinkNetwork() {
        ItemTransportSpec conveyor = spec("conveyor", ItemTransportSpec.PortBehavior.CONVEYOR, 0);
        Map<TileKey, TransportPlacement> cycle = new LinkedHashMap<>();
        cycle.put(new TileKey(0, 0), placement(conveyor, 0, 0, 0, 1, 0));
        cycle.put(new TileKey(1, 0), placement(conveyor, 1, 0, 1, 1, 1));
        cycle.put(new TileKey(1, 1), placement(conveyor, 1, 1, 2, 0, 1));
        cycle.put(new TileKey(0, 1), placement(conveyor, 0, 1, 3, 0, 0));
        var exit = new ExitAnchor(new TileKey(3, 0), ExitPort.Side.RIGHT, ExitPort.Bias.CENTER, 0);
        assertFalse(TransportGeometry.networkConsistent(cycle, exit),
            "every transport tile must terminate at the selected exit, not merely connect locally");
    }

    private ItemTransportSpec spec(String name, ItemTransportSpec.PortBehavior behavior, int range) {
        return new ItemTransportSpec(ContentId.of("mod:" + name), 1, 10f, range, range > 0, CostVector.empty(),
            SimulationFidelity.BOUNDED_MODEL, behavior);
    }

    private CapabilitySnapshot capabilities(ItemTransportSpec... specs) {
        Map<ContentId, CapabilityDescriptor> descriptors = new LinkedHashMap<>();
        for (var spec : specs) descriptors.put(spec.id(), new CapabilityDescriptor(spec.id(), CapabilityKind.ITEM_TRANSPORT,
            EnumSet.of(CapabilityState.AVAILABLE_NOW), Set.of(), spec.cost(), "test", spec));
        return new CapabilitySnapshot(descriptors);
    }

    private MiningLayout layout() {
        TileKey tile = new TileKey(0, 0);
        return MiningLayout.of(List.of(new DrillCandidate("source", ContentId.of("mod:drill"), tile, 0,
            PlacementFootprint.of(Set.of(tile)), Set.of(tile), new ProductionEstimate(ContentId.of("mindustry:copper"), 2f),
            List.of(), CostVector.empty(), Set.of())));
    }

    private TransportPlacement placement(ItemTransportSpec spec, int x, int y, int rotation,
                                         int outputX, int outputY) {
        return new TransportPlacement(spec, new TileKey(x, y), rotation,
            new TileKey(outputX, outputY), null);
    }

    private BuildPlanCompiler.CompileRecord record(String block, int x, int y, int rotation, Object config) {
        return new BuildPlanCompiler.CompileRecord(block, new TileKey(x, y), rotation, config, Set.of(), BuildPlanCompiler.Operation.ADD);
    }
}
