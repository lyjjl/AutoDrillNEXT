package autodrillnext.solver;

import autodrillnext.model.DiagnosticCode;
import autodrillnext.model.ExistingNetwork;
import autodrillnext.model.PlannerDiagnostic;
import autodrillnext.model.PowerDemand;
import autodrillnext.capability.spec.CapabilitySnapshot;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PowerSupportSolverTest {
    @Test
    void mandatoryPowerWithoutReachableSourceIsRejected() {
        SupportPlan plan = new PowerSupportSolver().solve(
            List.of(new PowerDemand(5f, true)),
            ExistingNetwork.empty(),
            new CapabilitySnapshot(Map.of())
        );

        assertFalse(plan.feasible());
        assertEquals(DiagnosticCode.NO_POWER_SOURCE, plan.diagnostics().get(0).code());
    }

    @Test
    void existingPowerMarginSatisfiesAggregateDemandWithoutCreatingProvider() {
        ExistingNetwork network = new ExistingNetwork(Set.of(), Set.of(), 6f, Map.of());

        SupportPlan plan = new PowerSupportSolver().solve(
            List.of(new PowerDemand(5f, true)),
            network,
            new CapabilitySnapshot(Map.of())
        );

        assertTrue(plan.feasible());
        assertTrue(plan.dependencies().contains(autodrillnext.model.DependencyKind.POWER_LINK));
        assertEquals(5f, plan.powerDemand().perSecond());
    }
}
