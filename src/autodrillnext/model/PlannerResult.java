package autodrillnext.model;

import autodrillnext.capability.spec.CapabilitySnapshot;
import autodrillnext.compile.BuildPlanCompiler;
import autodrillnext.compile.ValidationResult;
import autodrillnext.solver.BudgetSelection;
import autodrillnext.solver.ExitResolution;
import autodrillnext.world.OrePatch;
import autodrillnext.simulation.SimulationResult;

import java.util.List;
import java.util.Objects;

public record PlannerResult(
    boolean compileReady,
    OrePatch patch,
    ExitResolution exit,
    CapabilitySnapshot capabilities,
    MiningLayout layout,
    PlanGraph graph,
    SupportPlanView support,
    List<ServiceBundle> frontier,
    BudgetSelection budget,
    UpgradePlan upgrade,
    ValidationResult validation,
    List<BuildPlanCompiler.CompileRecord> compileRecords,
    List<PlannerDiagnostic> diagnostics,
    SimulationResult itemSimulation,
    OptimalityCertificate certificate
) {
    public PlannerResult {
        Objects.requireNonNull(diagnostics, "diagnostics");
        frontier = List.copyOf(frontier == null ? List.of() : frontier);
        compileRecords = List.copyOf(compileRecords == null ? List.of() : compileRecords);
        Objects.requireNonNull(certificate, "certificate");
        diagnostics = List.copyOf(diagnostics);
    }

    public boolean hasSolution() {
        return graph != null && layout != null && budget != null && budget.selected() != null;
    }

    public record SupportPlanView(
        boolean feasible,
        float finalQout,
        String variantId,
        List<PowerDemand> powerDemand,
        List<LiquidDemand> liquidDemands,
        java.util.Set<DependencyKind> dependencies
    ) {
        public SupportPlanView {
            if (!Float.isFinite(finalQout) || finalQout < 0f) throw new IllegalArgumentException("invalid support qout");
            Objects.requireNonNull(variantId, "variant id");
            powerDemand = List.copyOf(powerDemand);
            liquidDemands = List.copyOf(liquidDemands);
            dependencies = java.util.Set.copyOf(dependencies);
        }

        public static SupportPlanView of(autodrillnext.solver.SupportPlan plan) {
            return new SupportPlanView(
                plan.feasible(), plan.finalQout(), plan.variantId(),
                plan.powerDemand() == null ? List.of() : List.of(plan.powerDemand()),
                plan.liquidDemands(), plan.dependencies()
            );
        }
    }
}
