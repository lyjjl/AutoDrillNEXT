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
import autodrillnext.model.MiningLayout;
import autodrillnext.model.PatchSearchScope;
import autodrillnext.model.PlannerRequest;
import autodrillnext.model.ProductionEstimate;
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

import java.util.ArrayList;
import java.util.BitSet;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TransportPlacementDomainTest {
    @Test
    void capturesGroundOutputsAndEveryLegalBridgePairInCanonicalOrder() {
        TransportPlacementDomain domain = TransportPlacementDomain.capture(openScope(5, 3, true));
        List<String> ids = IntStream.range(0, domain.size())
            .mapToObj(index -> domain.configuration(index).canonicalId())
            .toList();

        assertTrue(ids.contains("belt@1,1/r0->2,1"));
        assertTrue(ids.contains("bridge@0,1/r0=>3,1/r0->4,1"));
        assertEquals(ids.stream().sorted().toList(), ids);
    }

    @Test
    void excludesTilesFarOutsideThePatchAndExitNeighborhood() {
        PatchSearchScope scope = openScope(80, 3, true);
        TileKey far = new TileKey(79, 1);

        assertFalse(scope.routingTerrain().contains(far));
        TransportPlacementDomain domain = TransportPlacementDomain.capture(scope);

        assertTrue(IntStream.range(0, domain.size())
            .mapToObj(domain::configuration)
            .flatMap(configuration -> configuration.placements().stream())
            .noneMatch(placement -> placement.tile().equals(far)));
    }

    @Test
    void relaxedFlowNeverFallsBelowAnyCompatibleConcreteCompletion() {
        PatchSearchScope scope = openScope(2, 1, false);
        TransportPlacementDomain domain = TransportPlacementDomain.capture(scope);
        MiningLayout layout = MiningLayout.of(List.of(drill(new TileKey(0, 0))));
        assertTrue(domain.size() <= 12, "fixture must remain exhaustively enumerable");

        int combinations = 1 << domain.size();
        List<ConcreteFlow> concrete = new ArrayList<>();
        for (int mask = 0; mask < combinations; mask++) {
            Optional<autodrillnext.model.PlanGraph> graph = domain.exactGraph(layout, bits(mask));
            if (graph.isPresent()) {
                concrete.add(new ConcreteFlow(
                    mask,
                    new FlowSolver().assign(graph.orElseThrow()).qOut()
                ));
            }
        }

        for (int disabledMask = 0; disabledMask < combinations; disabledMask++) {
            float upper = new FlowSolver().assign(
                domain.relaxedGraph(layout.candidates(), bits(disabledMask))
            ).qOut();
            for (ConcreteFlow completion : concrete) {
                if ((completion.mask() & disabledMask) == 0) {
                    assertTrue(upper + 0.0001f >= completion.qout(),
                        "disabled=" + disabledMask + ", enabled=" + completion.mask());
                }
            }
        }
    }

    private PatchSearchScope openScope(int width, int height, boolean includeBridge) {
        TileKey origin = new TileKey(0, 0);
        OrePatch patch = OrePatch.of(ContentId.of("copper"), origin, Set.of(new TileOffset(0, 0)));
        ExitPort exitPort = ExitPort.forPreference(ExitPort.Side.RIGHT, ExitPort.Bias.CENTER, patch);
        ExitAnchor exit = exitPort.anchors().get(0);
        LinkedHashMap<TileKey, TileState> tiles = new LinkedHashMap<>();
        for (int x = 0; x < width; x++) {
            for (int y = 0; y < height; y++) tiles.put(new TileKey(x, y), TileState.empty());
        }
        ContentId belt = ContentId.of("belt");
        LinkedHashMap<ContentId, CapabilityDescriptor> descriptors = new LinkedHashMap<>();
        descriptors.put(belt, descriptor(new ItemTransportSpec(
            belt, 1, 10f, 0, false, CostVector.empty(), SimulationFidelity.EXACT_SHADOW,
            ItemTransportSpec.PortBehavior.CONVEYOR
        )));
        if (includeBridge) {
            ContentId bridge = ContentId.of("bridge");
            descriptors.put(bridge, descriptor(new ItemTransportSpec(
                bridge, 1, 10f, 3, true, CostVector.empty(), SimulationFidelity.EXACT_SHADOW,
                ItemTransportSpec.PortBehavior.ITEM_BRIDGE
            )));
        }
        WorldSnapshot world = WorldSnapshot.of(tiles, TerrainRevision.of(1));
        return PatchSearchScope.capture(
            world,
            patch,
            new CapabilitySnapshot(descriptors),
            Inventory.empty(),
            ExistingNetwork.empty(),
            exit,
            PlannerRequest.defaults(origin, "blue", exitPort),
            null,
            null,
            SimulationFidelity.EXACT_SHADOW,
            true
        );
    }

    private CapabilityDescriptor descriptor(ItemTransportSpec spec) {
        return new CapabilityDescriptor(
            spec.id(),
            CapabilityKind.ITEM_TRANSPORT,
            EnumSet.of(CapabilityState.DISCOVERED, CapabilityState.SUPPORTED, CapabilityState.AVAILABLE_NOW),
            Set.of(),
            spec.cost(),
            "test",
            spec
        );
    }

    private DrillCandidate drill(TileKey anchor) {
        return new DrillCandidate(
            "drill@" + anchor.x() + "," + anchor.y(),
            ContentId.of("drill"),
            anchor,
            0,
            PlacementFootprint.of(Set.of(anchor)),
            Set.of(anchor),
            new ProductionEstimate(ContentId.of("copper"), 5f),
            List.of(),
            CostVector.empty(),
            Set.of()
        );
    }

    private BitSet bits(int mask) {
        return BitSet.valueOf(new long[]{Integer.toUnsignedLong(mask)});
    }

    private record ConcreteFlow(int mask, float qout) {}
}
