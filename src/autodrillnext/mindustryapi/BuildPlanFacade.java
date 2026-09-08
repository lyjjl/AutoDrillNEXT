package autodrillnext.mindustryapi;

import arc.math.geom.Point2;
import autodrillnext.compile.BuildPlanCompiler;
import autodrillnext.world.TileOffset;
import mindustry.Vars;
import mindustry.entities.units.BuildPlan;
import mindustry.world.Block;

import java.util.Objects;

public final class BuildPlanFacade {
    public BuildPlan compile(Block block, int x, int y, int rotation, Object config) {
        return new BuildPlan(
            x,
            y,
            Math.floorMod(rotation, 4),
            Objects.requireNonNull(block, "block"),
            config
        );
    }

    public BuildPlan compile(Block block, int x, int y, int rotation) {
        return compile(block, x, y, rotation, null);
    }

    public void submit(java.util.List<BuildPlanCompiler.CompileRecord> records) {
        Objects.requireNonNull(records, "compile records");
        if (Vars.player == null || Vars.player.unit() == null) throw new IllegalStateException("builder is unavailable");
        java.util.ArrayList<BuildPlan> plans = new java.util.ArrayList<>(records.size());
        java.util.HashSet<autodrillnext.world.TileKey> occupied = new java.util.HashSet<>();
        for (BuildPlan queued : Vars.player.unit().plans()) {
            if (!queued.breaking) occupied.addAll(autodrillnext.world.PlacementFootprint.square(
                new autodrillnext.world.TileKey(queued.x, queued.y), queued.block.size).tiles());
        }
        for (BuildPlanCompiler.CompileRecord record : records) {
            Block block = Vars.content.block(record.blockId());
            if (block == null || !block.unlockedNow() || !block.isPlaceable()
                || !mindustry.world.Build.validPlace(block, Vars.player.team(),
                    record.tile().x(), record.tile().y(), record.rotation())) {
                throw new IllegalStateException("placement changed at " + record.tile());
            }
            for (var tile : autodrillnext.world.PlacementFootprint.square(record.tile(), block.size).tiles()) {
                if (!occupied.add(tile)) throw new IllegalStateException("queued placement collision at " + tile);
            }
            Object config = record.config() instanceof TileOffset offset
                ? new Point2(offset.x(), offset.y())
                : record.config();
            plans.add(compile(
                block,
                record.tile().x(),
                record.tile().y(),
                record.rotation(),
                config
            ));
        }
        // Validate the whole batch before touching the player's queue.
        for (BuildPlan plan : plans) Vars.player.unit().addBuild(plan);
    }
}
