package autodrillnext.capability.spec;

import autodrillnext.model.ContentId;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public final class CapabilitySnapshot {
    private final Map<ContentId, CapabilityDescriptor> descriptors;

    public CapabilitySnapshot(Map<ContentId, CapabilityDescriptor> descriptors) {
        LinkedHashMap<ContentId, CapabilityDescriptor> copy = new LinkedHashMap<>();
        descriptors.entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .forEach(entry -> {
                ContentId id = Objects.requireNonNull(entry.getKey(), "id");
                CapabilityDescriptor descriptor = Objects.requireNonNull(entry.getValue(), "descriptor");
                copy.put(id, descriptor);
            });
        this.descriptors = Collections.unmodifiableMap(copy);
    }

    public CapabilityDescriptor require(ContentId id) {
        CapabilityDescriptor descriptor = descriptors.get(id);
        if (descriptor == null) throw new IllegalArgumentException("unknown capability: " + id.value());
        return descriptor;
    }

    public Map<ContentId, CapabilityDescriptor> descriptors() {
        return descriptors;
    }

    public long revision() {
        long fingerprint = 1125899906842597L;
        for (Map.Entry<ContentId, CapabilityDescriptor> entry : descriptors.entrySet()) {
            CapabilityDescriptor descriptor = entry.getValue();
            fingerprint = 31 * fingerprint + entry.getKey().hashCode();
            fingerprint = 31 * fingerprint + descriptor.kind().hashCode();
            fingerprint = 31 * fingerprint + descriptor.states().hashCode();
            fingerprint = 31 * fingerprint + descriptor.reasons().hashCode();
            fingerprint = 31 * fingerprint + descriptor.cost().hashCode();
            fingerprint = 31 * fingerprint + descriptor.adapterId().hashCode();
            fingerprint = 31 * fingerprint + String.valueOf(descriptor.spec()).hashCode();
        }
        return fingerprint;
    }
}
