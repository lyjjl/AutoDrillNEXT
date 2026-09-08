package autodrillnext.solver;

import autodrillnext.model.DrillCandidate;
import autodrillnext.model.MiningLayout;
import autodrillnext.model.PatchSearchScope;
import autodrillnext.model.PlanObjectiveOrder;
import autodrillnext.model.SearchBudget;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Tiny-domain correctness oracle. It deliberately does not share search bounds or expansion logic. */
final class PatchSearchOracle {
    Optional<EvaluatedMiningPlan> solve(
        PatchSearchScope scope,
        List<DrillCandidate> drills,
        TransportPlacementDomain domain,
        PlanCandidateEvaluator evaluator
    ) {
        if (drills.size() > 6 || domain.size() > 16) {
            throw new IllegalArgumentException("oracle fixture exceeds exhaustive limits");
        }
        PlanObjectiveOrder order = PlanObjectiveOrder.forProfile(scope.request().profile());
        EvaluatedMiningPlan best = null;
        long drillCombinations = 1L << drills.size();
        long transportCombinations = 1L << domain.size();
        for (long drillMask = 1; drillMask < drillCombinations; drillMask++) {
            MiningLayout layout = legalLayout(drills, drillMask);
            if (layout == null) continue;
            for (long placementMask = 0; placementMask < transportCombinations; placementMask++) {
                var graph = domain.exactGraph(layout, bits(placementMask));
                if (graph.isEmpty()) continue;
                for (EvaluatedMiningPlan candidate : evaluator.evaluate(
                    layout, graph.orElseThrow(), scope, SearchBudget.unlimited()).plans()) {
                    if (best == null || order.compare(candidate.objective(), best.objective()) > 0) {
                        best = candidate;
                    }
                }
            }
        }
        return Optional.ofNullable(best);
    }

    private MiningLayout legalLayout(List<DrillCandidate> drills, long mask) {
        ArrayList<DrillCandidate> selected = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        Set<autodrillnext.world.TileKey> occupied = new HashSet<>();
        for (int index = 0; index < drills.size(); index++) {
            if ((mask & 1L << index) == 0) continue;
            DrillCandidate candidate = drills.get(index);
            if (!java.util.Collections.disjoint(ids, candidate.conflictIds())
                || candidate.footprint().tiles().stream().anyMatch(occupied::contains)) {
                return null;
            }
            selected.add(candidate);
            ids.add(candidate.id());
            occupied.addAll(candidate.footprint().tiles());
        }
        for (DrillCandidate candidate : selected) {
            if (candidate.conflictIds().stream().anyMatch(ids::contains)) return null;
        }
        return MiningLayout.of(selected);
    }

    private BitSet bits(long mask) {
        return BitSet.valueOf(new long[]{mask});
    }
}
