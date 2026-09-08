package autodrillnext.capability.spec;

import autodrillnext.model.ContentId;

public interface CapabilitySpec {
    ContentId id();

    CostVector cost();
}
