package autodrillnext.solver;

import autodrillnext.capability.spec.CapabilityDescriptor;
import autodrillnext.capability.spec.CapabilityKind;
import autodrillnext.capability.spec.CapabilitySnapshot;
import autodrillnext.capability.spec.CapabilityState;
import autodrillnext.capability.spec.CostVector;
import autodrillnext.capability.spec.ItemTransportSpec;
import autodrillnext.capability.spec.ResourceValuation;
import autodrillnext.model.ContentId;
import autodrillnext.model.DrillCandidate;
import autodrillnext.model.EdgeKind;
import autodrillnext.model.ExistingNetwork;
import autodrillnext.model.PlanEdge;
import autodrillnext.model.PlanGraph;
import autodrillnext.model.PlanNode;
import autodrillnext.model.MiningLayout;
import autodrillnext.model.PlannerRequest;
import autodrillnext.model.ProductionEstimate;
import autodrillnext.model.RoutingProgress;
import autodrillnext.world.ExitAnchor;
import autodrillnext.world.ExitPort;
import autodrillnext.world.OrePatch;
import autodrillnext.world.PlacementFootprint;
import autodrillnext.world.TerrainRevision;
import autodrillnext.world.TerrainSnapshot;
import autodrillnext.world.TileKey;
import autodrillnext.world.TileOffset;
import autodrillnext.world.TileState;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ItemRouteSolverTest {
    @Test
    void lowRateProductionUsesCheaperBeltsInsteadOfAShorterExpensiveBridge() {
        PlanGraph graph = costChoiceRoute(0.48f);
        assertEquals(0.48f, graph.qOut(), 0.0001f);
        assertEquals(6, graph.cost().materials().amount(autodrillnext.model.ItemId.of("copper")));
        assertTrue(graph.placements().stream().allMatch(p -> p.spec().id().equals(ContentId.of("cheap-belt"))));
    }

    @Test
    void productionBeyondCheapBeltCapacityStillUsesSufficientTransport() {
        PlanGraph graph = costChoiceRoute(5f);
        assertEquals(5f, graph.qOut(), 0.0001f);
        assertEquals(20, graph.cost().materials().amount(autodrillnext.model.ItemId.of("copper")));
    }

    private PlanGraph costChoiceRoute(float production) {
        var copper = autodrillnext.model.ItemId.of("copper");
        var belt = new ItemTransportSpec(ContentId.of("cheap-belt"), 1, 1f, 0, false,
            CostVector.of(Map.of(copper, 1)));
        var bridge = new ItemTransportSpec(ContentId.of("expensive-bridge"), 1, 8f, 5, true,
            CostVector.of(Map.of(copper, 10)));
        var layout = MiningLayout.of(List.of(drill("source", 0, 0, production)));
        var sink = new ExitAnchor(new TileKey(6, 0), ExitPort.Side.RIGHT, ExitPort.Bias.CENTER, 0);
        var capabilities = capabilities(belt, bridge);
        var graph = new ItemRouteSolver().route(layout, sink, openTerrain(0, 0, 6, 0), capabilities, ExistingNetwork.empty(), Set.of(), false, 1f);
        assertPhysical(graph, layout, sink, capabilities);
        return graph;
    }

    @Test
    void multipleDrillsReuseASharedGroundTrunkAndStopAtOneSink() {
        DrillCandidate first = drill("first", 0, 0, 3f);
        DrillCandidate second = drill("second", 0, 2, 3f);
        TerrainSnapshot terrain = openTerrain(0, 0, 4, 2);
        ExitAnchor sink = new ExitAnchor(new TileKey(4, 1), ExitPort.Side.RIGHT, ExitPort.Bias.CENTER, 0);

        PlanGraph graph = new ItemRouteSolver().route(MiningLayout.of(List.of(first, second)), sink, terrain, capabilities(4f, false), ExistingNetwork.empty(), Set.of(), false, 1f);

        assertTrue(graph.edges().stream().anyMatch(edge -> edge.kind() == EdgeKind.GROUND_EDGE));
        assertEquals(1, graph.nodes().stream().filter(node -> node.kind() == PlanNode.Kind.SINK).count());
        assertTrue(graph.nodes().stream().noneMatch(node -> node.kind() == PlanNode.Kind.DOWNSTREAM));
        assertTrue(graph.edges().stream().filter(edge -> edge.kind() == EdgeKind.GROUND_EDGE).count() < 8);
        Set<TileKey> plannedTiles = new java.util.HashSet<>();
        for (PlanEdge edge : graph.edges()) {
            if (edge.kind() == EdgeKind.EXISTING_EDGE
                || edge.kind() == EdgeKind.SOURCE_EDGE
                || edge.kind() == EdgeKind.SINK_EDGE) continue;
            for (TileKey tile : edge.footprint()) {
                assertTrue(plannedTiles.add(tile), "planned transport tiles must be unique: " + tile);
            }
        }
    }

    @Test
    void blockedGroundCellUsesBridgeJumpWithEndpointOnlyFootprint() {
        DrillCandidate source = drill("source", 0, 1, 2f);
        ExitAnchor sink = new ExitAnchor(new TileKey(4, 1), ExitPort.Side.RIGHT, ExitPort.Bias.CENTER, 0);
        LinkedHashMap<TileKey, TileState> tiles = new LinkedHashMap<>();
        for (int x = 0; x <= 4; x++) {
            tiles.put(new TileKey(x, 1), new TileState(false, false, false, null, null, null, null));
        }
        tiles.put(new TileKey(2, 1), new TileState(false, true, false, null, null, null, null));
        TerrainSnapshot terrain = TerrainSnapshot.of(tiles, TerrainRevision.of(1));

        PlanGraph graph = new ItemRouteSolver().route(MiningLayout.of(List.of(source)), sink, terrain, capabilities(4f, true), ExistingNetwork.empty(), Set.of(), false, 1f);

        var records = new autodrillnext.compile.BuildPlanCompiler().compile(graph);
        Set<TileKey> occupied = new java.util.HashSet<>();
        for (var record : records) {
            assertTrue(occupied.add(record.tile()), "duplicate bridge endpoint: " + record.tile());
            assertTrue(!terrain.tile(record.tile()).solid(), "bridge placed on blocked ground");
        }
        assertTrue(!occupied.contains(source.anchor()), "transport cannot occupy the drill itself");
        assertTrue(occupied.contains(new TileKey(1, 1)), "bridge sender must receive at the drill perimeter");
        assertTrue(occupied.contains(sink.tile()));
    }

    @Test
    void chainedBridgesCompileOneBlockPerEndpointWithoutConveyorOverlap() {
        var ground = capabilities(4f, false);
        var bridge = capabilities(4f, true);
        LinkedHashMap<ContentId, CapabilityDescriptor> descriptors = new LinkedHashMap<>(ground.descriptors());
        descriptors.putAll(bridge.descriptors());
        LinkedHashMap<TileKey, TileState> tiles = new LinkedHashMap<>();
        for (int x = 0; x <= 8; x++) tiles.put(new TileKey(x, 0), TileState.empty());
        tiles.put(new TileKey(3, 0), new TileState(false, true, false, null, null, null, null));
        TerrainSnapshot terrain = TerrainSnapshot.of(tiles, TerrainRevision.of(1));
        MiningLayout layout = MiningLayout.of(List.of(drill("source", 0, 0, 2f)));
        PlanGraph graph = new ItemRouteSolver().route(layout, new ExitAnchor(new TileKey(8, 0), ExitPort.Side.RIGHT, ExitPort.Bias.CENTER, 0), terrain, new CapabilitySnapshot(descriptors), ExistingNetwork.empty(), Set.of(new TileKey(0, 0)), false, 1f);
        assertTrue(graph.diagnostics().isEmpty(), graph.diagnostics().toString());
        var validation = new autodrillnext.compile.FinalValidator().validate(graph,
            new autodrillnext.compile.LiveSnapshot(terrain, autodrillnext.model.Inventory.empty(),
                new CapabilitySnapshot(descriptors), true), layout);
        assertTrue(validation.valid(), validation.diagnostics().toString());
        var records = new autodrillnext.compile.BuildPlanCompiler().compile(graph, layout);
        java.util.HashSet<TileKey> placed = new java.util.HashSet<>();
        for (var record : records) assertTrue(placed.add(record.tile()), "duplicate building at " + record.tile());
        assertTrue(records.stream().anyMatch(record -> record.blockId().equals("mod:bridge")));
        assertTrue(records.stream().anyMatch(record -> record.tile().equals(new TileKey(1, 0))),
            "the drill outlet must have a transport block before the first bridge");
        for (var record : records) {
            if (!(record.config() instanceof TileOffset offset)) continue;
            TileKey target = offset.from(record.tile());
            var receiver = records.stream().filter(other -> other.tile().equals(target)).findFirst().orElseThrow();
            assertEquals(record.blockId(), receiver.blockId(), "bridge target must be a bridge of the same type");
            if (receiver.config() instanceof TileOffset reverse) {
                assertTrue(!reverse.from(receiver.tile()).equals(record.tile()),
                    "bridge relay must not link back to its sender");
            }
        }
    }

    @Test
    void mixedFacilitiesFollowPerBlockTerrainRulesAndShareTheActualBottleneck() {
        ContentId slow = ContentId.of("mod:slow");
        ContentId fast = ContentId.of("mod:fast");
        ItemTransportSpec first = new ItemTransportSpec(slow, 1, 2f, 0, false, CostVector.empty());
        ItemTransportSpec second = new ItemTransportSpec(fast, 1, 20f, 0, false, CostVector.empty());
        CapabilitySnapshot capabilities = capabilities(first, second);
        TerrainSnapshot terrain = restrictedTerrain(Map.of(
            slow, Set.of(new TileKey(1, 0), new TileKey(2, 0)),
            fast, Set.of(new TileKey(3, 0), new TileKey(4, 0))
        ), 4);
        MiningLayout layout = MiningLayout.of(List.of(drill("source", 0, 0, 8f)));
        ExitAnchor sink = new ExitAnchor(new TileKey(4, 0), ExitPort.Side.RIGHT, ExitPort.Bias.CENTER, 0);
        PlanGraph graph = new ItemRouteSolver().route(layout, sink, terrain, capabilities, ExistingNetwork.empty(), Set.of(), true, 1f);
        assertTrue(graph.diagnostics().isEmpty(), graph.diagnostics().toString());
        assertEquals(Set.of(slow.value(), fast.value()), Set.copyOf(graph.transportIds()));
        assertEquals(2f, graph.qOut(), 0.0001f);
        assertPhysical(graph, layout, sink, capabilities);
        PlanGraph homogeneous = new ItemRouteSolver().route(layout, sink, terrain, capabilities,
            ExistingNetwork.empty(), Set.of(), false, 1f);
        assertTrue(!homogeneous.diagnostics().isEmpty(), "disabled mixing cannot join terrain-exclusive ground types");
    }

    @Test
    void conveyorSideSourcesReserveAdmissionSpaceWithoutDisablingLowRateMerges() {
        ContentId belt = ContentId.of("mod:conveyor");
        ItemTransportSpec spec = new ItemTransportSpec(belt, 1, 7f, 0, false, CostVector.empty());
        CapabilitySnapshot capabilities = capabilities(spec);
        TerrainSnapshot terrain = TerrainSnapshot.of(Map.of(new TileKey(0, 1), TileState.empty(),
            new TileKey(1, 0), TileState.empty(), new TileKey(1, 1), TileState.empty()), TerrainRevision.of(1));
        ExitAnchor sink = new ExitAnchor(new TileKey(1, 1), ExitPort.Side.TOP, ExitPort.Bias.CENTER, 0);
        MiningLayout overloaded = MiningLayout.of(List.of(drill("side", 0, 1, 3.5f), drill("rear", 1, 0, 3.5f)));
        PlanGraph high = new ItemRouteSolver().route(overloaded, sink, terrain, capabilities, ExistingNetwork.empty(), Set.of(), false, 1f);
        assertEquals(4f, high.qOut(), 0.0001f, "rear spacing cannot be used to budget side admission");
        MiningLayout lowRate = MiningLayout.of(List.of(drill("side", 0, 1, 1f), drill("rear", 1, 0, 1f)));
        PlanGraph low = new ItemRouteSolver().route(lowRate, sink, terrain, capabilities, ExistingNetwork.empty(), Set.of(), false, 1f)
            .withTransportHeadroom(1.1f);
        low = low.withFlow(new FlowSolver().assign(low));
        assertEquals(2f, low.qOut(), 0.0001f);
        assertPhysical(low, lowRate, sink, capabilities);
        MiningLayout rearOnly = MiningLayout.of(List.of(drill("rear", 1, 0, 7f)));
        PlanGraph straight = new ItemRouteSolver().route(rearOnly, sink, terrain, capabilities, ExistingNetwork.empty(), Set.of(), false, 1f);
        assertEquals(7f, straight.qOut(), 0.0001f, "rear-only input retains normal conveyor capacity");
    }

    @Test
    void loneConveyorBendRetainsCapacityButCompetingPredecessorReservesSideAdmission() {
        CapabilitySnapshot capabilities = capabilities(7f, false);
        TerrainSnapshot terrain = TerrainSnapshot.of(Map.of(new TileKey(0, 0), TileState.empty(),
            new TileKey(1, 0), TileState.empty(), new TileKey(2, 0), TileState.empty(),
            new TileKey(2, 1), TileState.empty()), TerrainRevision.of(1));
        MiningLayout layout = MiningLayout.of(List.of(drill("rear", 0, 0, 7f)));
        ExitAnchor sink = new ExitAnchor(new TileKey(2, 1), ExitPort.Side.TOP, ExitPort.Bias.CENTER, 0);
        PlanGraph graph = new ItemRouteSolver().route(layout, sink, terrain, capabilities, ExistingNetwork.empty(), Set.of(), false, 1f);
        assertEquals(7f, graph.qOut(), 0.0001f, "a single side input inserts halfway along the belt");
        assertPhysical(graph, layout, sink, capabilities);
        Map<TileKey, TileState> withRearSource = new LinkedHashMap<>(terrain.tiles());
        withRearSource.put(new TileKey(2, -1), TileState.empty());
        MiningLayout merged = MiningLayout.of(List.of(drill("side", 0, 0, 3.5f), drill("rear", 2, -1, 3.5f)));
        PlanGraph merging = new ItemRouteSolver().route(merged, sink, TerrainSnapshot.of(withRearSource, TerrainRevision.of(2)), capabilities, ExistingNetwork.empty(), Set.of(), false, 1f);
        assertEquals(4f, merging.qOut(), 0.0001f, "the physical side conveyor competes with the rear drill");
        assertPhysical(merging, merged, sink, capabilities);
    }

    @Test
    void bothBridgeFamiliesHaveRealReceiversAndContinueOntoTerminalBelts() {
        for (var behavior : List.of(ItemTransportSpec.PortBehavior.ITEM_BRIDGE, ItemTransportSpec.PortBehavior.DUCT_BRIDGE)) {
            ContentId bridge = ContentId.of("mod:bridge");
            ContentId belt = ContentId.of("mod:belt");
            ItemTransportSpec bridgeSpec = new ItemTransportSpec(bridge, 1, 12f, 3, true, CostVector.empty(),
                autodrillnext.simulation.SimulationFidelity.BOUNDED_MODEL, behavior);
            ItemTransportSpec beltSpec = new ItemTransportSpec(belt, 1, 8f, 0, false, CostVector.empty());
            CapabilitySnapshot capabilities = capabilities(bridgeSpec, beltSpec);
            TerrainSnapshot terrain = restrictedTerrain(Map.of(
                bridge, Set.of(new TileKey(1, 0), new TileKey(4, 0)),
                belt, Set.of(new TileKey(5, 0), new TileKey(6, 0))
            ), 6);
            MiningLayout layout = MiningLayout.of(List.of(drill("source", 0, 0, 4f)));
            ExitAnchor sink = new ExitAnchor(new TileKey(6, 0), ExitPort.Side.RIGHT, ExitPort.Bias.CENTER, 0);
            PlanGraph graph = new ItemRouteSolver().route(layout, sink, terrain, capabilities, ExistingNetwork.empty(), Set.of(), false, 1f);
            assertTrue(graph.diagnostics().isEmpty(), behavior + ": " + graph.diagnostics());
            assertEquals(4f, graph.qOut(), 0.0001f);
            assertPhysical(graph, layout, sink, capabilities);
            var records = new autodrillnext.compile.BuildPlanCompiler().compile(graph, layout);
            var sender = records.stream().filter(record -> record.tile().equals(new TileKey(1, 0))).findFirst().orElseThrow();
            assertEquals(behavior == ItemTransportSpec.PortBehavior.ITEM_BRIDGE ? new TileOffset(3, 0) : null, sender.config());
            var broken = records.stream().filter(record -> !record.tile().equals(new TileKey(4, 0))).toList();
            assertTrue(!new autodrillnext.compile.FinalValidator().validatePhysical(broken, layout, sink, capabilities).valid(),
                "missing receiver must never pass despite unchanged abstract flow");
        }
    }

    @Test
    void existingDirectionalBridgeInterceptsAutomaticLinksButNotExplicitItemLinks() {
        ContentId bridge = ContentId.of("mod:bridge");
        MiningLayout layout = MiningLayout.of(List.of(drill("source", 0, 0, 2f)));
        ExitAnchor sink = new ExitAnchor(new TileKey(6, 0), ExitPort.Side.RIGHT, ExitPort.Bias.CENTER, 0);
        Map<TileKey, TileState> tiles = new LinkedHashMap<>();
        for (int x = 0; x <= 6; x++) tiles.put(new TileKey(x, 0), TileState.empty());
        tiles.put(new TileKey(2, 0), new TileState(false, true, false, null, null, null, null));
        tiles.put(new TileKey(3, 0), new TileState(false, true, false, null, null, null, null));
        TerrainSnapshot before = TerrainSnapshot.of(tiles, TerrainRevision.of(1));
        tiles.put(new TileKey(2, 0), new TileState(false, true, false, bridge.value(), null, null, null));
        TerrainSnapshot intercepted = TerrainSnapshot.of(tiles, TerrainRevision.of(2));
        for (var behavior : List.of(ItemTransportSpec.PortBehavior.DUCT_BRIDGE, ItemTransportSpec.PortBehavior.ITEM_BRIDGE)) {
            ItemTransportSpec spec = new ItemTransportSpec(bridge, 1, 10f, 3, true, CostVector.empty(),
                autodrillnext.simulation.SimulationFidelity.BOUNDED_MODEL, behavior);
            CapabilitySnapshot capabilities = capabilities(spec);
            PlanGraph original = new ItemRouteSolver().route(layout, sink, before, capabilities, ExistingNetwork.empty(), Set.of(), false, 1f);
            assertTrue(original.diagnostics().isEmpty(), original.diagnostics().toString());
            var live = new autodrillnext.compile.LiveSnapshot(intercepted, autodrillnext.model.Inventory.empty(), capabilities, true);
            var validation = new autodrillnext.compile.FinalValidator().validate(original, live, layout);
            PlanGraph revised = new ItemRouteSolver().route(layout, sink, intercepted, capabilities, ExistingNetwork.empty(), Set.of(), false, 1f);
            if (behavior == ItemTransportSpec.PortBehavior.DUCT_BRIDGE) {
                assertTrue(!validation.valid(), "an existing same-type bridge hijacks the automatic link");
                assertTrue(!revised.diagnostics().isEmpty(), "the corridor has no safe automatic-bridge route");
            } else {
                assertTrue(validation.valid(), validation.diagnostics().toString());
                assertTrue(revised.diagnostics().isEmpty(), revised.diagnostics().toString());
                assertPhysical(revised, layout, sink, capabilities);
            }
        }
    }

    @Test
    void terminalBendPointsUpInGameCoordinatesAndRejectsOpposingMerge() {
        CapabilitySnapshot capabilities = capabilities(8f, false);
        MiningLayout layout = MiningLayout.of(List.of(drill("source", 0, 0, 4f)));
        ExitAnchor sink = new ExitAnchor(new TileKey(2, 1), ExitPort.Side.TOP, ExitPort.Bias.CENTER, 0);
        PlanGraph graph = new ItemRouteSolver().route(layout, sink, openTerrain(0, 0, 2, 1), capabilities, ExistingNetwork.empty(), Set.of(), false, 1f);
        assertPhysical(graph, layout, sink, capabilities);
        var records = new autodrillnext.compile.BuildPlanCompiler().compile(graph, layout);
        assertEquals(1, records.stream().filter(record -> record.tile().equals(sink.tile())).findFirst().orElseThrow().rotation());
        var reversed = records.stream().map(record -> record.tile().equals(sink.tile())
            ? new autodrillnext.compile.BuildPlanCompiler.CompileRecord(record.blockId(), record.tile(), 3,
                record.config(), record.dependencies(), record.operation()) : record).toList();
        assertTrue(!new autodrillnext.compile.FinalValidator().validatePhysical(reversed, layout, sink, capabilities).valid());
    }

    @Test
    void disabledMixingRejectsTerrainThatRequiresTwoBridgeBlockIds() {
        var first = new ItemTransportSpec(ContentId.of("first-bridge"), 1, 8f, 2, true, CostVector.empty());
        var second = new ItemTransportSpec(ContentId.of("second-bridge"), 1, 8f, 2, true, CostVector.empty());
        var capabilities = capabilities(first, second);
        var terrain = restrictedTerrain(Map.of(
            first.id(), Set.of(new TileKey(1, 0), new TileKey(3, 0)),
            second.id(), Set.of(new TileKey(4, 0), new TileKey(6, 0))
        ), 6);
        var layout = MiningLayout.of(List.of(drill("source", 0, 0, 3f)));
        var sink = new ExitAnchor(new TileKey(6, 0), ExitPort.Side.RIGHT, ExitPort.Bias.CENTER, 0);
        var solver = new ItemRouteSolver();
        var mixed = solver.route(layout, sink, terrain, capabilities, ExistingNetwork.empty(), Set.of(), true, 1.2f);
        assertEquals(3f, mixed.qOut(), 0.0001f);
        assertEquals(Set.of(first.id().value(), second.id().value()), Set.copyOf(mixed.transportIds()));
        assertPhysical(mixed, layout, sink, capabilities);
        var homogeneous = solver.route(layout, sink, terrain, capabilities, ExistingNetwork.empty(), Set.of(), false, 1.2f);
        assertTrue(!homogeneous.diagnostics().isEmpty(), "disabled mixing must also restrict bridge IDs");
    }

    @Test
    void mixedMaterialsCheapensBothLowFlowBranchesWithoutDowngradingTheSharedTrunk() {
        var copper = autodrillnext.model.ItemId.of("copper");
        var cheap = new ItemTransportSpec(ContentId.of("cheap-belt"), 1, 4f, 0, false,
            CostVector.of(Map.of(copper, 1)));
        var fast = new ItemTransportSpec(ContentId.of("fast-belt"), 1, 24f, 0, false,
            CostVector.of(Map.of(copper, 5)));
        var capabilities = capabilities(cheap, fast);
        var layout = MiningLayout.of(List.of(drill("lower", 0, 0, 3f), drill("upper", 0, 4, 3f)));
        var sink = new ExitAnchor(new TileKey(6, 2), ExitPort.Side.RIGHT, ExitPort.Bias.CENTER, 0);
        Map<TileKey, TileState> tiles = new LinkedHashMap<>();
        for (int x = 0; x <= 3; x++) {
            tiles.put(new TileKey(x, 0), TileState.empty());
            tiles.put(new TileKey(x, 4), TileState.empty());
        }
        for (int y = 0; y <= 4; y++) tiles.put(new TileKey(3, y), TileState.empty());
        for (int x = 3; x <= 6; x++) tiles.put(new TileKey(x, 2), TileState.empty());
        var terrain = TerrainSnapshot.of(tiles, TerrainRevision.of(1));
        var solver = new ItemRouteSolver();
        PlanGraph homogeneous = solver.route(layout, sink, terrain, capabilities,
            ExistingNetwork.empty(), Set.of(), false, 1.2f);
        PlanGraph mixed = solver.route(layout, sink, terrain, capabilities,
            ExistingNetwork.empty(), Set.of(), true, 1.2f);
        assertEquals(6f, homogeneous.qOut(), 0.0001f);
        assertEquals(homogeneous.qOut(), mixed.qOut(), 0.0001f);
        assertTrue(mixed.cost().materials().total() < homogeneous.cost().materials().total());
        assertEquals(Set.of(cheap.id(), fast.id()), mixed.placements().stream()
            .map(p -> p.spec().id()).collect(java.util.stream.Collectors.toSet()));
        for (int y : List.of(0, 4)) {
            assertEquals(cheap.id(), mixed.placements().stream().filter(p -> p.tile().equals(new TileKey(1, y)))
                .findFirst().orElseThrow().spec().id(), "both branches must use cheap materials");
        }
        for (var placement : mixed.placements()) {
            if (placement.tile().y() == 2) assertEquals(fast.id(), placement.spec().id(), "trunk carries both sources");
        }
        ArrayList<PlanGraph> homogeneousChoices = new ArrayList<>(homogeneous.alternatives());
        homogeneousChoices.add(homogeneous);
        for (PlanGraph choice : homogeneousChoices) {
            assertEquals(1, choice.placements().stream().map(p -> p.spec().id()).distinct().count());
        }
        for (PlanEdge edge : mixed.edges()) {
            assertEquals(edge.nominalCapacity(), edge.usableCapacity(), 0.0001f,
                "returned capacities must not already include headroom");
        }
        assertEquals(6f, new FlowSolver().assign(mixed.withTransportHeadroom(1.2f)).qOut(), 0.0001f);
        PlanGraph extraReserve = solver.route(layout, sink, terrain, capabilities,
            ExistingNetwork.empty(), Set.of(), true, 2f);
        assertEquals(6f, new FlowSolver().assign(extraReserve.withTransportHeadroom(2f)).qOut(), 0.0001f);
        assertTrue(extraReserve.placements().stream().allMatch(p -> p.spec().id().equals(fast.id())),
            "a nominally sufficient branch cannot violate requested reserve");
        assertPhysical(mixed, layout, sink, capabilities);
        assertPhysical(extraReserve, layout, sink, capabilities);
    }

    @Test
    void cheaperArmoredBeltCannotReplaceSideFedSourceAdmission() {
        var copper = autodrillnext.model.ItemId.of("copper");
        var cheap = new ItemTransportSpec(ContentId.of("armored-belt"), 1, 12f, 0, false,
            CostVector.of(Map.of(copper, 1)), autodrillnext.simulation.SimulationFidelity.BOUNDED_MODEL,
            ItemTransportSpec.PortBehavior.ARMORED_CONVEYOR);
        var regular = new ItemTransportSpec(ContentId.of("regular-belt"), 1, 12f, 0, false,
            CostVector.of(Map.of(copper, 5)));
        var layout = MiningLayout.of(List.of(drill("side", 0, 0, 2f)));
        var sink = new ExitAnchor(new TileKey(1, 0), ExitPort.Side.TOP, ExitPort.Bias.CENTER, 0);
        var capabilities = capabilities(cheap, regular);
        var graph = new ItemRouteSolver().route(layout, sink, openTerrain(0, 0, 1, 0), capabilities,
            ExistingNetwork.empty(), Set.of(), true, 1.2f);
        assertEquals(2f, graph.qOut(), 0.0001f);
        assertEquals(List.of(regular.id().value()), graph.transportIds());
        assertPhysical(graph, layout, sink, capabilities);
    }

    @Test
    void bridgeMaterialDowngradesRespectRangeAndExistingAutomaticLinkInterception() {
        var copper = autodrillnext.model.ItemId.of("copper");
        var fast = new ItemTransportSpec(ContentId.of("explicit-bridge"), 1, 20f, 5, true,
            CostVector.of(Map.of(copper, 8)));
        var layout = MiningLayout.of(List.of(drill("source", 0, 0, 3f)));
        var sink = new ExitAnchor(new TileKey(6, 0), ExitPort.Side.RIGHT, ExitPort.Bias.CENTER, 0);
        for (var behavior : List.of(ItemTransportSpec.PortBehavior.ITEM_BRIDGE, ItemTransportSpec.PortBehavior.DUCT_BRIDGE)) {
            var cheap = new ItemTransportSpec(ContentId.of("cheap-bridge"), 1, 4f,
                behavior == ItemTransportSpec.PortBehavior.ITEM_BRIDGE ? 2 : 5, true,
                CostVector.of(Map.of(copper, 1)), autodrillnext.simulation.SimulationFidelity.BOUNDED_MODEL, behavior);
            Map<TileKey, TileState> tiles = new LinkedHashMap<>();
            tiles.put(new TileKey(0, 0), TileState.empty());
            tiles.put(new TileKey(1, 0), TileState.empty());
            tiles.put(new TileKey(6, 0), TileState.empty());
            tiles.put(new TileKey(3, 0), new TileState(false, true, false, cheap.id().value(), null, null, null));
            var terrain = TerrainSnapshot.of(tiles, TerrainRevision.of(1));
            var capabilities = capabilities(cheap, fast);
            var graph = new ItemRouteSolver().route(layout, sink, terrain, capabilities,
                ExistingNetwork.empty(), Set.of(), true, 1.2f);
            assertEquals(3f, graph.qOut(), 0.0001f);
            assertEquals(List.of(fast.id().value()), graph.transportIds());
            assertPhysical(graph, layout, sink, capabilities);
        }
    }

    @Test
    void higherPerBlockBridgeCostWinsWhenItAvoidsTheLongGroundDetour() {
        var copper = autodrillnext.model.ItemId.of("copper");
        var cheap = new ItemTransportSpec(ContentId.of("cheap-belt"), 1, 4f, 0, false,
            CostVector.of(Map.of(copper, 1)));
        var fast = new ItemTransportSpec(ContentId.of("fast-belt"), 1, 24f, 0, false,
            CostVector.of(Map.of(copper, 5)));
        var bridge = new ItemTransportSpec(ContentId.of("shortcut"), 1, 4f, 8, true,
            CostVector.of(Map.of(copper, 2)));
        var layout = MiningLayout.of(List.of(drill("a-main", 10, 0, 3f), drill("b-remote", 0, 0, 3f)));
        var sink = new ExitAnchor(new TileKey(12, 0), ExitPort.Side.RIGHT, ExitPort.Bias.CENTER, 0);
        Map<TileKey, TileState> tiles = new LinkedHashMap<>();
        for (int x = 0; x <= 12; x++) tiles.put(new TileKey(x, 2), TileState.empty());
        for (int y = 0; y <= 2; y++) {
            tiles.put(new TileKey(1, y), TileState.empty());
            tiles.put(new TileKey(9, y), TileState.empty());
            tiles.put(new TileKey(11, y), TileState.empty());
        }
        for (int x : List.of(0, 9, 10, 11, 12)) tiles.put(new TileKey(x, 0), TileState.empty());
        var terrain = TerrainSnapshot.of(tiles, TerrainRevision.of(1));
        var capabilities = capabilities(cheap, fast, bridge);
        var solver = new ItemRouteSolver();
        var withoutShortcut = solver.route(layout, sink, terrain, capabilities(cheap, fast),
            ExistingNetwork.empty(), Set.of(), true, 1.2f);
        var graph = solver.route(layout, sink, terrain, capabilities,
            ExistingNetwork.empty(), Set.of(), true, 1.2f);
        assertEquals(6f, graph.qOut(), 0.0001f);
        assertEquals(6f, new FlowSolver().assign(graph.withTransportHeadroom(1.2f)).qOut(), 0.0001f);
        assertTrue(graph.cost().materials().economicValue() < withoutShortcut.cost().materials().economicValue());
        assertTrue(graph.placements().size() < withoutShortcut.placements().size());
        assertTrue(graph.placements().stream().anyMatch(p -> p.spec().id().equals(bridge.id())));
        assertTrue(graph.placements().stream().anyMatch(p -> p.spec().id().equals(cheap.id())));
        assertTrue(graph.placements().stream().anyMatch(p -> p.spec().id().equals(fast.id())));
        assertPhysical(graph, layout, sink, capabilities);
    }

    @Test
    void equalMaterialRoutesPreferFewerBlocksDeterministically() {
        var copper = autodrillnext.model.ItemId.of("copper");
        var belt = new ItemTransportSpec(ContentId.of("belt"), 1, 8f, 0, false,
            CostVector.of(Map.of(copper, 1)));
        var bridge = new ItemTransportSpec(ContentId.of("bridge"), 1, 8f, 5, true,
            CostVector.of(Map.of(copper, 3)));
        var layout = MiningLayout.of(List.of(drill("source", 0, 0, 2f)));
        var sink = new ExitAnchor(new TileKey(6, 0), ExitPort.Side.RIGHT, ExitPort.Bias.CENTER, 0);
        var solver = new ItemRouteSolver();
        var first = solver.route(layout, sink, openTerrain(0, 0, 6, 0), capabilities(belt, bridge),
            ExistingNetwork.empty(), Set.of(), true, 1.2f);
        var reordered = solver.route(layout, sink, openTerrain(0, 0, 6, 0), capabilities(bridge, belt),
            ExistingNetwork.empty(), Set.of(), true, 1.2f);
        assertEquals(2f, first.qOut(), 0.0001f);
        assertEquals(6, first.cost().materials().amount(copper));
        assertEquals(2, first.placements().size(), "equal material expense favors the two bridge endpoints");
        assertEquals(first.placements(), reordered.placements());
        assertPhysical(first, layout, sink, capabilities(belt, bridge));
    }

    @Test
    void materialChoiceUsesFractionalResourceValueRatherThanEqualRawItemCounts() {
        var first = autodrillnext.model.ItemId.of("first-material");
        var second = autodrillnext.model.ItemId.of("second-material");
        var values = ResourceValuation.of(Map.of(first, 0.5d, second, 0.75d));
        var cheap = new ItemTransportSpec(ContentId.of("z-cheap-belt"), 1, 8f, 0, false,
            CostVector.of(Map.of(first, 1), values));
        var expensive = new ItemTransportSpec(ContentId.of("a-expensive-belt"), 1, 8f, 0, false,
            CostVector.of(Map.of(second, 1), values));
        var layout = MiningLayout.of(List.of(drill("source", 0, 0, 2f)));
        var sink = new ExitAnchor(new TileKey(4, 0), ExitPort.Side.RIGHT, ExitPort.Bias.CENTER, 0);
        var graph = new ItemRouteSolver().route(layout, sink, openTerrain(0, 0, 4, 0),
            capabilities(expensive, cheap), ExistingNetwork.empty(), Set.of(), true, 1.2f);
        assertEquals(2f, graph.qOut(), 0.0001f);
        assertEquals(List.of(cheap.id().value()), graph.transportIds());
        assertPhysical(graph, layout, sink, capabilities(expensive, cheap));
    }

    @Test
    void laterBridgeRebindsEarlierDrillAndRemovesItsRedundantBeltBranch() {
        for (boolean mixed : List.of(false, true)) {
            var fixture = laterBridgeFixture(true);
            List<RoutingProgress> progress = new ArrayList<>();
            var graph = new ItemRouteSolver().route(fixture.layout(), fixture.sink(), fixture.terrain(),
                fixture.capabilities(), ExistingNetwork.empty(), Set.of(), mixed, 1.2f, progress::add);
            assertEquals(5f, graph.qOut(), 0.0001f);
            assertEquals(5, graph.placements().size());
            assertTrue(progress.stream().anyMatch(p -> p.phase() == RoutingProgress.Phase.SIMPLIFIED
                && p.removedBlocks() >= 2 && p.candidate().qOut() >= 5f - 0.0001f));
            List<PlanGraph> candidates = new ArrayList<>(graph.alternatives());
            candidates.add(graph);
            for (PlanGraph candidate : candidates) {
                assertTrue(candidate.placements().stream().noneMatch(p ->
                    p.tile().equals(new TileKey(3, 0)) || p.tile().equals(new TileKey(4, 0))));
                assertEquals(3f, candidate.edges().stream()
                    .filter(e -> e.kind() == EdgeKind.SOURCE_EDGE && e.from().equals("source:first"))
                    .mapToDouble(e -> candidate.flow().flow(e.id())).sum(), 0.0001f);
                assertEquals(5f, new FlowSolver().assign(candidate.withTransportHeadroom(1.2f)).qOut(), 0.0001f);
                assertPhysical(candidate, fixture.layout(), fixture.sink(), fixture.capabilities());
            }
        }
    }

    @Test
    void adjacentBridgeOutputAndForwardFaceDoNotReplaceANecessaryBeltFeed() {
        var fixture = laterBridgeFixture(false);
        var graph = new ItemRouteSolver().route(fixture.layout(), fixture.sink(), fixture.terrain(),
            fixture.capabilities(), ExistingNetwork.empty(), Set.of(), true, 1.2f);
        assertEquals(5f, graph.qOut(), 0.0001f);
        assertTrue(graph.placements().stream().anyMatch(p -> p.tile().equals(new TileKey(4, 1))),
            "the first drill sits between the bridge's forbidden forward face and its unlinked receiver");
        assertPhysical(graph, fixture.layout(), fixture.sink(), fixture.capabilities());
    }

    @Test
    void legalDirectBridgeDoesNotRemoveBeltNeededForCombinedFlowAndHeadroom() {
        var fixture = laterBridgeFixture(true, 3f);
        var graph = new ItemRouteSolver().route(fixture.layout(), fixture.sink(), fixture.terrain(),
            fixture.capabilities(), ExistingNetwork.empty(), Set.of(), true, 1.2f);
        assertEquals(5f, graph.qOut(), 0.0001f);
        assertTrue(graph.placements().stream().anyMatch(p -> p.tile().equals(new TileKey(3, 0))));
        assertTrue(graph.placements().stream().anyMatch(p -> p.tile().equals(new TileKey(4, 0))));
        assertEquals(5f, new FlowSolver().assign(graph.withTransportHeadroom(1.2f)).qOut(), 0.0001f);
        assertPhysical(graph, fixture.layout(), fixture.sink(), fixture.capabilities());
    }

    @Test
    void observerSeesActualSearchGeometryBeforeAnyConnectedCandidate() {
        var layout = MiningLayout.of(List.of(drill("source", 0, 0, 2f)));
        var sink = new ExitAnchor(new TileKey(12, 0), ExitPort.Side.RIGHT, ExitPort.Bias.CENTER, 0);
        List<RoutingProgress> progress = new ArrayList<>();
        boolean[] returned = {false};
        var graph = new ItemRouteSolver().route(layout, sink, openTerrain(0, 0, 12, 2),
            capabilities(8f, false), ExistingNetwork.empty(), Set.of(), false, 1.2f, update -> {
                assertTrue(!returned[0]);
                if (update.phase() == RoutingProgress.Phase.SEARCHING) {
                    assertTrue(update.expandedStates() > 0);
                    assertTrue(update.placements().stream().anyMatch(p -> p.tile().equals(update.focus())));
                    assertTrue(update.frontier().contains(update.focus()));
                    assertEquals(null, update.candidate());
                }
                progress.add(update);
            });
        returned[0] = true;
        assertEquals(2f, graph.qOut(), 0.0001f);
        int searching = -1, connected = -1;
        long expanded = 0;
        for (int i = 0; i < progress.size(); i++) {
            var update = progress.get(i);
            assertTrue(update.expandedStates() >= expanded);
            expanded = update.expandedStates();
            if (searching < 0 && update.phase() == RoutingProgress.Phase.SEARCHING) searching = i;
            if (connected < 0 && update.phase() == RoutingProgress.Phase.CONNECTED) connected = i;
        }
        assertTrue(searching >= 0 && connected > searching, "search is visible before branch completion");
    }

    @Test
    void disconnectedSourceComponentIsRejectedBeforeSearchingTheFarRegion() {
        var belt = new ItemTransportSpec(ContentId.of("belt"), 1, 20f, 0, false,
            CostVector.of(Map.of(autodrillnext.model.ItemId.of("copper"), 1)));
        var layout = MiningLayout.of(List.of(drill("source", 75, 20, 2f)));
        var sink = new ExitAnchor(new TileKey(4, 20), ExitPort.Side.LEFT, ExitPort.Bias.CENTER, 0);
        Map<TileKey, TileState> tiles = new LinkedHashMap<>();
        for (int x = 0; x < 80; x++) {
            for (int y = 0; y < 40; y++) {
                tiles.put(new TileKey(x, y), x == 40
                    ? new TileState(false, true, false, null, null, null, null) : TileState.empty());
            }
        }
        List<RoutingProgress> progress = new ArrayList<>();
        var graph = new ItemRouteSolver().route(layout, sink, TerrainSnapshot.of(tiles, TerrainRevision.of(1)),
            capabilities(belt), ExistingNetwork.empty(), Set.of(), false, 1.2f, progress::add);
        assertTrue(graph.diagnostics().stream().anyMatch(diagnostic ->
            diagnostic.code() == autodrillnext.model.DiagnosticCode.NO_ROUTE));
        assertEquals(0L, progress.stream().mapToLong(RoutingProgress::expandedStates).max().orElse(0L),
            "a relaxed reachability proof should reject disconnected terrain without directional A* expansion");
    }

    @Test
    void fixedPointCleanupReachesBranchesAddedAfterManyNecessaryTrunkTiles() {
        var copper = autodrillnext.model.ItemId.of("copper");
        var belt = new ItemTransportSpec(ContentId.of("belt"), 1, 20f, 0, false,
            CostVector.of(Map.of(copper, 1)));
        var bridge = new ItemTransportSpec(ContentId.of("bridge"), 1, 20f, 2, true,
            CostVector.of(Map.of(copper, 3)));
        var layout = MiningLayout.of(List.of(
            drill("first", 0, 0, 1f),
            drill("second", 39, 3, 1f),
            drill("third", 38, 2, 1f)));
        var sink = new ExitAnchor(new TileKey(80, 0), ExitPort.Side.RIGHT, ExitPort.Bias.CENTER, 0);
        Set<TileKey> belts = new LinkedHashSet<>();
        for (int x = 1; x <= 80; x++) belts.add(new TileKey(x, 0));
        belts.add(new TileKey(40, 1));
        belts.add(new TileKey(40, 2));
        belts.add(new TileKey(40, 3));
        belts.add(new TileKey(41, 1));
        Set<TileKey> bridges = Set.of(new TileKey(39, 2), new TileKey(41, 2));
        Map<TileKey, TileState> tiles = new LinkedHashMap<>();
        for (TileKey tile : belts) tiles.put(tile, TileState.empty());
        for (TileKey tile : bridges) tiles.put(tile, TileState.empty());
        for (DrillCandidate drill : layout.candidates()) tiles.put(drill.anchor(), TileState.empty());
        Map<ContentId, Map<TileKey, Integer>> masks = new LinkedHashMap<>();
        for (var entry : Map.of(belt.id(), belts, bridge.id(), bridges).entrySet()) {
            Map<TileKey, Integer> rotations = new LinkedHashMap<>();
            for (TileKey tile : entry.getValue()) rotations.put(tile, 15);
            masks.put(entry.getKey(), rotations);
        }
        var terrain = TerrainSnapshot.of(autodrillnext.world.WorldSnapshot.captured(tiles, TerrainRevision.of(1),
            new autodrillnext.world.PlacementRules(masks, Map.of())));
        var graph = new ItemRouteSolver().route(layout, sink, terrain, capabilities(belt, bridge),
            ExistingNetwork.empty(), Set.of(), false, 1.2f);
        assertEquals(3f, graph.qOut(), 0.0001f);
        List<PlanGraph> candidates = new ArrayList<>(graph.alternatives());
        candidates.add(graph);
        for (PlanGraph candidate : candidates) {
            assertTrue(candidate.placements().stream().noneMatch(placement ->
                Set.of(new TileKey(40, 1), new TileKey(40, 2), new TileKey(40, 3)).contains(placement.tile())),
                "every retained candidate must remove the branch after fixed-point cleanup: "
                    + candidate.placements().stream().filter(placement ->
                        placement.tile().x() >= 37 && placement.tile().x() <= 42).toList());
            assertPhysical(candidate, layout, sink, capabilities(belt, bridge));
        }
    }

    @Test
    void denseBridgeNetworkRelinksUpstreamToRemoveARedundantRelay() {
        var spec = new ItemTransportSpec(ContentId.of("bridge"), 1, 20f, 4, true,
            CostVector.of(Map.of(autodrillnext.model.ItemId.of("copper"), 1)));
        int[][] anchors = {
            {0, 6}, {2, 8}, {10, 8}, {6, 2}, {10, 4}, {4, 6},
            {2, 10}, {4, 4}, {2, 0}, {6, 10}, {0, 8}, {2, 2}
        };
        List<DrillCandidate> drills = new ArrayList<>();
        for (int i = 0; i < anchors.length; i++) {
            int x = anchors[i][0], y = anchors[i][1];
            var footprint = Set.of(new TileKey(x, y), new TileKey(x + 1, y),
                new TileKey(x, y + 1), new TileKey(x + 1, y + 1));
            drills.add(new DrillCandidate("d" + i, ContentId.of("mod:drill"), new TileKey(x, y), 0,
                PlacementFootprint.of(footprint), footprint,
                new ProductionEstimate(ContentId.of("mindustry:copper"), 1f),
                List.of(), CostVector.empty(), Set.of()));
        }
        var layout = MiningLayout.of(drills);
        var sink = new ExitAnchor(new TileKey(14, 6), ExitPort.Side.RIGHT, ExitPort.Bias.CENTER, 0);
        var graph = new ItemRouteSolver().route(layout, sink, openTerrain(-1, -1, 14, 14),
            capabilities(spec), ExistingNetwork.empty(), Set.of(), false, 1.2f);

        assertEquals(12f, graph.qOut(), 0.0001f);
        // (2,7) can link to (2,4), bypassing (2,6) without losing any drill's service.
        assertTrue(graph.placements().size() <= 13, graph.placements().toString());
        assertPhysical(graph, layout, sink, capabilities(spec));
    }

    private RouteFixture laterBridgeFixture(boolean direct) {
        return laterBridgeFixture(direct, 20f);
    }

    private RouteFixture laterBridgeFixture(boolean direct, float bridgeCapacity) {
        var copper = autodrillnext.model.ItemId.of("copper");
        var belt = new ItemTransportSpec(ContentId.of("belt"), 1, 20f, 0, false,
            CostVector.of(Map.of(copper, 1)));
        var bridge = new ItemTransportSpec(ContentId.of("bridge"), 1, bridgeCapacity, 2, true,
            CostVector.of(Map.of(copper, 3)));
        var first = direct ? drill("first", 3, 1, 3f) : drill("first", 4, 2, 3f);
        var layout = MiningLayout.of(List.of(first, drill("second", 2, 2, 2f)));
        var sink = new ExitAnchor(new TileKey(6, 0), ExitPort.Side.RIGHT, ExitPort.Bias.CENTER, 0);
        Set<TileKey> belts = direct
            ? Set.of(new TileKey(3, 0), new TileKey(4, 0), new TileKey(5, 0), new TileKey(6, 0), new TileKey(5, 1))
            : Set.of(new TileKey(4, 1), new TileKey(5, 1), new TileKey(5, 0), new TileKey(6, 0));
        Set<TileKey> bridges = Set.of(new TileKey(3, 2), new TileKey(5, 2));
        Map<TileKey, TileState> tiles = new LinkedHashMap<>();
        for (TileKey tile : belts) tiles.put(tile, TileState.empty());
        for (TileKey tile : bridges) tiles.put(tile, TileState.empty());
        for (var drill : layout.candidates()) tiles.put(drill.anchor(), TileState.empty());
        Map<ContentId, Map<TileKey, Integer>> masks = new LinkedHashMap<>();
        for (var entry : Map.of(belt.id(), belts, bridge.id(), bridges).entrySet()) {
            Map<TileKey, Integer> rotations = new LinkedHashMap<>();
            for (TileKey tile : entry.getValue()) rotations.put(tile, 15);
            masks.put(entry.getKey(), rotations);
        }
        var terrain = TerrainSnapshot.of(autodrillnext.world.WorldSnapshot.captured(tiles, TerrainRevision.of(1),
            new autodrillnext.world.PlacementRules(masks, Map.of())));
        return new RouteFixture(layout, sink, terrain, capabilities(belt, bridge));
    }

    private record RouteFixture(MiningLayout layout, ExitAnchor sink, TerrainSnapshot terrain,
                                CapabilitySnapshot capabilities) {}

    private void assertPhysical(PlanGraph graph, MiningLayout layout, ExitAnchor sink, CapabilitySnapshot capabilities) {
        var records = new autodrillnext.compile.BuildPlanCompiler().compile(graph, layout);
        assertEquals(records.size(), records.stream().map(autodrillnext.compile.BuildPlanCompiler.CompileRecord::tile).distinct().count());
        var result = new autodrillnext.compile.FinalValidator().validatePhysical(records, layout, sink, capabilities);
        assertTrue(result.valid(), result.diagnostics().toString());
    }

    private CapabilitySnapshot capabilities(ItemTransportSpec... specs) {
        Map<ContentId, CapabilityDescriptor> result = new LinkedHashMap<>();
        for (ItemTransportSpec spec : specs) result.put(spec.id(), new CapabilityDescriptor(spec.id(), CapabilityKind.ITEM_TRANSPORT,
            EnumSet.of(CapabilityState.DISCOVERED, CapabilityState.SUPPORTED, CapabilityState.AVAILABLE_NOW),
            Set.of(), spec.cost(), "test", spec));
        return new CapabilitySnapshot(result);
    }

    private TerrainSnapshot restrictedTerrain(Map<ContentId, Set<TileKey>> allowed, int maxX) {
        Map<TileKey, TileState> tiles = new LinkedHashMap<>();
        for (int x = 0; x <= maxX; x++) tiles.put(new TileKey(x, 0), TileState.empty());
        Map<ContentId, Map<TileKey, Integer>> masks = new LinkedHashMap<>();
        allowed.forEach((id, anchors) -> {
            Map<TileKey, Integer> rotations = new LinkedHashMap<>();
            for (TileKey anchor : anchors) rotations.put(anchor, 15);
            masks.put(id, rotations);
        });
        masks.put(ContentId.of("mod:drill"), Map.of(new TileKey(0, 0), 15));
        return TerrainSnapshot.of(autodrillnext.world.WorldSnapshot.captured(tiles, TerrainRevision.of(1),
            new autodrillnext.world.PlacementRules(masks, Map.of())));
    }

    private DrillCandidate drill(String id, int x, int y, float production) {
        return new DrillCandidate(
            id,
            ContentId.of("mod:drill"),
            new TileKey(x, y),
            0,
            PlacementFootprint.of(Set.of(new TileKey(x, y))),
            Set.of(new TileKey(x, y)),
            new ProductionEstimate(ContentId.of("mindustry:copper"), production),
            List.of(),
            CostVector.empty(),
            Set.of()
        );
    }

    private TerrainSnapshot openTerrain(int minX, int minY, int maxX, int maxY) {
        LinkedHashMap<TileKey, TileState> tiles = new LinkedHashMap<>();
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                tiles.put(new TileKey(x, y), TileState.empty());
            }
        }
        return TerrainSnapshot.of(tiles, TerrainRevision.of(1));
    }

    private CapabilitySnapshot capabilities(float capacity, boolean bridge) {
        ContentId id = ContentId.of(bridge ? "mod:bridge" : "mod:conveyor");
        ItemTransportSpec spec = new ItemTransportSpec(id, 1, capacity, bridge ? 3 : 0, bridge, CostVector.empty());
        CapabilityDescriptor descriptor = new CapabilityDescriptor(
            id,
            CapabilityKind.ITEM_TRANSPORT,
            EnumSet.of(
                CapabilityState.DISCOVERED,
                CapabilityState.SUPPORTED,
                CapabilityState.AVAILABLE_NOW,
                CapabilityState.AFFORDABLE_NOW
            ),
            Set.of(),
            spec.cost(),
            "test",
            spec
        );
        return new CapabilitySnapshot(Map.of(id, descriptor));
    }
}
