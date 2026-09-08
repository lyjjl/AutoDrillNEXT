package autodrillnext.ui;

import arc.Core;
import arc.graphics.g2d.TextureRegion;
import arc.scene.style.Drawable;
import arc.scene.style.TextureRegionDrawable;
import arc.scene.ui.ImageButton;
import arc.scene.ui.layout.Table;
import autodrillnext.capability.spec.CapabilityDescriptor;
import autodrillnext.capability.spec.CapabilityKind;
import autodrillnext.capability.spec.CapabilityState;
import autodrillnext.capability.spec.DrillSpec;
import autodrillnext.capability.spec.SupportVariant;
import autodrillnext.model.LiquidId;
import autodrillnext.model.PlannerResult;
import autodrillnext.model.SearchStopReason;
import mindustry.Vars;
import mindustry.gen.Icon;
import mindustry.type.Liquid;
import mindustry.ui.Styles;
import mindustry.world.Block;
import mindustry.world.Tile;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.math.BigDecimal;
import java.math.MathContext;
import java.util.function.Consumer;

public final class PlannerChoicePanel {
    private static final float ICON_SIZE = 48f;
    private static final float ICON_IMAGE_SIZE = 34f;
    private static final int ICONS_PER_ROW = 6;

    public enum Stage {
        DRILL,
        LIQUID,
        EXIT,
        REVIEW
    }

    private final Table table = new Table(Styles.black3);
    private final SearchVerdictPresenter verdicts = new SearchVerdictPresenter();
    private final PlannerDiagnostics diagnostics = new PlannerDiagnostics();

    public PlannerChoicePanel() {
        table.margin(6f);
        table.visible = false;
    }

    public Table table() {
        return table;
    }

    public void showLoading(Stage stage, Runnable onClose) {
        Objects.requireNonNull(stage, "stage");
        Objects.requireNonNull(onClose, "close callback");
        table.clearChildren();
        table.visible = true;
        addHeader(title(stage), onClose);
        table.add(Core.bundle.get("auto-drill-next.ui.planning")).left().padTop(6f).row();
        table.pack();
    }

    public void showError(String message, Runnable onClose) {
        Objects.requireNonNull(message, "error message");
        Objects.requireNonNull(onClose, "close callback");
        table.clearChildren();
        table.visible = true;
        addHeader(Core.bundle.get("auto-drill-next.ui.plan"), onClose);
        table.add(message).left().padTop(6f).row();
        table.pack();
    }

    public void show(
        PlannerResult result,
        Stage stage,
        String selectedDrillId,
        String selectedLiquidId,
        Consumer<String> onDrillSelected,
        Consumer<String> onLiquidSelected,
        Runnable onBuild,
        Runnable onClose
    ) {
        Objects.requireNonNull(result, "planner result");
        Objects.requireNonNull(stage, "stage");
        Objects.requireNonNull(onDrillSelected, "drill callback");
        Objects.requireNonNull(onLiquidSelected, "liquid callback");
        Objects.requireNonNull(onClose, "close callback");
        Objects.requireNonNull(onBuild, "build callback");
        table.clearChildren();
        table.visible = true;
        addHeader(title(stage), onClose);

        switch (stage) {
            case DRILL -> showDrills(result, selectedDrillId, onDrillSelected);
            case LIQUID -> showLiquids(result, selectedDrillId, selectedLiquidId, onLiquidSelected);
            case EXIT -> table.add(Core.bundle.get("auto-drill-next.ui.choose-exit")).left().padTop(6f).row();
            case REVIEW -> showReview(result, onBuild);
        }
        table.pack();
    }

    public void hide() {
        table.visible = false;
        table.clearChildren();
    }

    private void showDrills(
        PlannerResult result,
        String selectedDrillId,
        Consumer<String> onSelected
    ) {
        Table buttons = iconGrid();
        int count = 0;
        for (CapabilityDescriptor descriptor : drillChoices(result)) {
            Block block = Vars.content == null ? null : Vars.content.block(descriptor.id().value());
            ImageButton button = iconButton(blockIcon(block));
            button.setChecked(descriptor.id().value().equals(selectedDrillId));
            button.clicked(() -> onSelected.accept(descriptor.id().value()));
            addTooltip(button, drillDetails(block, descriptor));
            buttons.add(button).size(ICON_SIZE);
            if (++count % ICONS_PER_ROW == 0) buttons.row();
        }
        if (count == 0) table.add(Core.bundle.get("auto-drill-next.ui.no-drills")).left().row();
        else table.add(buttons).left().row();
    }

    private void showLiquids(
        PlannerResult result,
        String selectedDrillId,
        String selectedLiquidId,
        Consumer<String> onSelected
    ) {
        Table buttons = iconGrid();
        ImageButton none = iconButton(Icon.cancel);
        none.setChecked(selectedLiquidId != null && selectedLiquidId.isBlank());
        none.clicked(() -> onSelected.accept(""));
        addTooltip(none, Core.bundle.get("auto-drill-next.ui.liquid-none"));
        buttons.add(none).size(ICON_SIZE);

        int count = 1;
        for (LiquidId id : liquidChoices(result, selectedDrillId)) {
            Liquid liquid = findLiquid(id);
            ImageButton button = iconButton(liquidIcon(liquid));
            button.setChecked(id.value().equals(selectedLiquidId));
            button.clicked(() -> onSelected.accept(id.value()));
            addTooltip(button, liquidDetails(liquid, id));
            buttons.add(button).size(ICON_SIZE);
            if (++count % ICONS_PER_ROW == 0) buttons.row();
        }
        table.add(buttons).left().row();
    }

    private Table iconGrid() {
        Table grid = new Table();
        grid.defaults().pad(0f);
        return grid;
    }

    private void addHeader(String title, Runnable onClose) {
        Table header = new Table();
        header.add(title).left().growX();
        ImageButton close = new ImageButton(Icon.cancel, Styles.squarei);
        close.resizeImage(20f);
        close.clicked(onClose);
        addTooltip(close, Core.bundle.get("auto-drill-next.ui.cancel"));
        header.add(close).size(ICON_SIZE);
        table.add(header).growX().row();
    }

    private void showReview(PlannerResult result, Runnable onBuild) {
        var certificate = result.certificate();
        table.labelWrap(tone(
            Core.bundle.get(verdicts.bundleKey(certificate)),
            verdicts.style(certificate.verdict())
        )).left().growX().padTop(6f).row();
        if (certificate.scope() != null) {
            table.labelWrap(Core.bundle.get("auto-drill-next.ui.proof-scope"))
                .left().growX().padTop(4f).row();
        }
        table.labelWrap(Core.bundle.format(
            "auto-drill-next.ui.proof-counts",
            Integer.toString(certificate.exploredStates()),
            Integer.toString(certificate.prunedStates()),
            Integer.toString(certificate.pendingStates())
        )).left().growX().padTop(4f).row();
        if (certificate.stopReason() != SearchStopReason.MODEL_INCOMPLETE) {
            table.labelWrap(Core.bundle.format(
                "auto-drill-next.ui.proof-gap",
                decimal(certificate.lowerBound().qout()),
                decimal(certificate.upperBound().qout())
            )).left().growX().padTop(4f).row();
        }
        var selected = result.budget() == null ? null : result.budget().selected();
        if (selected != null) {
            table.add(Core.bundle.format(
                "auto-drill-next.preview.line",
                Core.bundle.get("auto-drill-next.preview.qout"),
                decimal(selected.qout())
            )).left().padTop(6f).row();
            table.add(Core.bundle.format(
                "auto-drill-next.preview.line",
                Core.bundle.get("auto-drill-next.preview.material-cost"),
                decimal(selected.cost().materials().economicValue())
            )).left().row();
            table.add(Core.bundle.format(
                "auto-drill-next.preview.line",
                Core.bundle.get("auto-drill-next.preview.space-cost"),
                Integer.toString(selected.cost().space())
            )).left().row();
            table.add(Core.bundle.format(
                "auto-drill-next.preview.line",
                Core.bundle.get("auto-drill-next.preview.complexity-cost"),
                Integer.toString(selected.cost().complexity())
            )).left().row();
        }
        if (shouldShowBuildAction(result)) {
            table.button(Core.bundle.get("auto-drill-next.ui.build-plan"), onBuild)
                .growX().height(48f).padTop(8f).row();
        }
    }
    static boolean shouldShowBuildAction(PlannerResult result) {
        return Objects.requireNonNull(result, "planner result").compileReady();
    }

    private String tone(String text, SearchVerdictPresenter.Tone tone) {
        String color = switch (tone) {
            case NEUTRAL -> "b8c2cc";
            case AMBER -> "ffc857";
            case GREEN -> "63e6a2";
            case ERROR -> "ff6b6b";
        };
        return "[#" + color + "]" + text + "[]";
    }

    private String decimal(double value) {
        return Double.isFinite(value)
            ? BigDecimal.valueOf(value).round(MathContext.DECIMAL32)
                .stripTrailingZeros().toPlainString()
            : "—";
    }

    private String title(Stage stage) {
        return switch (stage) {
            case DRILL -> Core.bundle.get("auto-drill-next.ui.choose-drill");
            case LIQUID -> Core.bundle.get("auto-drill-next.ui.choose-liquid");
            case EXIT -> Core.bundle.get("auto-drill-next.ui.choose-exit");
            case REVIEW -> Core.bundle.get("auto-drill-next.ui.plan");
        };
    }

    private ImageButton iconButton(Drawable icon) {
        ImageButton button = new ImageButton(icon, Styles.squareTogglei);
        button.resizeImage(ICON_IMAGE_SIZE);
        return button;
    }

    private void addTooltip(ImageButton button, String details) {
        if (Vars.ui != null && details != null && !details.isBlank()) Vars.ui.addDescTooltip(button, details);
    }

    private List<CapabilityDescriptor> drillChoices(PlannerResult result) {
        if (result.capabilities() == null) return List.of();
        ArrayList<CapabilityDescriptor> choices = new ArrayList<>();
        for (CapabilityDescriptor descriptor : result.capabilities().descriptors().values()) {
            if (descriptor.kind() == CapabilityKind.DRILL
                && descriptor.states().contains(CapabilityState.AVAILABLE_NOW)
                && descriptor.spec() instanceof DrillSpec spec
                && supportsPatch(spec, result)) {
                choices.add(descriptor);
            }
        }
        choices.sort(Comparator.comparing(descriptor -> descriptor.id().value()));
        return List.copyOf(choices);
    }

    private boolean supportsPatch(DrillSpec spec, PlannerResult result) {
        if (result.patch() == null || Vars.world == null) return false;
        Tile source = Vars.world.tile(result.patch().origin().x(), result.patch().origin().y());
        if (source == null) return false;
        if (source.drop() != null) return spec.minesFloorOre();
        return source.wallDrop() != null && spec.minesWallOre();
    }

    private List<LiquidId> liquidChoices(PlannerResult result, String selectedDrillId) {
        LinkedHashSet<LiquidId> choices = new LinkedHashSet<>();
        for (CapabilityDescriptor descriptor : drillChoices(result)) {
            if (!descriptor.id().value().equals(selectedDrillId)) continue;
            DrillSpec spec = (DrillSpec) descriptor.spec();
            for (SupportVariant variant : spec.optionalSupport()) {
                choices.addAll(variant.requirement().allowedLiquids());
            }
        }
        ArrayList<LiquidId> sorted = new ArrayList<>(choices);
        sorted.sort(Comparator.comparing(LiquidId::value));
        return List.copyOf(sorted);
    }

    private Drawable blockIcon(Block block) {
        if (block == null) return Icon.wrench;
        TextureRegion icon = block.fullIcon != null ? block.fullIcon : block.uiIcon;
        return icon == null ? Icon.wrench : new TextureRegionDrawable(icon);
    }

    private Drawable liquidIcon(Liquid liquid) {
        if (liquid == null) return Icon.liquid;
        TextureRegion icon = liquid.fullIcon != null ? liquid.fullIcon : liquid.uiIcon;
        return icon == null ? Icon.liquid : new TextureRegionDrawable(icon);
    }

    private String drillDetails(Block block, CapabilityDescriptor descriptor) {
        DrillSpec spec = (DrillSpec) descriptor.spec();
        String name = block == null ? descriptor.id().value() : block.localizedName;
        return Core.bundle.format(
            "auto-drill-next.choice.drill-details",
            name,
            Integer.toString(spec.size()),
            Float.toString(spec.drillTime()),
            Integer.toString(spec.optionalSupport().size()),
            descriptor.cost().amounts().toString()
        );
    }

    private String liquidDetails(Liquid liquid, LiquidId id) {
        if (liquid == null) return id.value();
        return Core.bundle.format(
            "auto-drill-next.choice.liquid-details",
            liquid.localizedName,
            Float.toString(liquid.temperature),
            Float.toString(liquid.viscosity),
            liquid.description == null ? "" : liquid.description
        );
    }

    private Liquid findLiquid(LiquidId id) {
        if (Vars.content == null) return null;
        Liquid liquid = Vars.content.liquid(id.value());
        if (liquid == null) {
            int separator = id.value().lastIndexOf(':');
            if (separator >= 0) liquid = Vars.content.liquid(id.value().substring(separator + 1));
        }
        return liquid;
    }
}
