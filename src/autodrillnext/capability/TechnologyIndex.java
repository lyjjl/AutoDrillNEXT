package autodrillnext.capability;

import autodrillnext.model.ContentId;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

public final class TechnologyIndex {
    private final List<ContentId> current;
    private final List<ContentId> locked;

    private TechnologyIndex(List<ContentId> current, List<ContentId> locked) {
        this.current = List.copyOf(current);
        this.locked = List.copyOf(locked);
    }

    public static TechnologyIndex of(List<ContentId> current, List<ContentId> locked) {
        return new TechnologyIndex(current, locked);
    }

    public boolean isLocked(ContentId id) {
        return locked.contains(Objects.requireNonNull(id));
    }

    public List<ContentId> potentialUpgradesFrom(ContentId currentContent) {
        if (!current.contains(Objects.requireNonNull(currentContent))) return List.of();
        return locked;
    }

    public List<ContentId> current() {
        return current;
    }

    public List<ContentId> locked() {
        return locked;
    }
}
