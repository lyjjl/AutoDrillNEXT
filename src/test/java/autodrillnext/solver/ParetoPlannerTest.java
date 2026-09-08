package autodrillnext.solver;

import autodrillnext.capability.spec.CostVector;
import autodrillnext.model.ItemId;
import autodrillnext.model.PlanCost;
import autodrillnext.model.ServiceBundle;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ParetoPlannerTest {
    @Test
    void dominatedBundleIsRemovedAcrossOutputCostSpaceAndComplexity() {
        ServiceBundle cheap = bundle("cheap", 5f, 2, 1, 1);
        ServiceBundle output = bundle("output", 10f, 4, 3, 2);
        ServiceBundle dominated = bundle("dominated", 4f, 6, 5, 4);

        List<ServiceBundle> frontier = new ParetoPlanner().frontier(List.of(cheap, output, dominated));

        assertEquals(List.of("cheap", "output"), frontier.stream().map(ServiceBundle::id).toList());
        assertTrue(frontier.stream().noneMatch(bundle -> bundle.id().equals("dominated")));
    }

    private ServiceBundle bundle(String id, float qout, int copper, int space, int complexity) {
        return ServiceBundle.of(
            id,
            qout,
            new PlanCost(CostVector.of(Map.of(ItemId.of("copper"), copper)), space, complexity),
            Set.of("drill"), Set.of("drill"), Map.of(), Set.of(), Set.of()
        );
    }
}
