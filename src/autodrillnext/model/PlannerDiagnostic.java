package autodrillnext.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public record PlannerDiagnostic(DiagnosticCode code, Map<String, String> arguments) {
    public PlannerDiagnostic {
        Objects.requireNonNull(code, "code");
        LinkedHashMap<String, String> copy = new LinkedHashMap<>();
        arguments.forEach((key, value) -> copy.put(
            Objects.requireNonNull(key, "diagnostic argument key"),
            Objects.requireNonNull(value, "diagnostic argument value")
        ));
        arguments = Collections.unmodifiableMap(copy);
    }

    public static PlannerDiagnostic of(DiagnosticCode code) {
        return new PlannerDiagnostic(code, Map.of());
    }
}
