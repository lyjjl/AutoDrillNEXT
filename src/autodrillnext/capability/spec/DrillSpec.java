package autodrillnext.capability.spec;

import autodrillnext.model.ContentId;
import autodrillnext.model.SupportRequirement;

import java.util.List;
import java.util.Objects;

public record DrillSpec(
    ContentId id,
    int size,
    int range,
    float drillTime,
    float optionalBoostIntensity,
    List<SupportRequirement> mandatorySupport,
    List<SupportVariant> optionalSupport,
    CostVector cost,
    boolean rotates,
    float baselineProductionMultiplier
) implements CapabilitySpec {
    public DrillSpec(ContentId id, int size, int range, float drillTime, float optionalBoostIntensity,
                     List<SupportRequirement> mandatorySupport, List<SupportVariant> optionalSupport, CostVector cost) {
        this(id, size, range, drillTime, optionalBoostIntensity, mandatorySupport, optionalSupport, cost, range > 0);
    }

    public DrillSpec(ContentId id, int size, int range, float drillTime, float optionalBoostIntensity,
                     List<SupportRequirement> mandatorySupport, List<SupportVariant> optionalSupport, CostVector cost,
                     boolean rotates) {
        this(id, size, range, drillTime, optionalBoostIntensity, mandatorySupport, optionalSupport, cost, rotates, 1f);
    }

    public DrillSpec {
        Objects.requireNonNull(id, "id");
        mandatorySupport = List.copyOf(mandatorySupport);
        optionalSupport = List.copyOf(optionalSupport);
        Objects.requireNonNull(cost, "cost");
        if (size < 1 || range < 0) throw new IllegalArgumentException("invalid drill dimensions");
        if (!Float.isFinite(drillTime) || drillTime <= 0f) throw new IllegalArgumentException("invalid drill time");
        if (!Float.isFinite(optionalBoostIntensity) || optionalBoostIntensity < 0f) {
            throw new IllegalArgumentException("invalid boost intensity");
        }
        if (!Float.isFinite(baselineProductionMultiplier) || baselineProductionMultiplier < 0f) {
            throw new IllegalArgumentException("invalid baseline production multiplier");
        }
    }

    public float productionPerSecond(int coveredOreCount) {
        if (coveredOreCount < 0) throw new IllegalArgumentException("covered ore count must not be negative");
        return 60f / drillTime * coveredOreCount * baselineProductionMultiplier;
    }

    public boolean minesFloorOre() {
        return range == 0;
    }

    public boolean minesWallOre() {
        return range > 0;
    }
}
