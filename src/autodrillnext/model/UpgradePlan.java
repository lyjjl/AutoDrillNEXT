package autodrillnext.model;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public record UpgradePlan(List<UpgradeAction> actions) {
    public UpgradePlan {
        actions = List.copyOf(actions);
    }

    public List<UpgradeAction> sequence() {
        ArrayList<UpgradeAction> ordered = new ArrayList<>(actions);
        ordered.sort(Comparator
            .comparingInt((UpgradeAction action) -> action.kind().ordinal())
            .thenComparing(action -> action.tile()));
        return List.copyOf(ordered);
    }
}
