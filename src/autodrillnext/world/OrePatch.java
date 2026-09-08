package autodrillnext.world;

import autodrillnext.model.ContentId;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

public final class OrePatch {
    private final ContentId ore;
    private final TileKey origin;
    private final Set<TileOffset> relativeCells;
    private final Set<TileKey> absoluteCells;

    private OrePatch(ContentId ore, TileKey origin, Set<TileOffset> relativeCells) {
        this.ore = Objects.requireNonNull(ore, "ore");
        this.origin = Objects.requireNonNull(origin, "origin");
        if (relativeCells.isEmpty()) throw new IllegalArgumentException("ore patch must not be empty");

        LinkedHashSet<TileOffset> offsets = new LinkedHashSet<>(relativeCells);
        this.relativeCells = Collections.unmodifiableSet(offsets);
        LinkedHashSet<TileKey> absolute = new LinkedHashSet<>();
        offsets.forEach(offset -> absolute.add(offset.from(origin)));
        this.absoluteCells = Collections.unmodifiableSet(absolute);
    }

    public static OrePatch of(ContentId ore, TileKey origin, Set<TileOffset> relativeCells) {
        return new OrePatch(ore, origin, relativeCells);
    }

    public ContentId ore() {
        return ore;
    }

    public TileKey origin() {
        return origin;
    }

    public Set<TileOffset> relativeCells() {
        return relativeCells;
    }

    public Set<TileKey> absoluteCells() {
        return absoluteCells;
    }

    public boolean contains(TileKey tile) {
        return absoluteCells.contains(tile);
    }

    public int minX() {
        return absoluteCells.stream().mapToInt(TileKey::x).min().orElseThrow();
    }

    public int maxX() {
        return absoluteCells.stream().mapToInt(TileKey::x).max().orElseThrow();
    }

    public int minY() {
        return absoluteCells.stream().mapToInt(TileKey::y).min().orElseThrow();
    }

    public int maxY() {
        return absoluteCells.stream().mapToInt(TileKey::y).max().orElseThrow();
    }
}
