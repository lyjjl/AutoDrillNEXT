package autodrillnext.capability;

import autodrillnext.capability.spec.CapabilityCandidate;
import autodrillnext.capability.spec.CapabilityDescriptor;
import autodrillnext.capability.spec.CapabilityKind;
import autodrillnext.capability.spec.CapabilityState;
import autodrillnext.capability.spec.CapabilitySnapshot;
import autodrillnext.capability.spec.CostVector;
import autodrillnext.model.ContentId;
import autodrillnext.model.Inventory;
import autodrillnext.model.ItemId;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CapabilityRegistryTest {
    @Test
    void unknownContentIsDiscoveredButNeverSelected() {
        CapabilityCandidate unknown = new CapabilityCandidate(
            ContentId.of("example:quantum-teleporter"),
            CapabilityKind.UNKNOWN,
            false,
            true,
            true,
            false,
            CostVector.empty()
        );

        CapabilitySnapshot snapshot = new CapabilityRegistry().evaluate(
            List.of(unknown), Inventory.empty(), false
        );

        var descriptor = snapshot.require(unknown.id());
        assertTrue(descriptor.states().contains(CapabilityState.DISCOVERED));
        assertFalse(descriptor.states().contains(CapabilityState.SUPPORTED));
        assertFalse(descriptor.selectable());
    }

    @Test
    void knownSubclassCanBeAvailableWithoutBeingAffordable() {
        CapabilityCandidate subclass = new CapabilityCandidate(
            ContentId.of("mod:fast-conveyor"),
            CapabilityKind.ITEM_TRANSPORT,
            true,
            true,
            true,
            false,
            CostVector.of(Map.of(ItemId.of("titanium"), 20))
        );

        var descriptor = new CapabilityRegistry().evaluate(
            List.of(subclass), Inventory.of(Map.of(ItemId.of("titanium"), 3)), false
        ).require(subclass.id());

        assertTrue(descriptor.states().contains(CapabilityState.AVAILABLE_NOW));
        assertFalse(descriptor.states().contains(CapabilityState.AFFORDABLE_NOW));
        assertFalse(descriptor.selectable());
    }

    @Test
    void deniedCapabilityRemainsDescribedButCannotBeSelected() {
        ContentId deniedId = ContentId.of("mod:unsafe-bridge");
        CapabilityCandidate denied = new CapabilityCandidate(
            deniedId,
            CapabilityKind.ITEM_TRANSPORT,
            true,
            true,
            true,
            false,
            CostVector.empty()
        );

        CapabilityDescriptor descriptor = new CapabilityRegistry().evaluate(
            List.of(denied),
            Inventory.empty(),
            false,
            SafetyPolicy.builder().deny(deniedId).build()
        ).require(deniedId);

        assertTrue(descriptor.states().contains(CapabilityState.SUPPORTED));
        assertFalse(descriptor.states().contains(CapabilityState.AVAILABLE_NOW));
        assertFalse(descriptor.selectable());
        assertEquals("SAFETY_POLICY_DENIED", descriptor.reasons().iterator().next());
    }
}
