package autodrillnext.world;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlanOverlayTest {
    @Test
    void evenFootprintsMatchMindustryAnchorConvention() {
        TileKey anchor = new TileKey(5, 5);
        assertEquals(Set.of(new TileKey(5, 5), new TileKey(6, 5), new TileKey(5, 6), new TileKey(6, 6)),
            PlacementFootprint.square(anchor, 2).tiles());
        Set<TileKey> four = PlacementFootprint.square(anchor, 4).tiles();
        assertEquals(16, four.size());
        assertTrue(four.contains(new TileKey(4, 4)));
        assertTrue(four.contains(new TileKey(7, 7)));
        assertFalse(four.contains(new TileKey(3, 3)));
    }

    @Test
    void ordinaryFootprintsCannotOverlapPlannedFootprints() {
        PlacementFootprint first = PlacementFootprint.of(Set.of(
            new TileKey(1, 1),
            new TileKey(2, 1)
        ));
        PlacementFootprint overlapping = PlacementFootprint.of(Set.of(new TileKey(2, 1), new TileKey(3, 1)));

        PlanOverlay overlay = new PlanOverlay();
        assertTrue(overlay.canReserve(first));
        overlay.reserve(PlanPlacement.simple("first", first));
        assertFalse(overlay.canReserve(overlapping));
    }

    @Test
    void bridgeAndConfigReservationsAreCheckedSeparatelyFromFootprints() {
        PlacementFootprint first = PlacementFootprint.of(Set.of(new TileKey(5, 5)));
        PlacementFootprint second = PlacementFootprint.of(Set.of(new TileKey(8, 8)));
        LinkReservation link = LinkReservation.of(new TileKey(5, 5), new TileKey(8, 8));

        PlanOverlay overlay = new PlanOverlay();
        overlay.reserve(new PlanPlacement("first", first, Set.of(link), Set.of("liquid:water")));

        assertFalse(overlay.canReserve(new PlanPlacement("same-link", second, Set.of(link), Set.of())));
        assertFalse(overlay.canReserve(new PlanPlacement(
            "same-config", second, Set.of(), Set.of("liquid:water")
        )));
    }
}
