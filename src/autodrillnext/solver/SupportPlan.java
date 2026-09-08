package autodrillnext.solver;

import autodrillnext.model.DependencyKind;
import autodrillnext.model.LiquidDemand;
import autodrillnext.model.PlannerDiagnostic;
import autodrillnext.model.PowerDemand;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public record SupportPlan(
    boolean feasible,
    float finalQout,
    String variantId,
    PowerDemand powerDemand,
    List<LiquidDemand> liquidDemands,
    Set<DependencyKind> dependencies,
    List<PlannerDiagnostic> diagnostics
) {
    public SupportPlan {
        if (!Float.isFinite(finalQout) || finalQout < 0f) throw new IllegalArgumentException("invalid support qout");
        if (variantId == null || variantId.isBlank()) throw new IllegalArgumentException("variant id must not be blank");
        Objects.requireNonNull(powerDemand, "power demand");
        liquidDemands = List.copyOf(liquidDemands);
        dependencies = immutableCopy(dependencies);
        diagnostics = List.copyOf(diagnostics);
        if (feasible && !diagnostics.isEmpty()) throw new IllegalArgumentException("feasible support cannot have diagnostics");
        if (!feasible && diagnostics.isEmpty()) throw new IllegalArgumentException("infeasible support requires diagnostics");
    }

    public static SupportPlan valid(
        float finalQout,
        String variantId,
        PowerDemand powerDemand,
        List<LiquidDemand> liquidDemands,
        Set<DependencyKind> dependencies
    ) {
        return new SupportPlan(true, finalQout, variantId, powerDemand, liquidDemands, dependencies, List.of());
    }

    public static SupportPlan invalid(
        float finalQout,
        String variantId,
        PowerDemand powerDemand,
        List<LiquidDemand> liquidDemands,
        Set<DependencyKind> dependencies,
        PlannerDiagnostic diagnostic
    ) {
        return new SupportPlan(false, finalQout, variantId, powerDemand, liquidDemands, dependencies, List.of(diagnostic));
    }

    private static Set<DependencyKind> immutableCopy(Set<DependencyKind> values) {
        LinkedHashSet<DependencyKind> copy = new LinkedHashSet<>();
        for (DependencyKind value : values) copy.add(Objects.requireNonNull(value, "dependency"));
        return Collections.unmodifiableSet(copy);
    }
}
