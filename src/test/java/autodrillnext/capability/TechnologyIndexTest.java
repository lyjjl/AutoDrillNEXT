package autodrillnext.capability;

import autodrillnext.model.ContentId;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TechnologyIndexTest {
    @Test
    void lockedContentExplainsUnlockPathWithoutBecomingCurrentCapability() {
        ContentId current = ContentId.of("mindustry:conveyor");
        ContentId locked = ContentId.of("mindustry:titanium-conveyor");
        TechnologyIndex index = TechnologyIndex.of(List.of(current), List.of(locked));

        assertTrue(index.isLocked(locked));
        assertEquals(List.of(locked), index.potentialUpgradesFrom(current));
    }
}
