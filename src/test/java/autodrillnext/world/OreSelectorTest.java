package autodrillnext.world;

import autodrillnext.model.ContentId;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class OreSelectorTest {
    @Test
    void sandFloorDoesNotQualifyAsMineableOre() {
        assertNull(OreSelector.selectableOre(ContentId.of("sand"), null));
    }

    @Test
    void mineableWallOreStillQualifiesOverSandFloor() {
        ContentId lead = ContentId.of("lead");

        assertEquals(lead, OreSelector.selectableOre(ContentId.of("sand"), lead));
    }
}
