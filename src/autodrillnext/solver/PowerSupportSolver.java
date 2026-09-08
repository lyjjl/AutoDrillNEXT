package autodrillnext.solver;

import autodrillnext.capability.spec.CapabilitySnapshot;
import autodrillnext.capability.spec.PowerConnectorSpec;
import autodrillnext.model.DependencyKind;
import autodrillnext.model.DiagnosticCode;
import autodrillnext.model.ExistingNetwork;
import autodrillnext.model.PlannerDiagnostic;
import autodrillnext.model.PowerDemand;
import autodrillnext.world.TileKey;

import java.util.List;
import java.util.Set;

public final class PowerSupportSolver {
    private static final float DEFAULT_MARGIN = 1.10f;
    public SupportPlan solve(
        List<PowerDemand> demands,
        ExistingNetwork network,
        CapabilitySnapshot capabilities
    ) {
        float total = 0f;
        boolean mandatory = false;
        for (PowerDemand demand : demands) {
            total += demand.perSecond();
            mandatory |= demand.mandatory();
        }
        PowerDemand aggregate = new PowerDemand(total, mandatory);
        if (total <= 0f) return SupportPlan.valid(0f, "power-none", aggregate, List.of(), Set.of());
        if (network.powerSupplyPerSecond() + 0.0001f < total * DEFAULT_MARGIN) {
            return SupportPlan.invalid(
                0f,
                "power-existing",
                aggregate,
                List.of(),
                Set.of(),
                PlannerDiagnostic.of(DiagnosticCode.NO_POWER_SOURCE)
            );
        }
        return SupportPlan.valid(total, "power-existing", aggregate, List.of(), Set.of(DependencyKind.POWER_LINK));
    }

    public boolean linkValid(PowerConnectorSpec connector, TileKey from, TileKey to) {
        int dx = Math.abs(from.x() - to.x());
        int dy = Math.abs(from.y() - to.y());
        if (connector.beam()) return (dx == 0 || dy == 0) && dx + dy <= connector.range();
        return dx + dy <= connector.range();
    }
}
