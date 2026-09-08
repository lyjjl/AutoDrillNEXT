package autodrillnext.ui;

import autodrillnext.model.ContentId;
import autodrillnext.model.DiagnosticCode;
import autodrillnext.world.OrePatch;
import autodrillnext.world.TileKey;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlannerDiagnosticsTest {
    @Test
    void everyDiagnosticCodeHasAnEnglishAndChineseBundleKey() throws IOException {
        PlannerDiagnostics diagnostics = new PlannerDiagnostics();
        Set<String> english = keys(Path.of("assets/bundles/bundle.properties"));
        Set<String> chinese = keys(Path.of("assets/bundles/bundle_zh_CN.properties"));

        for (DiagnosticCode code : DiagnosticCode.values()) {
            String key = diagnostics.bundleKey(code);
            assertTrue(english.contains(key), () -> "missing English key: " + key);
            assertTrue(chinese.contains(key), () -> "missing Simplified Chinese key: " + key);
        }
    }

    @Test
    void noValidSinkTraceHasLocalizedTemplates() throws IOException {
        Set<String> english = keys(Path.of("assets/bundles/bundle.properties"));
        Set<String> chinese = keys(Path.of("assets/bundles/bundle_zh_CN.properties"));

        assertTrue(english.contains("auto-drill-next.diagnostic.no-valid-sink-detail"));
        assertTrue(chinese.contains("auto-drill-next.diagnostic.no-valid-sink-detail"));
    }

    @Test
    void selectorExposesExactlyTwelveStableExitChoices() {
        OrePatch patch = OrePatch.of(
            ContentId.of("mindustry:copper"),
            new TileKey(0, 0),
            Set.of(new autodrillnext.world.TileOffset(0, 0))
        );
        var options = new ExitSelector12().options(patch);

        assertEquals(12, options.size());
        assertEquals(12, options.stream().map(option -> option.port().id()).collect(Collectors.toSet()).size());
        assertEquals(12, options.stream().map(ExitSelector12.Option::labelKey).collect(Collectors.toSet()).size());
    }

    @Test
    void buildFailurePrefersBlockingReasonOverSearchAndThroughputWarnings() {
        var search = autodrillnext.model.PlannerDiagnostic.of(DiagnosticCode.SEARCH_LIMIT_REACHED);
        var partial = autodrillnext.model.PlannerDiagnostic.of(DiagnosticCode.PARTIAL_PLAN);
        var overlap = new autodrillnext.model.PlannerDiagnostic(DiagnosticCode.TERRAIN_OR_RULE_BLOCKED,
            java.util.Map.of("reason", "overlap", "tile", "281,338"));
        assertEquals(overlap, new PlannerDiagnostics().primaryFailure(java.util.List.of(search, partial, overlap)));
        assertEquals(search, new PlannerDiagnostics().primaryFailure(java.util.List.of(search)));
        org.junit.jupiter.api.Assertions.assertNull(new PlannerDiagnostics().primaryFailure(java.util.List.of()));
    }

    private static Set<String> keys(Path path) throws IOException {
        return Files.readAllLines(path).stream()
            .map(String::trim)
            .filter(line -> !line.isEmpty() && !line.startsWith("#") && !line.startsWith("!"))
            .map(line -> line.split("[=:]", 2)[0].trim())
            .collect(Collectors.toCollection(TreeSet::new));
    }
}
