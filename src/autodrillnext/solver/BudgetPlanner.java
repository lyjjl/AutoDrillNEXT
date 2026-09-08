package autodrillnext.solver;

import autodrillnext.model.BudgetSnapshot;
import autodrillnext.model.DiagnosticCode;
import autodrillnext.model.PlannerDiagnostic;
import autodrillnext.model.ServiceBundle;

import java.util.List;

public final class BudgetPlanner {
    public BudgetSelection select(ServiceBundle candidate, BudgetSnapshot budget) {
        if (candidate == null
            || candidate.qout() <= 0f
            || !candidate.isDependencyClosed()
            || !budget.infiniteResources()
                && !candidate.cost().materials().componentWiseAtMost(budget.inventory())) {
            return new BudgetSelection(
                null,
                List.of(PlannerDiagnostic.of(DiagnosticCode.BUDGET_DEFICIT))
            );
        }
        return new BudgetSelection(candidate, List.of());
    }
}
