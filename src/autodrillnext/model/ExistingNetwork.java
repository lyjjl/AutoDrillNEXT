package autodrillnext.model;

import autodrillnext.world.LinkReservation;
import autodrillnext.world.TileKey;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

public record ExistingNetwork(
    Set<LinkReservation> links,
    Set<TileKey> traversableTiles,
    float powerSupplyPerSecond,
    java.util.Map<LiquidId, Float> liquidSupplyPerSecond,
    Set<TileKey> occupiedTiles
) {
    public ExistingNetwork(Set<LinkReservation> links, Set<TileKey> traversableTiles) {
        this(links, traversableTiles, 0f, java.util.Map.of(), traversableTiles);
    }

    public ExistingNetwork(
        Set<LinkReservation> links,
        Set<TileKey> traversableTiles,
        float powerSupplyPerSecond,
        java.util.Map<LiquidId, Float> liquidSupplyPerSecond
    ) {
        this(links, traversableTiles, powerSupplyPerSecond, liquidSupplyPerSecond, traversableTiles);
    }

    public ExistingNetwork {
        links = immutableCopy(links);
        traversableTiles = immutableCopy(traversableTiles);
        occupiedTiles = immutableCopy(occupiedTiles);
        if (!Float.isFinite(powerSupplyPerSecond) || powerSupplyPerSecond < 0f) {
            throw new IllegalArgumentException("power supply must be finite and non-negative");
        }
        java.util.LinkedHashMap<LiquidId, Float> supply = new java.util.LinkedHashMap<>();
        liquidSupplyPerSecond.forEach((liquid, amount) -> {
            java.util.Objects.requireNonNull(liquid, "liquid");
            java.util.Objects.requireNonNull(amount, "liquid supply");
            if (!Float.isFinite(amount) || amount < 0f) throw new IllegalArgumentException("invalid liquid supply");
            if (amount > 0f) supply.put(liquid, amount);
        });
        liquidSupplyPerSecond = java.util.Collections.unmodifiableMap(supply);
    }

    public static ExistingNetwork empty() {
        return new ExistingNetwork(Set.of(), Set.of(), 0f, java.util.Map.of(), Set.of());
    }

    public boolean hasLink(TileKey first, TileKey second) {
        return links.contains(LinkReservation.of(first, second));
    }

    private static <T> Set<T> immutableCopy(Set<T> values) {
        java.util.LinkedHashSet<T> copy = new java.util.LinkedHashSet<>(values);
        return java.util.Collections.unmodifiableSet(copy);
    }
}
