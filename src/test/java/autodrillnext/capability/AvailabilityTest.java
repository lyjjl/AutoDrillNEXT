package autodrillnext.capability;

import autodrillnext.capability.spec.CapabilityCandidate;
import autodrillnext.capability.spec.CapabilityKind;
import autodrillnext.capability.spec.CapabilityState;
import autodrillnext.capability.spec.CapabilitySnapshot;
import autodrillnext.capability.spec.CostVector;
import autodrillnext.model.ContentId;
import autodrillnext.model.Inventory;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AvailabilityTest {
    @Test
    void lockedTransportCannotBeAvailableEvenWhenItIsFree() {
        CapabilityCandidate locked = new CapabilityCandidate(
            ContentId.of("mindustry:locked-bridge"),
            CapabilityKind.ITEM_TRANSPORT,
            true,
            false,
            true,
            false,
            CostVector.empty()
        );

        var descriptor = new CapabilityRegistry().evaluate(
            List.of(locked), Inventory.empty(), false
        ).require(locked.id());

        assertTrue(descriptor.states().contains(CapabilityState.SUPPORTED));
        assertFalse(descriptor.states().contains(CapabilityState.AVAILABLE_NOW));
        assertFalse(descriptor.selectable());
    }

    @Test
    void infiniteResourcesMakeAnAvailableBlockAffordable() {
        CapabilityCandidate freeByRule = new CapabilityCandidate(
            ContentId.of("mindustry:bridge"),
            CapabilityKind.ITEM_TRANSPORT,
            true,
            true,
            true,
            false,
            CostVector.of(java.util.Map.of(autodrillnext.model.ItemId.of("copper"), 999))
        );

        var descriptor = new CapabilityRegistry().evaluate(
            List.of(freeByRule), Inventory.empty(), true
        ).require(freeByRule.id());

        assertTrue(descriptor.states().contains(CapabilityState.AFFORDABLE_NOW));
        assertTrue(descriptor.selectable());
    }
}
