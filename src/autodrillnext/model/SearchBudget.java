package autodrillnext.model;

import java.util.Objects;
import java.util.function.LongSupplier;

/** Monotonic cooperative deadline shared by every nested search operation. */
public final class SearchBudget {
    private static final Expired EXPIRED = new Expired();

    private final long deadlineNanos;
    private final LongSupplier nanoTime;
    private final boolean unlimited;

    private SearchBudget(long deadlineNanos, LongSupplier nanoTime, boolean unlimited) {
        this.deadlineNanos = deadlineNanos;
        this.nanoTime = Objects.requireNonNull(nanoTime, "nano time");
        this.unlimited = unlimited;
    }

    public static SearchBudget until(long deadlineNanos, LongSupplier nanoTime) {
        return new SearchBudget(deadlineNanos, nanoTime, false);
    }

    public static SearchBudget unlimited() {
        return new SearchBudget(Long.MAX_VALUE, System::nanoTime, true);
    }

    public boolean expired() {
        return Thread.currentThread().isInterrupted()
            || !unlimited && nanoTime.getAsLong() >= deadlineNanos;
    }

    public void checkpoint() {
        if (expired()) throw EXPIRED;
    }

    public static final class Expired extends RuntimeException {
        private Expired() {
            super("search budget expired", null, false, false);
        }
    }
}
