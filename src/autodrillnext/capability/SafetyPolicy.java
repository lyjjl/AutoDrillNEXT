package autodrillnext.capability;

import autodrillnext.model.ContentId;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

public final class SafetyPolicy {
    private final Set<ContentId> allowedIds;
    private final Set<ContentId> deniedIds;

    private SafetyPolicy(Set<ContentId> allowedIds, Set<ContentId> deniedIds) {
        this.allowedIds = Set.copyOf(allowedIds);
        this.deniedIds = Set.copyOf(deniedIds);
    }

    public static Builder builder() {
        return new Builder();
    }

    public boolean allowed(ContentId id) {
        return !deniedIds.contains(id) && (allowedIds.isEmpty() || allowedIds.contains(id));
    }

    public boolean denied(ContentId id) {
        return deniedIds.contains(id);
    }

    public Set<ContentId> allowedIds() {
        return Collections.unmodifiableSet(allowedIds);
    }

    public Set<ContentId> deniedIds() {
        return Collections.unmodifiableSet(deniedIds);
    }

    public static final class Builder {
        private final Set<ContentId> allowedIds = new LinkedHashSet<>();
        private final Set<ContentId> deniedIds = new LinkedHashSet<>();

        public Builder allow(ContentId id) {
            allowedIds.add(id);
            return this;
        }

        public Builder deny(ContentId id) {
            deniedIds.add(id);
            return this;
        }

        public SafetyPolicy build() {
            return new SafetyPolicy(allowedIds, deniedIds);
        }
    }
}
