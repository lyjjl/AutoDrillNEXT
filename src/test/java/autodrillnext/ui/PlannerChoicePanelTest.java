package autodrillnext.ui;

import autodrillnext.model.OptimalityCertificate;
import autodrillnext.model.PlannerResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlannerChoicePanelTest {
    @Test
    void reviewShowsBuildActionOnlyForCompileReadyResult() {
        assertTrue(PlannerChoicePanel.shouldShowBuildAction(result(true)));
        assertFalse(PlannerChoicePanel.shouldShowBuildAction(result(false)));
    }


    private PlannerResult result(boolean compileReady) {
        return new PlannerResult(
            compileReady,
            null,
            null,
            null,
            null,
            null,
            null,
            List.of(),
            null,
            null,
            null,
            List.of(),
            List.of(),
            null,
            OptimalityCertificate.none()
        );
    }
}
