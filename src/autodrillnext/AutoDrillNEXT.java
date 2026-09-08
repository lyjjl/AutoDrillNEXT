package autodrillnext;

import arc.Core;
import arc.Events;
import arc.input.KeyCode;
import arc.scene.event.InputEvent;
import arc.scene.event.InputListener;
import arc.scene.style.TextureRegionDrawable;
import arc.scene.ui.ImageButton;
import arc.scene.ui.ScrollPane;
import arc.scene.ui.layout.Table;
import arc.util.Timer;
import autodrillnext.mindustryapi.BuildPlanFacade;
import autodrillnext.model.ContentId;
import autodrillnext.model.PlannerRequest;
import autodrillnext.model.PlannerDiagnostic;
import autodrillnext.model.PlannerResult;
import autodrillnext.ui.ExitArrowOverlay;
import autodrillnext.ui.ExitSelector12;
import autodrillnext.ui.PlannerDiagnostics;
import autodrillnext.ui.PlannerChoicePanel;
import autodrillnext.ui.PlanningOutlineOverlay;
import autodrillnext.world.ExitPort;
import arc.scene.ui.Button;
import arc.scene.ui.Label;
import autodrillnext.world.OrePatch;
import autodrillnext.world.TileKey;
import autodrillnext.world.OreSelector;
import mindustry.Vars;
import mindustry.world.Tile;
import mindustry.game.EventType;
import mindustry.mod.Mod;
import mindustry.ui.Styles;
import mindustry.ui.dialogs.SettingsMenuDialog;
import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.Future;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static arc.Core.bundle;
import static mindustry.Vars.state;
import static mindustry.Vars.ui;

public final class AutoDrillNEXT extends Mod {
    private static final int BUTTON_SIZE = 30;
    private static final String ACTIVATION_KEY = "auto-drill-next.settings.activation-key";
    private static final String DISPLAY_BUTTON = "auto-drill-next.settings.display-toggle-button";
    private static final String PROFILE = "auto-drill-next.settings.profile";
    private static final String TARGET_QOUT = "auto-drill-next.settings.target-qout";
    private static final String EXPERIMENTAL_LIQUID_BOOST = "auto-drill-next.settings.experimental-liquid-boost";
    private static final String MIXED_TRANSPORT = "auto-drill-next.settings.mixed-transport";
    private static final String HEURISTIC_SEARCH = "auto-drill-next.settings.heuristic-search";
    private static final ExecutorService PLANNER_EXECUTOR = Executors.newSingleThreadExecutor(runnable -> {
        Thread worker = new Thread(runnable, "auto-drill-next-planner");
        worker.setDaemon(true);
        return worker;
    });

    private final LocalMiningPlanner planner = new LocalMiningPlanner();
    private final BuildPlanFacade buildPlans = new BuildPlanFacade();
    private final ExitSelector12 exitSelector = new ExitSelector12();
    private final PlannerChoicePanel choicePanel = new PlannerChoicePanel();
    private final ExitArrowOverlay exitOverlay = new ExitArrowOverlay(exitSelector);
    private final PlanningOutlineOverlay planningOutline = new PlanningOutlineOverlay();
    private final PlannerDiagnostics diagnostics = new PlannerDiagnostics();
    private final Table plannerTable = choicePanel.table();
    private final Table exitTable = exitOverlay.table();

    private boolean enabled;
    private boolean planning;
    private int planGeneration;
    private Timer.Task pendingPlan;
    private Future<?> runningPlan;
    private TileKey selectedTile;
    private PlannerChoicePanel.Stage stage = PlannerChoicePanel.Stage.DRILL;
    private PlannerResult current;
    private PlannerRequest currentRequest;
    private String selectedDrillId;
    private String selectedLiquidId = "";
    private ExitPort.Side exitSide = ExitPort.Side.RIGHT;
    private ExitPort.Bias exitBias = ExitPort.Bias.CENTER;
    private ImageButton enableButton;
    private Label planningStatus;
    private Label planningHistory;
    private ScrollPane planningHistoryPane;
    private String displayedPlanningStatus = "";
    private String displayedPlanningHistory = "";

    @Override
    public void init() {
        initializeSettingsDefaults();
        installSettings();
        installInput();
        installTapHandler();
        installHudButton();
        installPlannerTables();
        Events.run(EventType.Trigger.draw, planningOutline::draw);
        Events.on(EventType.WorldLoadBeginEvent.class, event -> closePlanner());
        Events.on(EventType.WorldLoadEvent.class, event -> closePlanner());
        Events.on(EventType.StateChangeEvent.class, event -> {
            if (event.to == mindustry.core.GameState.State.menu) closePlanner();
        });
    }

    private void initializeSettingsDefaults() {
        Core.settings.defaults(
            ACTIVATION_KEY, KeyCode.h.name().toUpperCase(Locale.ROOT),
            DISPLAY_BUTTON, true,
            PROFILE, PlannerRequest.Profile.BALANCED.name(),
            TARGET_QOUT, "0",
            EXPERIMENTAL_LIQUID_BOOST, false,
            MIXED_TRANSPORT, false,
            HEURISTIC_SEARCH, false
        );
    }


    private void installSettings() {
        ConsSettings builder = new ConsSettings();
        ui.settings.getCategories().add(new SettingsMenuDialog.SettingsCategory(
            bundle.get("auto-drill-next.settings.title"),
            new TextureRegionDrawable(Core.atlas.find("auto-drill-next-logo")),
            builder::build
        ));
    }

    private void installInput() {
        Core.scene.addListener(new InputListener() {
            @Override
            public boolean keyDown(InputEvent event, KeyCode keyCode) {
                if (state.isMenu() || ui.chatfrag.shown() || ui.schematics.isShown()
                    || ui.database.isShown() || ui.consolefrag.shown() || ui.content.isShown()
                    || Core.scene.hasKeyboard()) return false;
                if (activationKeyMatches(Core.settings.getString(ACTIVATION_KEY), keyCode)) toggle();
                return false;
            }
        });
    }

    static boolean activationKeyMatches(String configured, KeyCode pressed) {
        String expected = configured == null || configured.isBlank()
            ? KeyCode.h.value
            : configured;
        return pressed != null && pressed.value.equalsIgnoreCase(expected);
    }

    private void installTapHandler() {
        Events.on(EventType.TapEvent.class, event -> {
            if (!enabled || event.tile == null || !selectableOre(event.tile)) return;
            selectedTile = new TileKey(event.tile.x, event.tile.y);
            selectedDrillId = null;
            selectedLiquidId = "";
            exitSide = ExitPort.Side.RIGHT;
            exitBias = ExitPort.Bias.CENTER;
            schedulePlan(PlannerChoicePanel.Stage.DRILL);
        });
    }

    private static boolean selectableOre(Tile tile) {
        ContentId floorOre = tile.drop() == null ? null : ContentId.of(tile.drop().name);
        ContentId wallOre = tile.wallDrop() == null ? null : ContentId.of(tile.wallDrop().name);
        return OreSelector.selectableOre(floorOre, wallOre) != null;
    }

    private void installHudButton() {
        ui.hudGroup.fill(table -> {
            enableButton = table.button(
                new TextureRegionDrawable(Core.atlas.find("auto-drill-next-logo")),
                Styles.emptyTogglei,
                this::toggle
            ).get();
            enableButton.resizeImage(BUTTON_SIZE);
            enableButton.visible(() -> Core.settings.getBool(DISPLAY_BUTTON));
            table.margin(5f).marginRight(155f).top().right();
        });
    }

    private void installPlannerTables() {
        plannerTable.visible = false;
        exitTable.visible = false;
        Core.scene.root.addChild(exitTable);
        Core.scene.root.addChild(plannerTable);
        exitTable.update(this::positionTables);
        plannerTable.update(() -> {
            updatePlanningProgress();
            positionTables();
        });
    }

    private void addPlanningProgress() {
        float width = Math.min(340f, Core.scene.getWidth() - 28f);
        planningStatus = plannerTable.labelWrap("").width(width).left().padTop(6f).get();
        plannerTable.row();
        plannerTable.labelWrap(bundle.get("auto-drill-next.ui.outline-legend")).width(width).left().padTop(6f).row();
        plannerTable.add(bundle.get("auto-drill-next.ui.recent-decisions")).left().padTop(8f).row();
        planningHistory = new Label("");
        planningHistory.setWrap(true);
        planningHistory.setAlignment(arc.util.Align.topLeft);
        planningHistoryPane = new ScrollPane(planningHistory, Styles.smallPane);
        planningHistoryPane.setScrollingDisabled(true, false);
        planningHistoryPane.setFadeScrollBars(false);
        planningHistoryPane.setSmoothScrolling(false);
        plannerTable.add(planningHistoryPane).width(width).height(Math.min(120f, Core.scene.getHeight() * 0.2f)).left().row();
        plannerTable.pack();
    }

    private void updatePlanningProgress() {
        if (planningStatus == null) return;
        planningOutline.update();
        String status = planningOutline.statusText();
        String history = planningOutline.decisionText();
        boolean changed = false;
        if (status != displayedPlanningStatus) {
            planningStatus.setText(status);
            displayedPlanningStatus = status;
            changed = true;
        }
        if (history != displayedPlanningHistory) {
            boolean followLatest = displayedPlanningHistory.isEmpty() || planningHistoryPane.getScrollPercentY() >= 0.99f;
            planningHistory.setText(history);
            displayedPlanningHistory = history;
            planningHistoryPane.layout();
            if (followLatest) planningHistoryPane.setScrollPercentY(1f);
            changed = true;
        }
        if (changed) plannerTable.pack();
    }

    private void clearPlanningProgress() {
        planningOutline.clear();
        detachPlanningProgress();
    }

    private void finishPlanningProgress() {
        planningOutline.finish();
        detachPlanningProgress();
    }

    private void detachPlanningProgress() {
        if (planningStatus != null) planningStatus.setText("");
        if (planningHistory != null) planningHistory.setText("");
        planningStatus = null;
        planningHistory = null;
        planningHistoryPane = null;
        displayedPlanningStatus = "";
        displayedPlanningHistory = "";
    }

    private void schedulePlan(PlannerChoicePanel.Stage nextStage) {
        if (selectedTile == null) return;
        if (pendingPlan != null) pendingPlan.cancel();
        if (runningPlan != null) {
            runningPlan.cancel(true);
            runningPlan = null;
        }
        clearPlanningProgress();
        int token = ++planGeneration;
        planningOutline.begin(token);
        stage = nextStage;
        planning = true;
        exitOverlay.hide();
        choicePanel.showLoading(nextStage, this::closePlanner);
        if (!usesSelectionPreview(nextStage)) {
            addPlanningProgress();
        }
        positionTables();
        pendingPlan = Timer.schedule(
            () -> Core.app.post(() -> runPlan(token, nextStage)),
            0.08f
        );
    }

    private void runPlan(int token, PlannerChoicePanel.Stage targetStage) {
        if (token != planGeneration || !enabled || selectedTile == null) return;
        PlannerRequest planRequest = request(selectedTile);
        currentRequest = planRequest;
        LocalMiningPlanner.PlanningSnapshot snapshot;
        try {
            snapshot = planner.capture(planRequest);
        } catch (RuntimeException failure) {
            applyPlan(token, targetStage, null, failure);
            return;
        }
        if (usesSelectionPreview(targetStage)) {
            applyPlan(token, targetStage, planner.drillSelection(planRequest, snapshot), null);
            return;
        }
        String drillId = selectedDrillId;
        String liquidId = selectedLiquidId;
        runningPlan = PLANNER_EXECUTOR.submit(() -> {
            try {
                PlannerResult result = planner.plan(planRequest, snapshot, drillId, liquidId,
                    progress -> planningOutline.publish(token, progress));
                Core.app.post(() -> applyPlan(token, targetStage, result, null));
            } catch (java.util.concurrent.CancellationException cancelled) {
                Core.app.post(() -> {
                    if (token == planGeneration) closePlanner();
                });
            } catch (Throwable failure) {
                Core.app.post(() -> applyPlan(token, targetStage, null, failure));
            }
        });
    }

    private void applyPlan(
        int token,
        PlannerChoicePanel.Stage targetStage,
        PlannerResult result,
        Throwable failure
    ) {
        if (token != planGeneration || !enabled || selectedTile == null) return;
        runningPlan = null;
        if (failure != null || result == null) {
            clearPlanningProgress();
            finishFailedPlan(bundle.get("auto-drill-next.ui.plan-error"), result == null ? null : result.patch());
            return;
        }
        if (keepsPlanningOutline(targetStage)) {
            finishPlanningProgress();
        } else {
            clearPlanningProgress();
        }
        current = result;
        planning = false;
        showStage(result, targetStage);
    }

    private void showStage(PlannerResult result, PlannerChoicePanel.Stage nextStage) {
        stage = nextStage;
        if (nextStage == PlannerChoicePanel.Stage.EXIT) {
            choicePanel.hide();
            if (result.patch() != null) {
                exitOverlay.show(
                    result.patch(),
                    exitSide,
                    exitBias,
                    this::selectExit,
                    this::closePlanner
                );
            } else {
                choicePanel.showError(buildFailureMessage(result), this::closePlanner);
            }
        } else {
            choicePanel.show(
                result,
                nextStage,
                selectedDrillId,
                selectedLiquidId,
                this::selectDrill,
                this::selectLiquid,
                this::commitCurrent,
                this::closePlanner
            );
            exitOverlay.hide();
        }
        positionTables();
    }

    private String buildFailureMessage(PlannerResult result) {
        PlannerDiagnostic diagnostic = diagnostics.primaryFailure(result.diagnostics());
        if (diagnostic == null) return bundle.get("auto-drill-next.ui.build-blocked");
        String message = diagnostics.resolve(diagnostic);
        String tile = diagnostic.arguments().get("tile");
        String reason = diagnostic.arguments().get("reason");
        if (tile != null && reason != null) {
            return bundle.format(
                "auto-drill-next.ui.build-blocked-detail",
                message,
                tile,
                bundle.get("auto-drill-next.reason." + reason)
            );
        }
        return bundle.format("auto-drill-next.ui.build-blocked-reason", message);
    }

    private void finishFailedPlan(String message, OrePatch patch) {
        float x = patch == null ? selectedTile.x() * Vars.tilesize
            : (patch.minX() + patch.maxX()) * 0.5f * Vars.tilesize;
        float y = patch == null ? (selectedTile.y() + 2) * Vars.tilesize
            : (patch.maxY() + 2) * Vars.tilesize;
        closePlanner();
        ui.showLabel(message, -1, 5f, x, y,
            mindustry.gen.WorldLabel.flagBackground | mindustry.gen.WorldLabel.flagOutline);
    }

    private PlannerRequest request(TileKey seed) {
        OrePatch previewPatch = OrePatch.of(
            autodrillnext.model.ContentId.of("preview"),
            seed,
            java.util.Set.of(new autodrillnext.world.TileOffset(0, 0))
        );
        OrePatch patch = current != null && current.patch() != null && current.patch().origin().equals(seed)
            ? current.patch() : previewPatch;
        ExitPort exit = exitSelector.select(patch, exitSide, exitBias);
        return new PlannerRequest(
            seed,
            planner.playerTeamId(),
            exit,
            profile(),
            searchStrategy(Core.settings.getBool(HEURISTIC_SEARCH)),
            targetQout(),
            1.10f,
            planner.infiniteResources()
                ? PlannerRequest.BudgetMode.INFINITE_RESOURCES
                : PlannerRequest.BudgetMode.CURRENT_INVENTORY,
            false,
            4096,
            1000,
            Core.settings.getBool(MIXED_TRANSPORT)
        );
    }
    static boolean keepsPlanningOutline(PlannerChoicePanel.Stage stage) {
        return stage == PlannerChoicePanel.Stage.REVIEW;
    }


    private void selectDrill(String drillId) {
        if (stage != PlannerChoicePanel.Stage.DRILL || drillId == null) return;
        selectedDrillId = drillId;
        selectedLiquidId = "";
        schedulePlan(nextStageAfterDrill(Core.settings.getBool(EXPERIMENTAL_LIQUID_BOOST)));
    }

    private void selectLiquid(String liquidId) {
        if (stage != PlannerChoicePanel.Stage.LIQUID) return;
        selectedLiquidId = liquidId == null ? "" : liquidId;
        schedulePlan(PlannerChoicePanel.Stage.EXIT);
    }

    private void selectExit(ExitPort exit) {
        if (stage != PlannerChoicePanel.Stage.EXIT) return;
        exitSide = exit.side();
        exitBias = exit.bias();
        schedulePlan(PlannerChoicePanel.Stage.REVIEW);
    }

    private PlannerRequest.Profile profile() {
        String value = Core.settings.getString(PROFILE);
        if (value == null || value.isBlank()) return PlannerRequest.Profile.BALANCED;
        try {
            return PlannerRequest.Profile.valueOf(value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return PlannerRequest.Profile.BALANCED;
        }
    }

    private float targetQout() {
        String stored = Core.settings.getString(TARGET_QOUT);
        if (stored == null || stored.isBlank()) return 0f;
        try {
            float value = Float.parseFloat(stored);
            return Float.isFinite(value) && value >= 0f ? value : 0f;
        } catch (NumberFormatException ignored) {
            return 0f;
        }
    }

    private void positionTables() {
        if (selectedTile == null || Vars.world == null || Core.camera == null) return;
        arc.math.geom.Vec2 projected = Core.camera.project(
            (selectedTile.x() + 0.5f) * Vars.tilesize,
            (selectedTile.y() + 0.5f) * Vars.tilesize
        );
        float screenX = projected.x;
        float screenY = projected.y;
        float sceneWidth = Core.scene == null ? Float.POSITIVE_INFINITY : Core.scene.getWidth();
        float sceneHeight = Core.scene == null ? Float.POSITIVE_INFINITY : Core.scene.getHeight();
        float panelX = screenX + 112f;
        float panelY = screenY - plannerTable.getHeight() / 2f;
        panelX = Math.max(8f, Math.min(panelX, sceneWidth - plannerTable.getWidth() - 8f));
        panelY = Math.max(8f, Math.min(panelY, sceneHeight - plannerTable.getHeight() - 8f));
        plannerTable.setPosition(panelX, panelY);
        if (current != null && current.patch() != null && stage == PlannerChoicePanel.Stage.EXIT && !planning) {
            exitOverlay.update(current.patch());
        }
    }

    private boolean commitCurrent() {
        if (current == null || !current.compileReady()) return false;
        try {
            if (currentRequest == null || !currentRequest.teamId().equals(planner.playerTeamId())
                || (currentRequest.budgetMode() == PlannerRequest.BudgetMode.INFINITE_RESOURCES) != planner.infiniteResources()) {
                throw new IllegalStateException("planning context changed");
            }
            planner.validateSubmission(currentRequest, planner.capture(currentRequest), current, selectedLiquidId);
            buildPlans.submit(current.compileRecords());
        } catch (IllegalArgumentException | IllegalStateException changed) {
            finishFailedPlan(diagnostics.resolve(PlannerDiagnostic.of(
                autodrillnext.model.DiagnosticCode.STALE_SNAPSHOT)), current.patch());
            return true;
        }
        ui.showInfoFade(bundle.get("auto-drill-next.ui.build-queued"));
        closePlanner();
        return true;
    }

    private void closePlanner() {
        if (pendingPlan != null) {
            pendingPlan.cancel();
            pendingPlan = null;
        }
        if (runningPlan != null) {
            runningPlan.cancel(true);
            runningPlan = null;
        }
        planGeneration++;
        clearPlanningProgress();
        planning = false;
        stage = PlannerChoicePanel.Stage.DRILL;
        selectedTile = null;
        selectedDrillId = null;
        selectedLiquidId = "";
        current = null;
        currentRequest = null;
        exitOverlay.hide();
        choicePanel.hide();
    }

    private void toggle() {
        enabled = !enabled;
        if (enableButton != null) enableButton.setChecked(enabled);
        if (!enabled) closePlanner();
    }

    private static final class ConsSettings {
        void build(SettingsMenuDialog.SettingsTable table) {
            SettingsMenuDialog.SettingsTable settings = new SettingsMenuDialog.SettingsTable();
            settings.pref(new DescriptionSetting(bundle.get("auto-drill-next.settings.activation-desc")));
            settings.textPref(ACTIVATION_KEY, KeyCode.h.name().toUpperCase(Locale.ROOT), value -> {
                KeyCode key = Arrays.stream(KeyCode.values())
                    .filter(candidate -> candidate.value.equalsIgnoreCase(value)).findFirst().orElse(KeyCode.h);
                Core.settings.put(ACTIVATION_KEY, key.name().toUpperCase(Locale.ROOT));
            });
            settings.checkPref(DISPLAY_BUTTON, true);
            settings.pref(new DescriptionSetting(bundle.get("auto-drill-next.settings.drills-desc")));
            settings.pref(new ProfileSetting());
            settings.textPref(TARGET_QOUT, "0");
            settings.checkPref(EXPERIMENTAL_LIQUID_BOOST, false);
            settings.checkPref(MIXED_TRANSPORT, false);
            settings.checkPref(HEURISTIC_SEARCH, false);
            settings.pref(new DescriptionSetting(bundle.get("auto-drill-next.settings.optimization-quality-desc")));
            table.add(settings);
        }
    }

    static PlannerRequest.Profile nextProfile(PlannerRequest.Profile current) {
        PlannerRequest.Profile[] profiles = PlannerRequest.Profile.values();
        int index = current == null ? 0 : current.ordinal();
        return profiles[(index + 1) % profiles.length];
    }

    static PlannerRequest.SearchStrategy searchStrategy(boolean heuristic) {
        return heuristic
            ? PlannerRequest.SearchStrategy.HEURISTIC
            : PlannerRequest.SearchStrategy.EXHAUSTIVE;
    }

    static PlannerChoicePanel.Stage nextStageAfterDrill(boolean experimentalLiquidBoost) {
        return experimentalLiquidBoost ? PlannerChoicePanel.Stage.LIQUID : PlannerChoicePanel.Stage.EXIT;
    }

    static boolean usesSelectionPreview(PlannerChoicePanel.Stage stage) {
        return stage == PlannerChoicePanel.Stage.DRILL
            || stage == PlannerChoicePanel.Stage.LIQUID
            || stage == PlannerChoicePanel.Stage.EXIT;
    }

    private static PlannerRequest.Profile storedProfile() {
        String value = Core.settings.getString(PROFILE);
        if (value == null || value.isBlank()) return PlannerRequest.Profile.BALANCED;
        try {
            return PlannerRequest.Profile.valueOf(value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return PlannerRequest.Profile.BALANCED;
        }
    }

    private static String profileLabel(PlannerRequest.Profile profile) {
        return bundle.get(
            "auto-drill-next.profile." + profile.name().toLowerCase(Locale.ROOT).replace('_', '-')
        );
    }

    private static final class ProfileSetting extends SettingsMenuDialog.SettingsTable.Setting {
        private ProfileSetting() {
            super(PROFILE);
        }

        @Override
        public void add(SettingsMenuDialog.SettingsTable table) {
            Label value = new Label(profileLabel(storedProfile()), Styles.outlineLabel);
            Button button = new Button(Styles.grayt);
            button.add(title).left().growX();
            button.add(value).right().padLeft(10f);
            button.clicked(() -> {
                PlannerRequest.Profile next = nextProfile(storedProfile());
                Core.settings.put(PROFILE, next.name());
                value.setText(profileLabel(next));
            });
            table.add(button).fillX().height(45f).left();
            addDesc(button);
            table.row();
        }
    }

    private static final class DescriptionSetting extends SettingsMenuDialog.SettingsTable.Setting {
        private final String text;

        private DescriptionSetting(String text) {
            super(null);
            this.text = text;
        }

        @Override
        public void add(SettingsMenuDialog.SettingsTable table) {
            table.labelWrap(text).fillX().get().setWrap(true);
            table.row();
        }
    }
}
