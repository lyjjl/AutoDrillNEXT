package autodrillnext.solver;

import autodrillnext.model.AutoDrillNEXTPlanRecord;
import autodrillnext.model.PlannerRequest;
import autodrillnext.model.UpgradeAction;
import autodrillnext.model.UpgradePlan;
import autodrillnext.world.ExitPort;
import autodrillnext.world.OrePatch;
import autodrillnext.world.TerrainRevision;
import autodrillnext.world.TileKey;
import autodrillnext.world.TileOffset;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UpgradePlannerTest {
    @Test
    void unknownPlayerBuildingNeverProducesRemoveAction() {
        Map<TileKey, String> existing = Map.of(new TileKey(1, 1), "player:conveyor");
        Map<TileKey, String> target = Map.of();
        AutoDrillNEXTPlanRecord ownership = AutoDrillNEXTPlanRecord.empty(TerrainRevision.of(1));

        UpgradePlan plan = new UpgradePlanner().diff(existing, target, ownership, request(false));

        assertFalse(plan.actions().stream().anyMatch(action -> action.kind() == UpgradeAction.Kind.REMOVE));
    }

    @Test
    void ownedRemovalRequiresExplicitDestructiveRequest() {
        TileKey tile = new TileKey(1, 1);
        AutoDrillNEXTPlanRecord ownership = new AutoDrillNEXTPlanRecord(
            "plan",
            Set.of(tile),
            Map.of(tile, "fingerprint"),
            Set.of(tile),
            TerrainRevision.of(1)
        );
        Map<TileKey, String> existing = Map.of(tile, "mod:old-drill");

        UpgradePlan safe = new UpgradePlanner().diff(existing, Map.of(), ownership, request(false));
        UpgradePlan destructive = new UpgradePlanner().diff(existing, Map.of(), ownership, request(true));

        assertFalse(safe.actions().stream().anyMatch(action -> action.kind() == UpgradeAction.Kind.REMOVE));
        assertTrue(destructive.actions().stream().anyMatch(action -> action.kind() == UpgradeAction.Kind.REMOVE));
    }

    @Test
    void additionsAreSequencedBeforeReplacementAndRemoval() {
        TileKey add = new TileKey(0, 0);
        TileKey replace = new TileKey(1, 0);
        TileKey remove = new TileKey(2, 0);
        AutoDrillNEXTPlanRecord ownership = new AutoDrillNEXTPlanRecord(
            "plan",
            Set.of(add, replace, remove),
            Map.of(add, "a", replace, "b", remove, "c"),
            Set.of(replace, remove),
            TerrainRevision.of(1)
        );
        UpgradePlan plan = new UpgradePlanner().diff(
            Map.of(replace, "old", remove, "old-remove"),
            Map.of(add, "new", replace, "new"),
            ownership,
            request(true)
        );

        List<UpgradeAction.Kind> kinds = plan.sequence().stream().map(UpgradeAction::kind).toList();
        assertTrue(kinds.indexOf(UpgradeAction.Kind.ADD) < kinds.indexOf(UpgradeAction.Kind.REPLACE));
        assertTrue(kinds.indexOf(UpgradeAction.Kind.REPLACE) < kinds.indexOf(UpgradeAction.Kind.REMOVE));
    }

    private PlannerRequest request(boolean destructive) {
        OrePatch patch = OrePatch.of(
            autodrillnext.model.ContentId.of("mindustry:copper"),
            new TileKey(0, 0),
            Set.of(new TileOffset(0, 0))
        );
        return new PlannerRequest(new TileKey(0, 0),
        "sharded",
        ExitPort.forPreference(ExitPort.Side.RIGHT, ExitPort.Bias.CENTER, patch),
        PlannerRequest.Profile.BALANCED,
        0f,
        1.10f,
        PlannerRequest.BudgetMode.CURRENT_INVENTORY,
        destructive,
        100,
        100, true);
    }
}
