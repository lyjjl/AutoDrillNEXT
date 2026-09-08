package autodrillnext.solver;

import autodrillnext.capability.spec.CostVector;
import autodrillnext.model.BudgetSnapshot;
import autodrillnext.model.DiagnosticCode;
import autodrillnext.model.Inventory;
import autodrillnext.model.ItemId;
import autodrillnext.model.PlanCost;
import autodrillnext.model.ServiceBundle;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BudgetPlannerTest {
    @Test
    void componentWiseBudgetRejectsOneMissingResourceEvenWhenTotalIsEnough() {
        ServiceBundle graphitePlan = bundle("graphite", 8f, Map.of(
            ItemId.of("copper"), 1,
            ItemId.of("graphite"), 5
        ));
        BudgetSnapshot budget = new BudgetSnapshot(Inventory.of(Map.of(
            ItemId.of("copper"), 100,
            ItemId.of("graphite"), 4
        )), false);

        BudgetSelection selection = new BudgetPlanner().select(graphitePlan, budget);

        assertFalse(selection.hasSelection());
        assertEquals(DiagnosticCode.BUDGET_DEFICIT, selection.diagnostics().get(0).code());
    }

    @Test
    void acceptsOnePositiveDependencyClosedAffordableBundle() {
        ServiceBundle candidate = bundle("candidate", 9f, Map.of(ItemId.of("copper"), 10));
        BudgetSnapshot budget = new BudgetSnapshot(
            Inventory.of(Map.of(ItemId.of("copper"), 10)),
            false
        );

        BudgetSelection selection = new BudgetPlanner().select(candidate, budget);

        assertTrue(selection.hasSelection());
        assertEquals(candidate, selection.selected());
    }

    private ServiceBundle bundle(String id, float qout, Map<ItemId, Integer> cost) {
        return ServiceBundle.of(
            id,
            qout,
            new PlanCost(CostVector.of(cost), 1, 1),
            Set.of("drill"),
            Set.of("drill"),
            Map.of(),
            Set.of(),
            Set.of()
        );
    }
}
