package autodrillnext.ui;

import arc.Core;
import arc.graphics.Color;
import arc.graphics.g2d.Draw;
import arc.graphics.g2d.Lines;
import arc.struct.FloatSeq;
import autodrillnext.model.DrillCandidate;
import autodrillnext.model.MiningLayout;
import autodrillnext.model.PlanGraph;
import autodrillnext.model.PlanningProgress;
import autodrillnext.model.RoutingProgress;
import autodrillnext.model.TransportPlacement;
import autodrillnext.world.TileKey;
import mindustry.Vars;
import mindustry.graphics.Layer;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/** Main-thread world rendering with a bounded, worker-safe progress handoff. */
public final class PlanningOutlineOverlay {
    private static final Color ATTEMPT = Color.valueOf("ffc857");
    private static final Color BEST = Color.valueOf("63e6a2");
    private static final Color SEARCH = Color.valueOf("91b4cb");
    private static final Color DRILL = Color.valueOf("b8c2cc");

    private final Mailbox mailbox = new Mailbox();
    private final FloatSeq attemptedDrillLines = new FloatSeq();
    private final FloatSeq attemptedRouteLines = new FloatSeq();
    private final FloatSeq bestLines = new FloatSeq();
    private final FloatSeq frontierLines = new FloatSeq();
    private final FloatSeq focusLines = new FloatSeq();
    private PlanningProgress rendered;
    private List<TransportPlacement> attemptedPlacements;
    private String statusText = "";
    private String decisionText = "";

    /** Called on the main thread with a fresh request generation. */
    public void begin(int token) {
        clear();
        mailbox.begin(token);
    }

    /** Called by the planner worker; keeps the immutable value, not a copy or a queued UI task. */
    public void publish(int token, PlanningProgress progress) {
        mailbox.publish(token, progress);
    }
    /** Seals the completed frame against worker updates while preserving its world geometry for review. */
    public void finish() {
        update();
        mailbox.finish();
    }


    /** Called on the main thread on completion, cancellation, or a world/context change. */
    public void clear() {
        mailbox.clear();
        rendered = null;
        attemptedPlacements = null;
        attemptedDrillLines.clear();
        attemptedRouteLines.clear();
        bestLines.clear();
        frontierLines.clear();
        focusLines.clear();
        statusText = "";
        decisionText = "";
    }

    /** Main-thread polling; both the native labels and world drawing consume the newest frame. */
    public void update() {
        PlanningProgress progress = mailbox.latest();
        if (progress == null || progress == rendered) return;
        if (rendered == null || progress.attempted() != rendered.attempted()) {
            geometry(attemptedDrillLines, progress.attempted(), null);
        }
        RoutingProgress routing = progress.routing();
        List<TransportPlacement> placements = routing != null ? routing.placements()
            : progress.route() == null ? List.of() : progress.route().placements();
        if (attemptedPlacements != placements) {
            attemptedRouteLines.clear();
            transportGeometry(attemptedRouteLines, placements);
            attemptedPlacements = placements;
        }
        if (rendered == null || progress.bestRoute() != rendered.bestRoute()) {
            bestLines.clear();
            if (progress.bestRoute() != null) transportGeometry(bestLines, progress.bestRoute().placements());
        }
        RoutingProgress previousRouting = rendered == null ? null : rendered.routing();
        if (previousRouting == null || routing == null || routing.frontier() != previousRouting.frontier()) {
            frontierLines.clear();
            if (routing != null) {
                for (TileKey tile : routing.frontier()) searchMarker(frontierLines, tile, Vars.tilesize * 0.18f);
            }
        }
        if (previousRouting == null || routing == null || !Objects.equals(routing.focus(), previousRouting.focus())) {
            focusLines.clear();
            if (routing != null && routing.focus() != null) {
                searchMarker(focusLines, routing.focus(), Vars.tilesize * 0.48f);
            }
        }
        statusText = status(progress);
        if (rendered == null || progress.decisions() != rendered.decisions()) {
            StringBuilder history = new StringBuilder();
            for (PlanningProgress.DecisionEvent event : progress.decisions()) {
                if (!history.isEmpty()) history.append('\n');
                history.append(Core.bundle.format("auto-drill-next.ui.decision-entry", Long.toString(event.sequence()),
                    Core.bundle.get("auto-drill-next.ui.decision." + key(event.decision())),
                    decimal(event.qOut()), decimal(event.materialCost()), Integer.toString(event.transportBlocks())));
            }
            decisionText = history.toString();
        }
        rendered = progress;
    }

    public String statusText() {
        return statusText;
    }

    public String decisionText() {
        return decisionText;
    }

    private static String status(PlanningProgress progress) {
        PlanningProgress.Counters counts = progress.counters();
        String stage = Core.bundle.get("auto-drill-next.ui.stage." + key(progress.stage()));
        if (progress.stage() == PlanningProgress.Stage.CERTIFYING
            && progress.bestRoute() != null) {
            stage = Core.bundle.get("auto-drill-next.ui.current-best-running");
        }
        RoutingProgress routing = progress.routing();
        if (routing != null) {
            stage = Core.bundle.format("auto-drill-next.ui.routing-status", stage,
                Core.bundle.get("auto-drill-next.ui.routing." + key(routing.phase())),
                Integer.toString(routing.connectedSources()), Integer.toString(routing.totalSources()));
            if (routing.removedBlocks() > 0) {
                stage = Core.bundle.format("auto-drill-next.ui.routing-removed", stage, Integer.toString(routing.removedBlocks()));
            }
        }
        return Core.bundle.format(
            "auto-drill-next.ui.search-status",
            stage,
            Long.toString(counts.layoutsTried()),
            Long.toString(counts.routesTried()),
            Long.toString(counts.exploredStates()),
            Long.toString(counts.prunedStates()),
            Long.toString(counts.pendingStates()),
            Long.toString(counts.accepted()),
            Long.toString(counts.rejected())
        );
    }

    private static String key(Enum<?> value) {
        return value.name().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    private static String decimal(double value) {
        return Double.isFinite(value)
            ? BigDecimal.valueOf(value).round(MathContext.DECIMAL32).stripTrailingZeros().toPlainString() : "—";
    }

    private static void searchMarker(FloatSeq lines, TileKey tile, float radius) {
        float x = tile.x() * Vars.tilesize, y = tile.y() * Vars.tilesize;
        line(lines, x, y + radius, x + radius, y);
        line(lines, x + radius, y, x, y - radius);
        line(lines, x, y - radius, x - radius, y);
        line(lines, x - radius, y, x, y + radius);
    }

    /** Registered with Trigger.draw, whose projection is already in world coordinates. */
    public void draw() {
        if (Vars.state == null || Vars.state.isMenu()) {
            clear();
            return;
        }
        if (Core.camera == null) return;
        update();
        if (rendered == null) return;
        float pixel = Core.graphics == null || Core.graphics.getWidth() <= 0
            ? 1f : Core.camera.width / Core.graphics.getWidth();

        float z = Draw.z();
        float color = Draw.getColorPacked();
        float mix = Draw.getMixColorPacked();
        float stroke = Lines.getStroke();
        try {
            Draw.z(Layer.overlayUI);
            Draw.mixcol();
            drawLines(frontierLines, SEARCH, 0.9f * pixel);
            // Route colors carry search meaning; muted drill footprints are reference geometry only.
            drawLines(bestLines, BEST, 2.6f * pixel);
            drawLines(attemptedDrillLines, DRILL, 0.85f * pixel);
            drawLines(attemptedRouteLines, ATTEMPT, 1.4f * pixel);
            drawLines(focusLines, SEARCH, 2f * pixel);
        } finally {
            Lines.stroke(stroke);
            Draw.color(color);
            Draw.mixcol(mix);
            Draw.z(z);
        }
    }

    private static void drawLines(FloatSeq lines, Color color, float stroke) {
        Draw.color(color);
        Lines.stroke(stroke);
        float minX = Core.camera.position.x - Core.camera.width / 2f - stroke;
        float maxX = Core.camera.position.x + Core.camera.width / 2f + stroke;
        float minY = Core.camera.position.y - Core.camera.height / 2f - stroke;
        float maxY = Core.camera.position.y + Core.camera.height / 2f + stroke;
        for (int i = 0; i < lines.size; i += 4) {
            float x = lines.items[i], y = lines.items[i + 1];
            float toX = lines.items[i + 2], toY = lines.items[i + 3];
            if (Math.max(x, toX) < minX || Math.min(x, toX) > maxX
                || Math.max(y, toY) < minY || Math.min(y, toY) > maxY) continue;
            Lines.line(x, y, toX, toY);
        }
    }

    // Geometry is refreshed only for a newly observed snapshot, never on unchanged draw frames.
    private static void geometry(FloatSeq lines, MiningLayout layout, PlanGraph route) {
        lines.clear();
        if (layout != null) {
            for (DrillCandidate candidate : layout.candidates()) {
                Set<TileKey> cells = candidate.footprint().tiles();
                footprint(lines, cells);
                int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE;
                int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE;
                for (TileKey tile : cells) {
                    minX = Math.min(minX, tile.x());
                    minY = Math.min(minY, tile.y());
                    maxX = Math.max(maxX, tile.x());
                    maxY = Math.max(maxY, tile.y());
                }
                direction(lines, (minX + maxX) * Vars.tilesize / 2f,
                    (minY + maxY) * Vars.tilesize / 2f, candidate.rotation());
            }
        }
        if (route != null) transportGeometry(lines, route.placements());
    }

    private static void transportGeometry(FloatSeq lines, List<TransportPlacement> placements) {
        for (TransportPlacement placement : placements) {
            float x = placement.tile().x() * Vars.tilesize;
            float y = placement.tile().y() * Vars.tilesize;
            float low = (placement.spec().size() - 1) / 2;
            float left = x - (low + 0.5f) * Vars.tilesize;
            float bottom = y - (low + 0.5f) * Vars.tilesize;
            float side = placement.spec().size() * Vars.tilesize;
            line(lines, left, bottom, left + side, bottom);
            line(lines, left + side, bottom, left + side, bottom + side);
            line(lines, left + side, bottom + side, left, bottom + side);
            line(lines, left, bottom + side, left, bottom);
            TileKey output = placement.bridgeLink() == null ? placement.output() : placement.bridgeLink();
            float toX = output.x() * Vars.tilesize, toY = output.y() * Vars.tilesize;
            if (x != toX || y != toY) {
                line(lines, x, y, toX, toY);
                arrow(lines, x, y, toX, toY);
            }
        }
    }

    private static void footprint(FloatSeq lines, Set<TileKey> cells) {
        float half = Vars.tilesize / 2f;
        for (TileKey tile : cells) {
            float x = tile.x() * Vars.tilesize, y = tile.y() * Vars.tilesize;
            if (!cells.contains(new TileKey(tile.x() - 1, tile.y()))) line(lines, x - half, y - half, x - half, y + half);
            if (!cells.contains(new TileKey(tile.x() + 1, tile.y()))) line(lines, x + half, y - half, x + half, y + half);
            if (!cells.contains(new TileKey(tile.x(), tile.y() - 1))) line(lines, x - half, y - half, x + half, y - half);
            if (!cells.contains(new TileKey(tile.x(), tile.y() + 1))) line(lines, x - half, y + half, x + half, y + half);
        }
    }

    private static void direction(FloatSeq lines, float x, float y, int rotation) {
        float dx = rotation == 0 ? 1f : rotation == 2 ? -1f : 0f;
        float dy = rotation == 1 ? 1f : rotation == 3 ? -1f : 0f;
        float radius = Vars.tilesize * 0.3f;
        line(lines, x - dx * radius, y - dy * radius, x + dx * radius, y + dy * radius);
        arrow(lines, x - dx * radius, y - dy * radius, x + dx * radius, y + dy * radius);
    }

    private static void arrow(FloatSeq lines, float x, float y, float toX, float toY) {
        float dx = toX - x, dy = toY - y;
        float length = (float)Math.sqrt(dx * dx + dy * dy);
        dx /= length;
        dy /= length;
        float tipX = x + (toX - x) * 0.65f, tipY = y + (toY - y) * 0.65f;
        float head = Math.min(Vars.tilesize * 0.3f, length * 0.4f);
        line(lines, tipX, tipY, tipX - dx * head - dy * head * 0.65f, tipY - dy * head + dx * head * 0.65f);
        line(lines, tipX, tipY, tipX - dx * head + dy * head * 0.65f, tipY - dy * head - dx * head * 0.65f);
    }

    private static void line(FloatSeq lines, float x, float y, float toX, float toY) {
        lines.add(x, y);
        lines.add(toX, toY);
    }

    /** A single-slot handoff: generation checks and replacement are one atomic operation. */
    static final class Mailbox {
        private int generation;
        private boolean active;
        private PlanningProgress latest;

        synchronized void begin(int token) {
            generation = token;
            active = true;
            latest = null;
        }

        synchronized void publish(int token, PlanningProgress progress) {
            if (active && generation == token) latest = Objects.requireNonNull(progress, "progress");
        }
        synchronized void finish() {
            active = false;
        }


        synchronized void clear() {
            active = false;
            latest = null;
        }

        synchronized PlanningProgress latest() {
            return latest;
        }
    }
}
