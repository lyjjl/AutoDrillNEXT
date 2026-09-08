package autodrillnext.solver;

import autodrillnext.model.AutoDrillNEXTPlanRecord;
import autodrillnext.model.PlannerRequest;
import autodrillnext.model.UpgradeAction;
import autodrillnext.model.UpgradePlan;
import autodrillnext.world.TileKey;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class UpgradePlanner {
    public UpgradePlan diff(
        Map<TileKey, String> existing,
        Map<TileKey, String> target,
        AutoDrillNEXTPlanRecord ownership,
        PlannerRequest request
    ) {
        LinkedHashSet<TileKey> tiles = new LinkedHashSet<>();
        tiles.addAll(existing.keySet());
        tiles.addAll(target.keySet());
        ArrayList<TileKey> ordered = new ArrayList<>(tiles);
        ordered.sort(Comparator.naturalOrder());
        ArrayList<UpgradeAction> actions = new ArrayList<>();
        for (TileKey tile : ordered) {
            String before = existing.get(tile);
            String after = target.get(tile);
            if (after == null) {
                if (before != null && ownership.ownedTiles().contains(tile) && request.allowDestructiveRelayout()) {
                    actions.add(UpgradeAction.remove(tile, before, true));
                } else if (before != null) {
                    actions.add(new UpgradeAction(UpgradeAction.Kind.KEEP, tile, before, null, false));
                }
            } else if (before == null) {
                actions.add(UpgradeAction.add(tile, after));
            } else if (before.equals(after)) {
                actions.add(new UpgradeAction(UpgradeAction.Kind.KEEP, tile, before, after, true));
            } else {
                boolean executable = ownership.ownedTiles().contains(tile) && request.allowDestructiveRelayout();
                actions.add(new UpgradeAction(UpgradeAction.Kind.REPLACE, tile, before, after, executable));
            }
        }
        return new UpgradePlan(actions);
    }
}
