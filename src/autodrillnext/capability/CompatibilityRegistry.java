package autodrillnext.capability;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public final class CompatibilityRegistry {
    private final Map<Class<?>, String> adapterIds = new LinkedHashMap<>();

    public void register(Class<?> blockType, String adapterId) {
        adapterIds.put(Objects.requireNonNull(blockType), Objects.requireNonNull(adapterId));
    }

    public CompatibilityRegistry registerAndReturn(Class<?> blockType, String adapterId) {
        register(blockType, adapterId);
        return this;
    }

    public String adapterIdFor(Class<?> blockType) {
        Class<?> best = null;
        for (Class<?> registered : adapterIds.keySet()) {
            if (!registered.isAssignableFrom(blockType)) continue;
            if (best == null || best.isAssignableFrom(registered)) best = registered;
        }
        return best == null ? null : adapterIds.get(best);
    }
}
