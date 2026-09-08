package autodrillnext.ui;

import arc.Core;
import arc.files.Fi;
import arc.util.I18NBundle;
import autodrillnext.capability.spec.CapabilitySnapshot;
import autodrillnext.capability.spec.CostVector;
import autodrillnext.model.ContentId;
import autodrillnext.model.DiagnosticCode;
import autodrillnext.model.ExistingNetwork;
import autodrillnext.model.Inventory;
import autodrillnext.model.ObjectiveValue;
import autodrillnext.model.OptimalityCertificate;
import autodrillnext.model.PatchSearchScope;
import autodrillnext.model.PlannerDiagnostic;
import autodrillnext.model.PlannerRequest;
import autodrillnext.model.SearchStopReason;
import autodrillnext.model.SearchVerdict;
import autodrillnext.simulation.SimulationFidelity;
import autodrillnext.world.ExitAnchor;
import autodrillnext.world.ExitPort;
import autodrillnext.world.OrePatch;
import autodrillnext.world.TileKey;
import autodrillnext.world.TileOffset;
import autodrillnext.world.TileState;
import autodrillnext.world.WorldSnapshot;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SearchVerdictPresenterTest {
    @Test
    void verdictPresenterResolvesTruthfulChineseCopy() {
        I18NBundle previous = Core.bundle;
        Core.bundle = I18NBundle.createBundle(
            new Fi("assets/bundles/bundle"), Locale.SIMPLIFIED_CHINESE);
        try {
            SearchVerdictPresenter presenter = new SearchVerdictPresenter();
            assertEquals("当前最佳 · 已达到时间/状态上限", Core.bundle.get(
                presenter.bundleKey(certificate(
                    SearchVerdict.CURRENT_BEST, SearchStopReason.STATE_LIMIT))));
            assertEquals("当前最佳 · 支持模型不完整", Core.bundle.get(
                presenter.bundleKey(certificate(
                    SearchVerdict.CURRENT_BEST, SearchStopReason.MODEL_INCOMPLETE))));
            assertEquals("针对该矿区最优 · 已证明", Core.bundle.get(
                presenter.bundleKey(certificate(
                    SearchVerdict.PATCH_OPTIMAL, SearchStopReason.NONE))));
        } finally {
            Core.bundle = previous;
        }
    }

    @Test
    void verdictStylesHaveOneCentralMapping() {
        SearchVerdictPresenter presenter = new SearchVerdictPresenter();

        assertEquals(SearchVerdictPresenter.Tone.ERROR, presenter.style(SearchVerdict.NONE));
        assertEquals(SearchVerdictPresenter.Tone.AMBER, presenter.style(SearchVerdict.SEARCH_INCOMPLETE));
        assertEquals(SearchVerdictPresenter.Tone.AMBER, presenter.style(SearchVerdict.CURRENT_BEST));
        assertEquals(SearchVerdictPresenter.Tone.GREEN, presenter.style(SearchVerdict.PATCH_OPTIMAL));
        assertEquals(SearchVerdictPresenter.Tone.NEUTRAL, presenter.style(SearchVerdict.PROVEN_INFEASIBLE));
    }

    private OptimalityCertificate certificate(SearchVerdict verdict, SearchStopReason reason) {
        ObjectiveValue lower = verdict == SearchVerdict.SEARCH_INCOMPLETE
            || verdict == SearchVerdict.PROVEN_INFEASIBLE
            ? ObjectiveValue.zero("none")
            : new ObjectiveValue(1f, 1, CostVector.empty(), 1, 1, "best");
        ObjectiveValue upper = verdict == SearchVerdict.PATCH_OPTIMAL
            || verdict == SearchVerdict.PROVEN_INFEASIBLE
            ? lower
            : new ObjectiveValue(2f, 1, CostVector.empty(), 0, 0, "upper");
        List<PlannerDiagnostic> diagnostics = switch (reason) {
            case MODEL_INCOMPLETE -> List.of(PlannerDiagnostic.of(DiagnosticCode.UNSUPPORTED_CONTENT));
            case DEADLINE, STATE_LIMIT, HEURISTIC_MODE ->
                List.of(PlannerDiagnostic.of(DiagnosticCode.SEARCH_LIMIT_REACHED));
            case NONE -> List.of();
        };
        int pending = reason == SearchStopReason.NONE || reason == SearchStopReason.MODEL_INCOMPLETE ? 0 : 1;
        return new OptimalityCertificate(
            verdict, scope(), 3, 2, pending, lower, upper, reason, diagnostics);
    }

    private PatchSearchScope scope() {
        TileKey origin = new TileKey(0, 0);
        OrePatch patch = OrePatch.of(
            ContentId.of("copper"), origin, Set.of(new TileOffset(0, 0)));
        ExitPort port = ExitPort.forPreference(
            ExitPort.Side.RIGHT, ExitPort.Bias.CENTER, patch);
        ExitAnchor exit = port.anchors().get(0);
        WorldSnapshot world = WorldSnapshot.of(Map.of(
            origin, TileState.empty(),
            exit.tile(), TileState.empty()
        ));
        return PatchSearchScope.capture(
            world,
            patch,
            new CapabilitySnapshot(Map.of()),
            Inventory.empty(),
            ExistingNetwork.empty(),
            exit,
            PlannerRequest.defaults(origin, "blue", port),
            null,
            null,
            SimulationFidelity.BOUNDED_MODEL,
            true
        );
    }
}
