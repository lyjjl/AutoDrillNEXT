package autodrillnext.world;

public record TileOffset(int x, int y) implements Comparable<TileOffset> {
    public TileKey from(TileKey origin) {
        return new TileKey(origin.x() + x, origin.y() + y);
    }

    @Override
    public int compareTo(TileOffset other) {
        int byX = Integer.compare(x, other.x);
        return byX != 0 ? byX : Integer.compare(y, other.y);
    }
}
