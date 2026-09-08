package autodrillnext.solver;

import autodrillnext.capability.spec.CostVector;
import autodrillnext.model.ItemId;
import autodrillnext.model.ServiceBundle;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

public final class ParetoPlanner {
    public List<ServiceBundle> frontier(List<ServiceBundle> bundles) {
        ArrayList<ServiceBundle> ordered = new ArrayList<>(bundles);
        ordered.sort(Comparator.comparing(ServiceBundle::id));
        ArrayList<ServiceBundle> frontier = new ArrayList<>();
        for (ServiceBundle candidate : ordered) {
            if (!candidate.isDependencyClosed()) continue;
            boolean dominated = false;
            for (ServiceBundle other : ordered) {
                if (!other.isDependencyClosed() || candidate == other) continue;
                if (dominates(other, candidate)) {
                    dominated = true;
                    break;
                }
            }
            if (!dominated) frontier.add(candidate);
        }
        return List.copyOf(frontier);
    }

    private boolean dominates(ServiceBundle first, ServiceBundle second) {
        boolean qoutAtLeast = first.qout() >= second.qout();
        boolean costAtMost = componentWiseAtMost(first.cost().materials(), second.cost().materials());
        boolean spaceAtMost = first.cost().space() <= second.cost().space();
        boolean complexityAtMost = first.cost().complexity() <= second.cost().complexity();
        boolean strict = first.qout() > second.qout()
            || !componentWiseAtMost(second.cost().materials(), first.cost().materials())
            || first.cost().space() < second.cost().space()
            || first.cost().complexity() < second.cost().complexity();
        return qoutAtLeast && costAtMost && spaceAtMost && complexityAtMost && strict;
    }

    private boolean componentWiseAtMost(CostVector first, CostVector second) {
        for (Map.Entry<ItemId, Integer> entry : first.amounts().entrySet()) {
            if (entry.getValue() > second.amount(entry.getKey())) return false;
        }
        return true;
    }
}
