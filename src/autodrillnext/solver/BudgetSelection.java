package autodrillnext.solver;

import autodrillnext.model.PlannerDiagnostic;
import autodrillnext.model.ServiceBundle;

import java.util.List;

public record BudgetSelection(ServiceBundle selected, List<PlannerDiagnostic> diagnostics) {
    public BudgetSelection {
        diagnostics = List.copyOf(diagnostics);
        if (selected != null && !diagnostics.isEmpty()) throw new IllegalArgumentException("selection cannot have diagnostics");
        if (selected == null && diagnostics.isEmpty()) throw new IllegalArgumentException("missing selection needs diagnostics");
    }

    public boolean hasSelection() {
        return selected != null;
    }
}
