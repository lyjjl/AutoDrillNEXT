package autodrillnext.capability.adapter;

import autodrillnext.capability.spec.SupportVariant;
import autodrillnext.model.SupportRequirement;

import java.util.List;

record SupportRequirements(
    List<SupportRequirement> mandatory,
    List<SupportVariant> optional
) {
    SupportRequirements {
        mandatory = List.copyOf(mandatory);
        optional = List.copyOf(optional);
    }
}
