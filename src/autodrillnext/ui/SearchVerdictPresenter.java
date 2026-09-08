package autodrillnext.ui;

import autodrillnext.model.OptimalityCertificate;
import autodrillnext.model.SearchStopReason;
import autodrillnext.model.SearchVerdict;

import java.util.Objects;

/** The single mapping from proof semantics to user-facing copy and tone. */
public final class SearchVerdictPresenter {
    public enum Tone {
        NEUTRAL,
        AMBER,
        GREEN,
        ERROR
    }

    public String bundleKey(OptimalityCertificate certificate) {
        Objects.requireNonNull(certificate, "certificate");
        return switch (certificate.verdict()) {
            case NONE -> "auto-drill-next.ui.verdict.none";
            case SEARCH_INCOMPLETE -> switch (certificate.stopReason()) {
                case MODEL_INCOMPLETE -> "auto-drill-next.ui.verdict.search-incomplete.model-incomplete";
                case HEURISTIC_MODE -> "auto-drill-next.ui.verdict.search-incomplete.heuristic";
                default -> "auto-drill-next.ui.verdict.search-incomplete.limit";
            };
            case CURRENT_BEST -> switch (certificate.stopReason()) {
                case MODEL_INCOMPLETE -> "auto-drill-next.ui.verdict.current-best.model-incomplete";
                case HEURISTIC_MODE -> "auto-drill-next.ui.verdict.current-best.heuristic";
                default -> "auto-drill-next.ui.verdict.current-best.limit";
            };
            case PATCH_OPTIMAL -> "auto-drill-next.ui.verdict.patch-optimal";
            case PROVEN_INFEASIBLE -> "auto-drill-next.ui.verdict.proven-infeasible";
        };
    }

    public Tone style(SearchVerdict verdict) {
        Objects.requireNonNull(verdict, "verdict");
        return switch (verdict) {
            case NONE -> Tone.ERROR;
            case SEARCH_INCOMPLETE, CURRENT_BEST -> Tone.AMBER;
            case PATCH_OPTIMAL -> Tone.GREEN;
            case PROVEN_INFEASIBLE -> Tone.NEUTRAL;
        };
    }
}
