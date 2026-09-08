package autodrillnext.model;

public record PowerDemand(float perSecond, boolean mandatory) {
    public PowerDemand {
        if (!Float.isFinite(perSecond) || perSecond < 0f) {
            throw new IllegalArgumentException("power demand must be finite and non-negative");
        }
    }
}
