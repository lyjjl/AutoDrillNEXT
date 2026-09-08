package autodrillnext.world;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

public record PlanPlacement(
    String id,
    PlacementFootprint footprint,
    Set<LinkReservation> links,
    Set<String> configs
) {
    public PlanPlacement {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("placement id must not be blank");
        Objects.requireNonNull(footprint, "footprint");
        links = immutableCopy(links, "link");
        configs = immutableCopy(configs, "config");
    }

    public static PlanPlacement simple(String id, PlacementFootprint footprint) {
        return new PlanPlacement(id, footprint, Set.of(), Set.of());
    }

    private static <T> Set<T> immutableCopy(Set<T> values, String label) {
        LinkedHashSet<T> copy = new LinkedHashSet<>();
        for (T value : values) copy.add(Objects.requireNonNull(value, label));
        return Collections.unmodifiableSet(copy);
    }
}
