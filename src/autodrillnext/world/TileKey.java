package autodrillnext.world;

public record TileKey(int x, int y) implements Comparable<TileKey> {
    public TileKey translated(int dx, int dy) {
        return new TileKey(x + dx, y + dy);
    }

    public TileOffset offsetFrom(TileKey origin) {
        return new TileOffset(x - origin.x, y - origin.y);
    }

    @Override
    public int compareTo(TileKey other) {
        int byX = Integer.compare(x, other.x);
        return byX != 0 ? byX : Integer.compare(y, other.y);
    }
}
