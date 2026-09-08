package autodrillnext.world;

import autodrillnext.model.ContentId;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Immutable runtime answers. Absent block/anchor/rotation entries are forbidden, never guessed. */
public final class PlacementRules {
    public record Placement(ContentId block, TileKey anchor, int rotation) {
        public Placement {
            Objects.requireNonNull(block, "block");
            Objects.requireNonNull(anchor, "anchor");
            if (rotation < 0 || rotation > 3) throw new IllegalArgumentException("invalid rotation");
        }
    }

    private final Map<ContentId, Map<TileKey, Integer>> allowed;
    private final Map<Placement, MiningResult> mining;
    private final Map<Placement, List<PowerAccess>> power;
    private final Map<ContentId, String> diagnostics;

    public PlacementRules(Map<ContentId, Map<TileKey, Integer>> allowed, Map<Placement, MiningResult> mining) {
        this(allowed, mining, Map.of());
    }

    public PlacementRules(Map<ContentId, Map<TileKey, Integer>> allowed, Map<Placement, MiningResult> mining,
                          Map<Placement, List<PowerAccess>> power) {
        this(allowed, mining, power, Map.of());
    }

    public PlacementRules(Map<ContentId, Map<TileKey, Integer>> allowed, Map<Placement, MiningResult> mining,
                          Map<Placement, List<PowerAccess>> power, Map<ContentId, String> diagnostics) {
        Map<ContentId, Map<TileKey, Integer>> copied = new LinkedHashMap<>();
        allowed.forEach((block, anchors) -> copied.put(block, Map.copyOf(anchors)));
        this.allowed = Map.copyOf(copied);
        this.mining = Map.copyOf(mining);
        Map<Placement, List<PowerAccess>> copiedPower = new LinkedHashMap<>();
        power.forEach((placement, access) -> copiedPower.put(placement, List.copyOf(access)));
        this.power = Map.copyOf(copiedPower);
        this.diagnostics = Map.copyOf(diagnostics);
    }

    public boolean canPlace(ContentId block, TileKey anchor, int rotation) {
        if (rotation < 0 || rotation > 3) return false;
        Map<TileKey, Integer> anchors = allowed.get(block);
        return anchors != null && (anchors.getOrDefault(anchor, 0) & (1 << rotation)) != 0;
    }

    public MiningResult mining(ContentId block, TileKey anchor, int rotation) {
        if (!canPlace(block, anchor, rotation)) return null;
        return mining.get(new Placement(block, anchor, rotation));
    }

    public List<PowerAccess> powerAccess(ContentId block, TileKey anchor, int rotation) {
        if (!canPlace(block, anchor, rotation)) return List.of();
        return power.getOrDefault(new Placement(block, anchor, rotation), List.of());
    }

    public Map<ContentId, String> diagnostics() {
        return diagnostics;
    }

    public PlacementRules translated(int dx, int dy) {
        Map<ContentId, Map<TileKey, Integer>> translated = new LinkedHashMap<>();
        allowed.forEach((block, anchors) -> {
            Map<TileKey, Integer> moved = new LinkedHashMap<>();
            anchors.forEach((anchor, rotations) -> moved.put(anchor.translated(dx, dy), rotations));
            translated.put(block, moved);
        });
        Map<Placement, MiningResult> movedMining = new LinkedHashMap<>();
        mining.forEach((placement, result) -> movedMining.put(new Placement(placement.block(),
            placement.anchor().translated(dx, dy), placement.rotation()), result.translated(dx, dy)));
        Map<Placement, List<PowerAccess>> movedPower = new LinkedHashMap<>();
        power.forEach((placement, access) -> movedPower.put(new Placement(placement.block(),
            placement.anchor().translated(dx, dy), placement.rotation()),
            access.stream().map(link -> link.translated(dx, dy)).toList()));
        return new PlacementRules(translated, movedMining, movedPower, diagnostics);
    }
}
