package autodrillnext.model;

import java.util.Objects;

public record BudgetSnapshot(Inventory inventory, boolean infiniteResources) {
    public BudgetSnapshot {
        Objects.requireNonNull(inventory, "inventory");
    }
}
