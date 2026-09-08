package autodrillnext.model;

import autodrillnext.capability.spec.CostVector;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DependencyClosureTest {
    @Test
    void halfBridgeAndMissingMandatorySupportAreNotClosedBundles() {
        ServiceBundle halfBridge = ServiceBundle.of(
            "half-bridge", 5f, PlanCost.empty(),
            Set.of("drill"), Set.of("drill"),
            Map.of("bridge", Set.of("endpoint-a")),
            Set.of(), Set.of()
        );
        ServiceBundle missingSupport = ServiceBundle.of(
            "missing-support", 5f, PlanCost.empty(),
            Set.of("drill"), Set.of("drill"),
            Map.of(), Set.of("power"), Set.of()
        );

        assertFalse(halfBridge.isDependencyClosed());
        assertFalse(missingSupport.isDependencyClosed());
    }

    @Test
    void completeBundleRequiresOutputForEveryDrillAndBothBridgeEndpoints() {
        ServiceBundle complete = ServiceBundle.of(
            "complete", 5f, PlanCost.empty(),
            Set.of("drill"), Set.of("drill"),
            Map.of("bridge", Set.of("endpoint-a", "endpoint-b")),
            Set.of("power"), Set.of("power")
        );
        ServiceBundle orphanDrill = ServiceBundle.of(
            "orphan-drill", 5f, PlanCost.empty(),
            Set.of("drill"), Set.of(), Map.of(), Set.of(), Set.of()
        );

        assertTrue(complete.isDependencyClosed());
        assertFalse(orphanDrill.isDependencyClosed());
    }
}
