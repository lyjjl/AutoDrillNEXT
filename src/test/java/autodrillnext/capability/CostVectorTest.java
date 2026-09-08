package autodrillnext.capability;

import autodrillnext.capability.spec.CostVector;
import autodrillnext.capability.spec.ResourceValuation;
import autodrillnext.model.Inventory;
import autodrillnext.model.ItemId;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CostVectorTest {
    @Test
    void rulesCostRoundsEachRequirementIndependently() {
        CostVector base = CostVector.of(Map.of(
            ItemId.of("copper"), 3,
            ItemId.of("graphite"), 5
        ));

        CostVector scaled = base.scaled(0.5f);

        assertEquals(2, scaled.amount(ItemId.of("copper")));
        assertEquals(3, scaled.amount(ItemId.of("graphite")));
    }

    @Test
    void affordabilityIsComponentWiseAndDoesNotInventScalarCurrency() {
        CostVector cost = CostVector.of(Map.of(
            ItemId.of("copper"), 20,
            ItemId.of("graphite"), 5
        ));
        Inventory inventory = Inventory.of(Map.of(
            ItemId.of("copper"), 100,
            ItemId.of("graphite"), 4
        ));

        assertFalse(cost.componentWiseAtMost(inventory));
        assertTrue(cost.componentWiseAtMost(Inventory.of(Map.of(
            ItemId.of("copper"), 20,
            ItemId.of("graphite"), 5
        ))));
    }

    @Test
    void capturedWeightsSurviveRoundedScalingAndAdditionWithoutChangingBudgets() {
        ItemId custom = ItemId.of("mod-metal");
        ResourceValuation values = ResourceValuation.of(Map.of(custom, 7L));
        CostVector cost = CostVector.of(Map.of(custom, 3), values).scaled(0.5f)
            .plus(CostVector.of(Map.of(custom, 1), values));
        assertEquals(3, cost.amount(custom));
        assertEquals(3L, cost.total());
        assertEquals(21L, cost.economicValue());
        assertFalse(cost.componentWiseAtMost(Inventory.of(Map.of(custom, 2))));
        assertTrue(cost.componentWiseAtMost(Inventory.of(Map.of(custom, 3))));
        assertEquals(14L, cost.scaled(0.5f).economicValue());
    }

    @Test
    void staleValuationSnapshotsAreNotEqualAndCannotSilentlyMerge() {
        ItemId item = ItemId.of("mod-resource");
        CostVector old = CostVector.of(Map.of(item, 3), ResourceValuation.of(Map.of(item, 7L)));
        CostVector current = CostVector.of(Map.of(item, 3), ResourceValuation.of(Map.of(item, 9L)));
        assertNotEquals(old, current);
        assertThrows(IllegalArgumentException.class, () -> old.plus(current));
        assertEquals(21L, old.economicValue());
        assertEquals(27L, current.economicValue());
        CostVector compatible = CostVector.of(Map.of(item, 2), ResourceValuation.of(Map.of(item, 7L)));
        assertEquals(35L, old.plus(compatible).economicValue());
    }
}
