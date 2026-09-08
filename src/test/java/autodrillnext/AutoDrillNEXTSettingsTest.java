package autodrillnext;

import arc.input.KeyCode;
import autodrillnext.model.PlannerRequest;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AutoDrillNEXTSettingsTest {
    @Test
    void missingActivationSettingFallsBackToH() {
        assertTrue(AutoDrillNEXT.activationKeyMatches(null, KeyCode.h));
    }

    @Test
    void configuredActivationSettingMatchesOnlyTheConfiguredKey() {
        assertTrue(AutoDrillNEXT.activationKeyMatches("h", KeyCode.h));
        assertFalse(AutoDrillNEXT.activationKeyMatches("j", KeyCode.h));
    }

    @Test
    void settingsUseMindustryLocalizedLabelKeys() throws IOException {
        Set<String> english = Files.readAllLines(Path.of("assets/bundles/bundle.properties")).stream()
            .map(String::trim)
            .filter(line -> !line.isEmpty() && !line.startsWith("#") && !line.startsWith("!"))
            .map(line -> line.split("[=:]", 2)[0].trim())
            .collect(Collectors.toSet());

        for (String setting : List.of(
            "auto-drill-next.settings.activation-key",
            "auto-drill-next.settings.display-toggle-button",
            "auto-drill-next.settings.profile",
            "auto-drill-next.settings.target-qout",
            "auto-drill-next.settings.experimental-liquid-boost",
            "auto-drill-next.settings.mixed-transport",
            "auto-drill-next.settings.heuristic-search"
        )) {
            assertTrue(english.contains("setting." + setting + ".name"), () -> "missing setting label: " + setting);
        }
    }

    @Test
    void profileSelectionCyclesThroughTheAvailableProfiles() {
        assertEquals(PlannerRequest.Profile.THROUGHPUT, AutoDrillNEXT.nextProfile(PlannerRequest.Profile.BALANCED));
        assertEquals(PlannerRequest.Profile.BALANCED, AutoDrillNEXT.nextProfile(PlannerRequest.Profile.LOW_COMPLEXITY));
    }
    @Test
    void exhaustiveSearchIsTheDefaultStrategy() {
        assertEquals(
            PlannerRequest.SearchStrategy.EXHAUSTIVE,
            AutoDrillNEXT.searchStrategy(false)
        );
        assertEquals(
            PlannerRequest.SearchStrategy.HEURISTIC,
            AutoDrillNEXT.searchStrategy(true)
        );
    }


    @Test
    void experimentalLiquidBoostDefaultsToSkippingTheLiquidPicker() {
        assertEquals(
            autodrillnext.ui.PlannerChoicePanel.Stage.EXIT,
            AutoDrillNEXT.nextStageAfterDrill(false)
        );
        assertEquals(
            autodrillnext.ui.PlannerChoicePanel.Stage.LIQUID,
            AutoDrillNEXT.nextStageAfterDrill(true)
        );
    }

    @Test
    void drillLiquidAndExitStagesUseSelectionPreviews() {
        assertTrue(AutoDrillNEXT.usesSelectionPreview(autodrillnext.ui.PlannerChoicePanel.Stage.DRILL));
        assertTrue(AutoDrillNEXT.usesSelectionPreview(autodrillnext.ui.PlannerChoicePanel.Stage.LIQUID));
        assertTrue(AutoDrillNEXT.usesSelectionPreview(autodrillnext.ui.PlannerChoicePanel.Stage.EXIT));
        assertFalse(AutoDrillNEXT.usesSelectionPreview(autodrillnext.ui.PlannerChoicePanel.Stage.REVIEW));
    }
    @Test
    void reviewStageKeepsTheCompletedPlanPreview() {
        assertTrue(AutoDrillNEXT.keepsPlanningOutline(autodrillnext.ui.PlannerChoicePanel.Stage.REVIEW));
        assertFalse(AutoDrillNEXT.keepsPlanningOutline(autodrillnext.ui.PlannerChoicePanel.Stage.EXIT));
    }

}
