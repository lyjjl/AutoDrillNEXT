package autodrillnext.compile;

import autodrillnext.model.PlannerDiagnostic;

import java.util.List;

public record ValidationResult(boolean valid, List<PlannerDiagnostic> diagnostics) {
    public ValidationResult {
        diagnostics = List.copyOf(diagnostics);
        if (valid && !diagnostics.isEmpty()) throw new IllegalArgumentException("valid result cannot carry diagnostics");
        if (!valid && diagnostics.isEmpty()) throw new IllegalArgumentException("invalid result requires diagnostics");
    }
}
