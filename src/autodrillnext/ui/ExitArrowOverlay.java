package autodrillnext.ui;

import arc.Core;
import arc.graphics.Camera;
import arc.math.geom.Vec2;
import arc.scene.event.Touchable;
import arc.scene.ui.ImageButton;
import arc.scene.ui.layout.Table;
import autodrillnext.world.ExitPort;
import autodrillnext.world.OrePatch;
import mindustry.Vars;
import mindustry.gen.Icon;
import mindustry.ui.Styles;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

public final class ExitArrowOverlay {
    private static final float BUTTON_SIZE = 46f;
    private static final float IMAGE_SIZE = 25f;
    private static final GridPosition CANCEL_POSITION = new GridPosition(5, 2);

    private final ExitSelector12 selector;
    private final Table table = new Table();
    private List<Entry> entries = List.of();
    private ImageButton cancel;
    private ExitPort.Side selectedSide = ExitPort.Side.RIGHT;
    private ExitPort.Bias selectedBias = ExitPort.Bias.CENTER;

    public ExitArrowOverlay(ExitSelector12 selector) {
        this.selector = Objects.requireNonNull(selector, "exit selector");
        table.setFillParent(true);
        table.setLayoutEnabled(false);
        table.touchable = Touchable.childrenOnly;
        table.visible = false;
    }

    public Table table() {
        return table;
    }

    public void show(
        OrePatch patch,
        ExitPort.Side side,
        ExitPort.Bias bias,
        Consumer<ExitPort> onSelected,
        Runnable onCancel
    ) {
        Objects.requireNonNull(patch, "ore patch");
        Objects.requireNonNull(side, "selected side");
        Objects.requireNonNull(bias, "selected bias");
        Objects.requireNonNull(onSelected, "exit callback");
        Objects.requireNonNull(onCancel, "cancel callback");
        selectedSide = side;
        selectedBias = bias;
        table.clearChildren();

        ArrayList<Entry> next = new ArrayList<>();
        for (ExitSelector12.Option option : selector.options(patch)) {
            ExitPort port = option.port();
            ImageButton button = new ImageButton(icon(port.side()), Styles.squareTogglei);
            button.setSize(BUTTON_SIZE, BUTTON_SIZE);
            button.resizeImage(IMAGE_SIZE);
            button.setChecked(isSelected(port));
            button.clicked(() -> {
                selectedSide = port.side();
                selectedBias = port.bias();
                updateSelection();
                onSelected.accept(port);
            });
            if (Vars.ui != null) Vars.ui.addDescTooltip(button, Core.bundle.get(option.labelKey()));
            table.addChild(button);
            next.add(new Entry(port, button, gridPosition(port)));
        }
        cancel = new ImageButton(Icon.cancel, Styles.squarei);
        cancel.setSize(BUTTON_SIZE, BUTTON_SIZE);
        cancel.resizeImage(IMAGE_SIZE);
        cancel.clicked(onCancel);
        if (Vars.ui != null) Vars.ui.addDescTooltip(cancel, Core.bundle.get("auto-drill-next.ui.cancel"));
        table.addChild(cancel);
        entries = List.copyOf(next);
        table.visible = true;
        updatePosition(patch);
    }

    public void update(OrePatch patch) {
        if (!table.visible || patch == null) return;
        updatePosition(patch);
    }

    public void hide() {
        table.visible = false;
        table.clearChildren();
        entries = List.of();
        cancel = null;
    }

    private void updatePosition(OrePatch patch) {
        Camera camera = Core.camera;
        if (camera == null || Vars.tilesize <= 0) return;
        Vec2 projected = camera.project(
            ((patch.minX() + patch.maxX() + 1) * 0.5f) * Vars.tilesize,
            ((patch.minY() + patch.maxY() + 1) * 0.5f) * Vars.tilesize
        );
        float centerX = projected.x;
        float centerY = projected.y;
        float screenWidth = Core.scene == null ? Float.POSITIVE_INFINITY : Core.scene.getWidth();
        float screenHeight = Core.scene == null ? Float.POSITIVE_INFINITY : Core.scene.getHeight();
        boolean visible = centerX + BUTTON_SIZE * 4f >= 0f && centerX - BUTTON_SIZE * 4f <= screenWidth
            && centerY + BUTTON_SIZE * 3f >= 0f && centerY - BUTTON_SIZE * 3f <= screenHeight;
        for (Entry entry : entries) {
            position(entry.button(), entry.position(), centerX, centerY, visible);
        }
        if (cancel != null) position(cancel, CANCEL_POSITION, centerX, centerY, visible);
    }

    private void position(ImageButton button, GridPosition position, float centerX, float centerY, boolean visible) {
        button.setPosition(
            centerX + (position.column() - 2.5f) * BUTTON_SIZE,
            centerY + (position.row() - 2.5f) * BUTTON_SIZE
        );
        button.visible = visible;
    }

    private GridPosition gridPosition(ExitPort port) {
        return switch (port.side()) {
            case TOP -> new GridPosition(port.bias().ordinal() + 1, 4);
            case RIGHT -> new GridPosition(4, 3 - port.bias().ordinal());
            case BOTTOM -> new GridPosition(port.bias().ordinal() + 1, 0);
            case LEFT -> new GridPosition(0, port.bias().ordinal() + 1);
        };
    }

    private void updateSelection() {
        for (Entry entry : entries) entry.button().setChecked(isSelected(entry.port()));
    }

    private boolean isSelected(ExitPort port) {
        return port.side() == selectedSide && port.bias() == selectedBias;
    }

    private arc.scene.style.Drawable icon(ExitPort.Side side) {
        return switch (side) {
            case TOP -> Icon.up;
            case BOTTOM -> Icon.down;
            case LEFT -> Icon.left;
            case RIGHT -> Icon.right;
        };
    }

    private record GridPosition(int column, int row) {
    }

    private record Entry(ExitPort port, ImageButton button, GridPosition position) {
    }
}
