package autodrillnext.model;

import autodrillnext.capability.spec.CapabilitySnapshot;
import autodrillnext.capability.spec.CostVector;
import autodrillnext.simulation.SimulationFidelity;
import autodrillnext.world.ExitAnchor;
import autodrillnext.world.ExitPort;
import autodrillnext.world.OrePatch;
import autodrillnext.world.TileKey;
import autodrillnext.world.TileOffset;
import autodrillnext.world.TileState;
import autodrillnext.world.WorldSnapshot;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class OptimalityCertificateTest {
    @Test
    void rejectsAProvenVerdictWithPendingStates() {
        assertThrows(IllegalArgumentException.class, () -> new OptimalityCertificate(
            SearchVerdict.PATCH_OPTIMAL,
            scope(Inventory.empty()),
            4,
            2,
            1,
            value(2f, "same"),
            value(2f, "same"),
            SearchStopReason.NONE,
            List.of()
        ));
    }

    @Test
    void incompleteSearchWithoutIncumbentIsNotNoSolutionProof() {
        OptimalityCertificate certificate = new OptimalityCertificate(
            SearchVerdict.SEARCH_INCOMPLETE,
            scope(Inventory.empty()),
            1,
            0,
            3,
            ObjectiveValue.zero("none"),
            value(5f, "upper"),
            SearchStopReason.STATE_LIMIT,
            List.of(PlannerDiagnostic.of(DiagnosticCode.SEARCH_LIMIT_REACHED))
        );

        assertFalse(certificate.optimal());
        assertEquals(SearchVerdict.SEARCH_INCOMPLETE, certificate.verdict());
        assertEquals(3, certificate.pendingStates());
    }

    @Test
    void noneHasNoScopeBoundsOrSearchCounts() {
        OptimalityCertificate certificate = OptimalityCertificate.none();

        assertEquals(SearchVerdict.NONE, certificate.verdict());
        assertNull(certificate.scope());
        assertEquals(ObjectiveValue.zero("none"), certificate.lowerBound());
        assertEquals(certificate.lowerBound(), certificate.upperBound());
        assertEquals(0, certificate.exploredStates());
        assertEquals(0, certificate.prunedStates());
        assertEquals(0, certificate.pendingStates());
    }


    private PatchSearchScope scope(Inventory inventory) {
        TileKey origin = new TileKey(0, 0);
        OrePatch patch = OrePatch.of(ContentId.of("copper"), origin, Set.of(new TileOffset(0, 0)));
        ExitPort exit = ExitPort.forPreference(ExitPort.Side.RIGHT, ExitPort.Bias.CENTER, patch);
        ExitAnchor anchor = exit.anchors().get(0);
        WorldSnapshot world = WorldSnapshot.of(Map.of(
            origin, TileState.empty(),
            anchor.tile(), TileState.empty()
        ));
        return PatchSearchScope.capture(
            world,
            patch,
            new CapabilitySnapshot(Map.of()),
            inventory,
            ExistingNetwork.empty(),
            anchor,
            PlannerRequest.defaults(origin, "blue", exit),
            null,
            null,
            SimulationFidelity.BOUNDED_MODEL,
            true
        );
    }

    private ObjectiveValue value(float qout, String id) {
        return new ObjectiveValue(qout, 0, CostVector.empty(), 0, 0, id);
    }
}
