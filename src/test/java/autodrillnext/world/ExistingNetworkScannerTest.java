package autodrillnext.world;

import autodrillnext.capability.spec.CapabilityDescriptor;
import autodrillnext.capability.spec.CapabilityKind;
import autodrillnext.capability.spec.CapabilitySnapshot;
import autodrillnext.capability.spec.CapabilityState;
import autodrillnext.capability.spec.CostVector;
import autodrillnext.capability.spec.ItemTransportSpec;
import autodrillnext.model.ContentId;
import autodrillnext.model.ExistingNetwork;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExistingNetworkScannerTest {
    @Test
    void knownExistingTransportIsReusableButUnknownPlayerBlockRemainsAnObstacle() {
        ContentId conveyor = ContentId.of("mod:conveyor");
        CapabilityDescriptor known = new CapabilityDescriptor(
            conveyor,
            CapabilityKind.ITEM_TRANSPORT,
            EnumSet.of(CapabilityState.DISCOVERED, CapabilityState.SUPPORTED, CapabilityState.AVAILABLE_NOW),
            Set.of(),
            CostVector.empty(),
            "conveyor",
            new ItemTransportSpec(conveyor, 1, 1f, 0, false, CostVector.empty())
        );
        TileKey reusable = new TileKey(1, 0);
        TileKey unknown = new TileKey(2, 0);
        WorldSnapshot world = WorldSnapshot.of(Map.of(
            reusable, new TileState(false, false, false, "mod:conveyor", "sharded", null, null),
            unknown, new TileState(false, false, false, "player:block", "sharded", null, null)
        ));

        ExistingNetwork network = new ExistingNetworkScanner().scan(
            world,
            new CapabilitySnapshot(Map.of(conveyor, known))
        );

        assertTrue(network.traversableTiles().contains(reusable));
        assertTrue(network.occupiedTiles().contains(unknown));
        assertFalse(network.traversableTiles().contains(unknown));
    }
}
