package autodrillnext.ui;

import autodrillnext.world.ExitPort;
import autodrillnext.world.OrePatch;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

public final class ExitSelector12 {
    public List<Option> options(OrePatch patch) {
        Objects.requireNonNull(patch, "patch");
        ArrayList<Option> options = new ArrayList<>();
        for (ExitPort port : ExitPort.allPreferences(patch)) {
            options.add(new Option(port, key(port)));
        }
        return List.copyOf(options);
    }

    public ExitPort select(OrePatch patch, ExitPort.Side side, ExitPort.Bias bias) {
        Objects.requireNonNull(patch, "patch");
        return ExitPort.forPreference(side, bias, patch);
    }

    public String key(ExitPort port) {
        return "auto-drill-next.exit." + port.side().name().toLowerCase(Locale.ROOT)
            + "." + port.bias().name().toLowerCase(Locale.ROOT);
    }

    public record Option(ExitPort port, String labelKey) {
        public Option {
            Objects.requireNonNull(port, "exit port");
            Objects.requireNonNull(labelKey, "exit label key");
        }
    }
}
