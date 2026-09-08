package autodrillnext.capability;

import autodrillnext.capability.spec.CapabilityCandidate;
import autodrillnext.capability.spec.CapabilityDescriptor;
import autodrillnext.capability.spec.CapabilityKind;
import autodrillnext.capability.spec.CapabilitySnapshot;
import autodrillnext.capability.spec.CapabilityState;
import autodrillnext.model.ContentId;
import autodrillnext.model.Inventory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class CapabilityRegistry {
    public CapabilitySnapshot evaluate(
        List<CapabilityCandidate> candidates,
        Inventory inventory,
        boolean infiniteResources
    ) {
        return evaluate(candidates, inventory, infiniteResources, SafetyPolicy.builder().build());
    }

    public CapabilitySnapshot evaluate(
        List<CapabilityCandidate> candidates,
        Inventory inventory,
        boolean infiniteResources,
        SafetyPolicy safetyPolicy
    ) {
        ArrayList<CapabilityCandidate> ordered = new ArrayList<>(candidates);
        ordered.sort(Comparator.comparing(candidate -> candidate.id().value()));

        LinkedHashMap<ContentId, CapabilityDescriptor> descriptors = new LinkedHashMap<>();
        for (CapabilityCandidate candidate : ordered) {
            EnumSet<CapabilityState> states = EnumSet.of(CapabilityState.DISCOVERED);
            LinkedHashSet<String> reasons = new LinkedHashSet<>();

            boolean supported = candidate.supported() && candidate.kind() != CapabilityKind.UNKNOWN;
            if (supported) {
                states.add(CapabilityState.SUPPORTED);
            } else {
                reasons.add("UNKNOWN_SEMANTICS");
                reasons.add("UNSUPPORTED_CONTENT");
            }

            boolean safetyAllowed = safetyPolicy.allowed(candidate.id());
            if (!safetyAllowed) reasons.add("SAFETY_POLICY_DENIED");

            boolean available = supported
                && safetyAllowed
                && candidate.unlocked()
                && candidate.placeable()
                && !candidate.overPlacementLimit();
            if (available) {
                states.add(CapabilityState.AVAILABLE_NOW);
            } else if (supported && !safetyAllowed) {
                // The safety reason is already recorded above.
            } else if (supported && !candidate.unlocked()) {
                reasons.add("NO_UNLOCKED_CONTENT");
            } else if (supported) {
                reasons.add("TERRAIN_OR_RULE_BLOCKED");
            }

            if (available && (infiniteResources || candidate.cost().componentWiseAtMost(inventory))) {
                states.add(CapabilityState.AFFORDABLE_NOW);
            } else if (available) {
                reasons.add("BUDGET_DEFICIT");
            }

            descriptors.put(candidate.id(), new CapabilityDescriptor(
                candidate.id(),
                candidate.kind(),
                states,
                reasons,
                candidate.cost(),
                candidate.adapterId(),
                candidate.spec()
            ));
        }
        return new CapabilitySnapshot(descriptors);
    }
}
