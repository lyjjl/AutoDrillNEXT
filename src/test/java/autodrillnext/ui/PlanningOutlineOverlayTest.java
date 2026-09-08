package autodrillnext.ui;

import arc.Core;
import arc.files.Fi;
import arc.util.I18NBundle;
import autodrillnext.model.MiningLayout;
import autodrillnext.model.PlanningProgress;
import autodrillnext.model.RoutingProgress;
import autodrillnext.world.TileKey;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlanningOutlineOverlayTest {
    @Test
    void supersededWorkerCannotReplaceCurrentGeneration() {
        PlanningOutlineOverlay.Mailbox mailbox = new PlanningOutlineOverlay.Mailbox();
        PlanningProgress previous = frame();
        PlanningProgress current = frame();
        mailbox.begin(1);
        mailbox.publish(1, previous);
        mailbox.begin(2);
        assertNull(mailbox.latest());
        mailbox.publish(2, current);
        mailbox.publish(1, previous);
        assertSame(current, mailbox.latest());
    }

    @Test
    void clearedWorkerCannotRestoreItsFrameEvenAfterAnotherRequestBegins() {
        PlanningOutlineOverlay.Mailbox mailbox = new PlanningOutlineOverlay.Mailbox();
        PlanningProgress cancelled = frame();
        mailbox.begin(1);
        mailbox.publish(1, cancelled);
        mailbox.clear();
        mailbox.publish(1, cancelled);
        assertNull(mailbox.latest());
        mailbox.begin(2);
        mailbox.publish(1, cancelled);
        assertNull(mailbox.latest());
        PlanningProgress current = frame();
        mailbox.publish(2, current);
        assertSame(current, mailbox.latest());
    }

    @Test
    void completedPlanRemainsVisibleWhileLateWorkerFramesAreRejected() {
        PlanningOutlineOverlay.Mailbox mailbox = new PlanningOutlineOverlay.Mailbox();
        PlanningProgress completed = frame();
        mailbox.begin(3);
        mailbox.publish(3, completed);

        mailbox.finish();
        mailbox.publish(3, frame());

        assertSame(completed, mailbox.latest());
    }

    @Test
    void drawingReadsOnlyTheLatestFrameWithoutReplayingEarlierAttempts() {
        PlanningOutlineOverlay.Mailbox mailbox = new PlanningOutlineOverlay.Mailbox();
        PlanningProgress first = frame();
        PlanningProgress last = frame();
        mailbox.begin(7);
        mailbox.publish(7, first);
        mailbox.publish(7, frame());
        mailbox.publish(7, last);
        assertSame(last, mailbox.latest());
        assertSame(last, mailbox.latest());
    }

    @Test
    void latestSearchKeepsRecentDecisionsAndClearRemovesTheirVisibleStatus() {
        I18NBundle previousBundle = Core.bundle;
        Core.bundle = I18NBundle.createBundle(new Fi("assets/bundles/bundle"), Locale.ROOT);
        try {
            PlanningOutlineOverlay overlay = new PlanningOutlineOverlay();
            overlay.begin(8);
            var first = new PlanningProgress.DecisionEvent(1, PlanningProgress.Decision.NO_ROUTE, 0, 0, 0);
            var second = new PlanningProgress.DecisionEvent(2, PlanningProgress.Decision.NEW_BEST, 12.5, 3.25, 4);
            overlay.publish(8, progress(List.of(first), 23));
            overlay.publish(8, progress(List.of(first, second), 47));
            overlay.update();
            String history = overlay.decisionText();
            assertTrue(history.indexOf(Core.bundle.get("auto-drill-next.ui.decision.no-route"))
                < history.indexOf(Core.bundle.get("auto-drill-next.ui.decision.new-best")));
            assertTrue(history.contains(Core.bundle.get("auto-drill-next.ui.decision.no-route")));
            assertTrue(history.contains("12.5"));
            assertTrue(history.contains("3.25"));
            assertTrue(overlay.statusText().contains("47"));
            assertFalse(overlay.statusText().contains("23"));
            overlay.clear();
            overlay.publish(8, progress(List.of(first, second), 99));
            overlay.update();
            assertEquals("", overlay.statusText());
            assertEquals("", overlay.decisionText());
            overlay.begin(9);
            overlay.publish(8, progress(List.of(first), 100));
            overlay.update();
            assertEquals("", overlay.statusText());
            assertEquals("", overlay.decisionText());
        } finally {
            Core.bundle = previousBundle;
        }
    }
    @Test
    void statusShowsCertificationQueueCounts() {
        I18NBundle previousBundle = Core.bundle;
        Core.bundle = I18NBundle.createBundle(new Fi("assets/bundles/bundle"), Locale.ROOT);
        try {
            PlanningOutlineOverlay overlay = new PlanningOutlineOverlay();
            overlay.begin(9);
            overlay.publish(9, new PlanningProgress(
                MiningLayout.of(List.of()), null, null, null, null,
                PlanningProgress.Stage.CERTIFYING,
                new PlanningProgress.Counters(2, 1, 9, 5, 7, 1, 3),
                List.of()
            ));
            overlay.update();

            assertTrue(overlay.statusText().contains("9"));
            assertTrue(overlay.statusText().contains("5"));
            assertTrue(overlay.statusText().contains("7"));
        } finally {
            Core.bundle = previousBundle;
        }
    }


    private static PlanningProgress progress(List<PlanningProgress.DecisionEvent> decisions, long expanded) {
        RoutingProgress routing = new RoutingProgress(RoutingProgress.Phase.SEARCHING, List.of(),
            List.of(new TileKey(1, 2)), new TileKey(2, 2), expanded, 1, 2, null, 0);
        return new PlanningProgress(MiningLayout.of(List.of()), null, null, null, routing,
            PlanningProgress.Stage.ROUTING,
            new PlanningProgress.Counters(2, 3, expanded, 0, 0, 1, 1),
            decisions);
    }

    private static PlanningProgress frame() {
        return new PlanningProgress(MiningLayout.of(List.of()), null, null, null, null,
            PlanningProgress.Stage.LAYOUT,
            new PlanningProgress.Counters(0, 0, 0, 0, 0, 0, 0),
            List.of());
    }
}
