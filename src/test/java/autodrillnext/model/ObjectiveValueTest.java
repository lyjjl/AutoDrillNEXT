package autodrillnext.model;

import autodrillnext.capability.spec.CostVector;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ObjectiveValueTest {
    @Test
    void profilesUseOneExplicitObjectiveOrder() {
        ObjectiveValue throughput = value(8f, 3, 20, 6, 6, "throughput");
        ObjectiveValue cheap = value(4f, 3, 2, 8, 8, "cheap");
        ObjectiveValue simple = value(3f, 2, 5, 4, 1, "simple");

        assertTrue(PlanObjectiveOrder.forProfile(PlannerRequest.Profile.THROUGHPUT)
            .compare(throughput, cheap) > 0);
        assertTrue(PlanObjectiveOrder.forProfile(PlannerRequest.Profile.LOW_COST)
            .compare(cheap, throughput) > 0);
        assertTrue(PlanObjectiveOrder.forProfile(PlannerRequest.Profile.LOW_COMPLEXITY)
            .compare(simple, cheap) > 0);
    }

    @Test
    void throughputOrderUsesStableOutputBucketsAndCanonicalTieBreak() {
        PlanObjectiveOrder order = PlanObjectiveOrder.forProfile(PlannerRequest.Profile.BALANCED);
        ObjectiveValue basic = value(9f, 2, 5, 1, 1, "basic");
        ObjectiveValue rare = new ObjectiveValue(9.00001f, 2,
            CostVector.of(Map.of(ItemId.of("plastanium"), 1)), 1, 1, "rare");
        ObjectiveValue cheaper = value(9.00002f, 2, 4, 1, 1, "cheaper");
        ObjectiveValue higherOutput = value(9.001f, 2, 100, 1, 1, "higher-output");

        assertTrue(order.compare(basic, rare) > 0);
        assertTrue(order.compare(cheaper, basic) > 0);
        assertTrue(order.compare(higherOutput, cheaper) > 0);

        ArrayList<ObjectiveValue> values = new ArrayList<>(List.of(basic, higherOutput, cheaper));
        values.sort(order.bestFirst());
        assertEquals(List.of(higherOutput, cheaper, basic), values);
    }

    private ObjectiveValue value(
        float qout,
        int coverage,
        int copper,
        int space,
        int complexity,
        String id
    ) {
        return new ObjectiveValue(
            qout,
            coverage,
            CostVector.of(Map.of(ItemId.of("copper"), copper)),
            space,
            complexity,
            id
        );
    }
}
