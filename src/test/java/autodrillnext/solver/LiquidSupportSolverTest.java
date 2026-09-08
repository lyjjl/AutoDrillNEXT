package autodrillnext.solver;

import autodrillnext.capability.spec.CapabilityDescriptor;
import autodrillnext.capability.spec.CapabilityKind;
import autodrillnext.capability.spec.CapabilitySnapshot;
import autodrillnext.capability.spec.CapabilityState;
import autodrillnext.capability.spec.CostVector;
import autodrillnext.capability.spec.LiquidProviderSpec;
import autodrillnext.model.ContentId;
import autodrillnext.model.DiagnosticCode;
import autodrillnext.model.ExistingNetwork;
import autodrillnext.model.LiquidDemand;
import autodrillnext.model.LiquidId;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LiquidSupportSolverTest {
    @Test
    void fixedLiquidDemandRequiresMarginAndProviderSupply() {
        LiquidId water = LiquidId.of("mindustry:water");
        LiquidDemand demand = new LiquidDemand(List.of(water), 5f, true);

        SupportPlan shortage = new LiquidSupportSolver().solve(
            List.of(demand),
            new ExistingNetwork(Set.of(), Set.of(), 0f, Map.of(water, 5f)),
            new CapabilitySnapshot(Map.of()),
            1.10f
        );
        SupportPlan enough = new LiquidSupportSolver().solve(
            List.of(demand),
            new ExistingNetwork(Set.of(), Set.of(), 0f, Map.of(water, 6f)),
            new CapabilitySnapshot(Map.of()),
            1.10f
        );

        assertFalse(shortage.feasible());
        assertEquals(DiagnosticCode.NO_LIQUID_SOURCE, shortage.diagnostics().get(0).code());
        assertTrue(enough.feasible());
    }

    @Test
    void unlockedPumpWithoutAConnectedSupplyCannotSatisfyDemand() {
        LiquidId water = LiquidId.of("mindustry:water");
        LiquidId oil = LiquidId.of("mindustry:oil");
        ContentId providerId = ContentId.of("mod:water-pump");
        LiquidProviderSpec provider = new LiquidProviderSpec(providerId, 1, water, 4f, CostVector.empty());
        CapabilityDescriptor descriptor = new CapabilityDescriptor(
            providerId,
            CapabilityKind.LIQUID_PROVIDER,
            EnumSet.of(CapabilityState.DISCOVERED, CapabilityState.SUPPORTED, CapabilityState.AVAILABLE_NOW),
            Set.of(),
            provider.cost(),
            "pump",
            provider
        );

        SupportPlan plan = new LiquidSupportSolver().solve(
            List.of(new LiquidDemand(List.of(water, oil), 3f, true)),
            ExistingNetwork.empty(),
            new CapabilitySnapshot(Map.of(providerId, descriptor)),
            1.0f
        );

        assertFalse(plan.feasible());
        assertEquals(DiagnosticCode.NO_LIQUID_SOURCE, plan.diagnostics().get(0).code());
    }
    @Test
    void backtracksWhenEqualChoiceDemandsNeedDifferentSupplies() {
        LiquidId water = LiquidId.of("water");
        LiquidId cryofluid = LiquidId.of("cryofluid");
        LiquidId oil = LiquidId.of("oil");
        ExistingNetwork network = new ExistingNetwork(
            Set.of(),
            Set.of(),
            0f,
            Map.of(water, 1.1f, cryofluid, 1.1f)
        );
        List<LiquidDemand> demands = List.of(
            new LiquidDemand(List.of(water, cryofluid), 1f, true),
            new LiquidDemand(List.of(water, oil), 1f, true)
        );

        SupportPlan plan = new LiquidSupportSolver().solve(
            demands,
            network,
            new CapabilitySnapshot(Map.of()),
            1.10f
        );

        assertTrue(plan.feasible());
    }

}
