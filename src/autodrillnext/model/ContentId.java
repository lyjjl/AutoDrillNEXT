package autodrillnext.model;

import java.util.Objects;

public record ContentId(String value) implements Comparable<ContentId> {
    public ContentId {
        Objects.requireNonNull(value, "value");
        if (value.isBlank()) throw new IllegalArgumentException("content id must not be blank");
    }

    public static ContentId of(String value) {
        return new ContentId(value);
    }

    @Override
    public int compareTo(ContentId other) {
        return value.compareTo(other.value);
    }
}
