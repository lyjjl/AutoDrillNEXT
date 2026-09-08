package autodrillnext.world;

/** Opaque, bit-preserving revision token; numeric ordering does not imply runtime chronology. */
public record TerrainRevision(long value) implements Comparable<TerrainRevision> {
    public static TerrainRevision initial() {
        return new TerrainRevision(0);
    }

    public static TerrainRevision of(long value) {
        return new TerrainRevision(value);
    }

    public TerrainRevision next() {
        return new TerrainRevision(Math.addExact(value, 1));
    }

    @Override
    public int compareTo(TerrainRevision other) {
        return Long.compare(value, other.value);
    }
}
