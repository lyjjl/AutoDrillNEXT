package autodrillnext.solver;

import autodrillnext.model.PlannerDiagnostic;
import autodrillnext.world.ExitAnchor;

import java.util.List;
import java.util.Objects;

public record ExitResolution(ExitAnchor anchor, List<PlannerDiagnostic> diagnostics) {
    public ExitResolution {
        diagnostics = List.copyOf(diagnostics);
        if (anchor != null && !diagnostics.isEmpty()) {
            throw new IllegalArgumentException("resolved exit cannot carry diagnostics");
        }
        if (anchor == null && diagnostics.isEmpty()) {
            throw new IllegalArgumentException("unresolved exit requires diagnostics");
        }
    }

    public static ExitResolution resolved(ExitAnchor anchor) {
        return new ExitResolution(Objects.requireNonNull(anchor, "anchor"), List.of());
    }

    public static ExitResolution unresolved(PlannerDiagnostic diagnostic) {
        return new ExitResolution(null, List.of(Objects.requireNonNull(diagnostic, "diagnostic")));
    }

    public boolean resolved() {
        return anchor != null;
    }

    public boolean unresolved() {
        return anchor == null;
    }
}
