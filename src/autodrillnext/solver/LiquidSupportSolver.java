package autodrillnext.solver;

import autodrillnext.capability.spec.CapabilitySnapshot;
import autodrillnext.model.DependencyKind;
import autodrillnext.model.DiagnosticCode;
import autodrillnext.model.ExistingNetwork;
import autodrillnext.model.LiquidDemand;
import autodrillnext.model.LiquidId;
import autodrillnext.model.ObjectiveValue;
import autodrillnext.model.PlannerDiagnostic;
import autodrillnext.model.SearchBudget;
import autodrillnext.model.PowerDemand;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;

public final class LiquidSupportSolver {
    public SupportPlan solve(
        List<LiquidDemand> demands,
        ExistingNetwork network,
        CapabilitySnapshot capabilities,
        float margin
    ) {
        return solve(demands, network, capabilities, margin, SearchBudget.unlimited());
    }

    public SupportPlan solve(
        List<LiquidDemand> demands,
        ExistingNetwork network,
        CapabilitySnapshot capabilities,
        float margin,
        SearchBudget budget
    ) {
        budget.checkpoint();
        if (!Float.isFinite(margin) || margin < 1f) {
            throw new IllegalArgumentException("liquid margin must be at least one");
        }
        if (demands.isEmpty()) {
            return SupportPlan.valid(
                0f,
                "liquid-none",
                new PowerDemand(0f, false),
                List.of(),
                Set.of()
            );
        }

        ArrayList<LiquidDemand> ordered = new ArrayList<>(demands);
        ordered.sort((first, second) -> {
            int byChoices = Integer.compare(first.allowedLiquids().size(), second.allowedLiquids().size());
            if (byChoices != 0) return byChoices;
            int byAmount = Float.compare(second.perSecond(), first.perSecond());
            if (byAmount != 0) return byAmount;
            return liquidKey(first).compareTo(liquidKey(second));
        });
        LinkedHashMap<LiquidId, Float> remaining = new LinkedHashMap<>();
        network.liquidSupplyPerSecond().entrySet().stream()
            .sorted(java.util.Map.Entry.comparingByKey())
            .forEach(entry -> remaining.put(entry.getKey(), entry.getValue()));

        if (!assign(0, ordered, remaining, margin, new HashSet<>(), budget)) {
            return SupportPlan.invalid(
                0f,
                "liquid-existing",
                new PowerDemand(0f, false),
                demands,
                Set.of(),
                PlannerDiagnostic.of(DiagnosticCode.NO_LIQUID_SOURCE)
            );
        }
        return SupportPlan.valid(
            0f,
            "liquid-existing",
            new PowerDemand(0f, false),
            demands,
            Set.of(DependencyKind.LIQUID_SUPPORT)
        );
    }

    private boolean assign(
        int index,
        List<LiquidDemand> demands,
        LinkedHashMap<LiquidId, Float> remaining,
        float margin,
        Set<String> failed,
        SearchBudget budget
    ) {
        budget.checkpoint();
        if (index == demands.size()) return true;
        String state = stateKey(index, remaining);
        if (!failed.add(state)) return false;

        LiquidDemand demand = demands.get(index);
        float required = demand.perSecond() * margin;
        ArrayList<LiquidId> choices = new ArrayList<>(demand.allowedLiquids());
        choices.sort(LiquidId::compareTo);
        for (LiquidId liquid : choices) {
            float available = remaining.getOrDefault(liquid, 0f);
            if (available + 0.0001f < required) continue;
            remaining.put(liquid, available - required);
            if (assign(index + 1, demands, remaining, margin, failed, budget)) return true;
            remaining.put(liquid, available);
        }
        return false;
    }

    private String stateKey(int index, LinkedHashMap<LiquidId, Float> remaining) {
        StringBuilder key = new StringBuilder().append(index);
        remaining.forEach((liquid, amount) -> key.append('|').append(liquid.value()).append('=')
            .append(ObjectiveValue.outputRank(amount)));
        return key.toString();
    }

    private static String liquidKey(LiquidDemand demand) {
        return demand.allowedLiquids().stream()
            .map(LiquidId::value)
            .sorted()
            .collect(java.util.stream.Collectors.joining("|"));
    }
}
