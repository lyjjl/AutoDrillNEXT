package autodrillnext.testsupport;

import java.util.Objects;

public record SyntheticBlock(String id, int size, boolean transport, boolean bridge) {
    public SyntheticBlock {
        Objects.requireNonNull(id);
        if (size < 1) throw new IllegalArgumentException("size must be positive");
    }
}
