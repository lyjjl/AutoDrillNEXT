package autodrillnext.ui;

import arc.Core;
import arc.scene.ui.layout.Table;
import autodrillnext.model.DiagnosticCode;
import autodrillnext.model.PlannerDiagnostic;

import java.util.List;
import java.util.Objects;

public final class PlannerDiagnostics {
    public String bundleKey(DiagnosticCode code) {
        Objects.requireNonNull(code, "diagnostic code");
        return switch (code) {
            case UNSUPPORTED_CONTENT -> "auto-drill-next.diagnostic.unsupported-content";
            case NO_UNLOCKED_CONTENT -> "auto-drill-next.diagnostic.no-unlocked-content";
            case TERRAIN_OR_RULE_BLOCKED -> "auto-drill-next.diagnostic.terrain-or-rule-blocked";
            case BUDGET_DEFICIT -> "auto-drill-next.diagnostic.budget-deficit";
            case SAFETY_POLICY_DENIED -> "auto-drill-next.diagnostic.safety-policy-denied";
            case NO_VALID_SINK -> "auto-drill-next.diagnostic.no-valid-sink";
            case ORE_NOT_FOUND -> "auto-drill-next.diagnostic.ore-not-found";
            case NO_DRILL_CANDIDATE -> "auto-drill-next.diagnostic.no-drill-candidate";
            case NO_UNLOCKED_TRANSPORT -> "auto-drill-next.diagnostic.no-unlocked-transport";
            case NO_ROUTE -> "auto-drill-next.diagnostic.no-route";
            case NO_POWER_SOURCE -> "auto-drill-next.diagnostic.no-power-source";
            case NO_LIQUID_SOURCE -> "auto-drill-next.diagnostic.no-liquid-source";
            case NO_POWER_SUPPORT -> "auto-drill-next.diagnostic.no-power-support";
            case NO_LIQUID_SUPPORT -> "auto-drill-next.diagnostic.no-liquid-support";
            case DEPENDENCY_OVER_BUDGET -> "auto-drill-next.diagnostic.dependency-over-budget";
            case EXISTING_NETWORK_BLOCKED -> "auto-drill-next.diagnostic.existing-network-blocked";
            case STALE_SNAPSHOT -> "auto-drill-next.diagnostic.stale-snapshot";
            case PARTIAL_PLAN -> "auto-drill-next.diagnostic.partial-plan";
            case SEARCH_LIMIT_REACHED -> "auto-drill-next.diagnostic.search-limit-reached";
            case NO_FEASIBLE_PLAN -> "auto-drill-next.diagnostic.no-feasible-plan";
        };
    }

    public PlannerDiagnostic primaryFailure(List<PlannerDiagnostic> diagnostics) {
        for (PlannerDiagnostic diagnostic : diagnostics) {
            if (diagnostic.code() != DiagnosticCode.SEARCH_LIMIT_REACHED
                && diagnostic.code() != DiagnosticCode.PARTIAL_PLAN) return diagnostic;
        }
        return diagnostics.isEmpty() ? null : diagnostics.get(0);
    }

    public String resolve(PlannerDiagnostic diagnostic) {
        if (diagnostic.code() == DiagnosticCode.NO_VALID_SINK && diagnostic.arguments().containsKey("anchors")) {
            return Core.bundle.format(
                "auto-drill-next.diagnostic.no-valid-sink-detail",
                diagnostic.arguments().get("anchors"),
                diagnostic.arguments().getOrDefault("snapshot", "unknown")
            );
        }
        String message = Core.bundle.get(bundleKey(diagnostic.code()));
        String detail = diagnostic.arguments().get("detail");
        return detail == null || detail.isBlank() ? message
            : Core.bundle.format("auto-drill-next.diagnostic.with-detail", message, detail);
    }

    public void render(Table table, List<PlannerDiagnostic> diagnostics) {
        Objects.requireNonNull(table, "diagnostic table");
        for (PlannerDiagnostic diagnostic : diagnostics) {
            table.label(() -> resolve(diagnostic)).left().growX().row();
        }
    }
}
