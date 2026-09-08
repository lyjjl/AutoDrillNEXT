package autodrillnext.mindustryapi;

import autodrillnext.capability.CapabilityRegistry;
import autodrillnext.capability.adapter.CapabilityAdapter;
import autodrillnext.capability.adapter.CapabilityAdapterRegistry;
import autodrillnext.capability.adapter.CapabilityContext;
import autodrillnext.capability.adapter.ResourceValuationCapture;
import autodrillnext.capability.spec.CapabilityCandidate;
import autodrillnext.capability.spec.CapabilityDescriptor;
import autodrillnext.capability.spec.CapabilityKind;
import autodrillnext.capability.spec.CapabilitySnapshot;
import autodrillnext.capability.spec.CapabilitySpec;
import autodrillnext.capability.spec.CapabilityState;
import autodrillnext.capability.spec.DrillSpec;
import autodrillnext.capability.spec.ItemTransportSpec;
import autodrillnext.capability.spec.LiquidProviderSpec;
import autodrillnext.capability.spec.LiquidTransportSpec;
import autodrillnext.capability.spec.PowerConnectorSpec;
import autodrillnext.model.ContentId;
import autodrillnext.world.TileKey;
import autodrillnext.world.WorldSnapshot;
import autodrillnext.model.Inventory;
import mindustry.Vars;
import mindustry.game.Rules;
import mindustry.game.Team;
import mindustry.type.Liquid;
import mindustry.world.Block;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.lang.reflect.Method;

public final class GameFacade {
    private static final Method OVER_PLACEMENT_LIMIT = findOverPlacementLimit();
    private final CapabilityAdapterRegistry adapters;
    private final CapabilityRegistry capabilityRegistry;
    private final ResourceFacade resources;

    public GameFacade() {
        this(CapabilityAdapterRegistry.v159Defaults(), new CapabilityRegistry(), new ResourceFacade());
    }

    public GameFacade(
        CapabilityAdapterRegistry adapters,
        CapabilityRegistry capabilityRegistry,
        ResourceFacade resources
    ) {
        this.adapters = Objects.requireNonNull(adapters, "adapters");
        this.capabilityRegistry = Objects.requireNonNull(capabilityRegistry, "capabilityRegistry");
        this.resources = Objects.requireNonNull(resources, "resources");
    }

    public boolean runtimeAvailable() {
        return Vars.state != null && Vars.state.rules != null;
    }
    public String playerTeamId() {
        if (Vars.player == null || Vars.player.team() == null) {
            throw new IllegalStateException("player team is not ready");
        }
        return Vars.player.team().name;
    }

    public boolean infiniteResources() {
        if (!runtimeAvailable()) throw new IllegalStateException("game state is not ready");
        return Vars.state.rules.infiniteResources;
    }

    public String localizedBlockName(String blockId) {
        Objects.requireNonNull(blockId, "block id");
        Block block = Vars.content.block(blockId);
        if (block == null) throw new IllegalArgumentException("unknown block: " + blockId);
        return block.localizedName;
    }


    public RuntimeSnapshot snapshot(WorldFacade world, String teamId, TileKey seed, int maxTiles) {
        Objects.requireNonNull(world, "world facade");
        Objects.requireNonNull(teamId, "team id");
        if (!runtimeAvailable()) throw new IllegalStateException("game state is not ready");
        WorldFacade.requireMainThread();
        Team team = resolveTeam(teamId);
        Rules rules = Vars.state.rules;
        CapabilitySnapshot capabilities = snapshot(team, rules);
        WorldSnapshot captured = world.snapshot(team, seed, maxTiles, capabilities);
        if (!captured.placementRules().diagnostics().isEmpty()) {
            LinkedHashMap<ContentId, CapabilityDescriptor> descriptors = new LinkedHashMap<>(capabilities.descriptors());
            captured.placementRules().diagnostics().forEach((id, diagnostic) -> {
                CapabilityDescriptor descriptor = descriptors.get(id);
                LinkedHashSet<String> reasons = new LinkedHashSet<>(descriptor.reasons());
                reasons.add("UNSUPPORTED_CONTENT");
                reasons.add("RUNTIME_CAPTURE_FAILED: " + diagnostic);
                descriptors.put(id, new CapabilityDescriptor(id, descriptor.kind(),
                    EnumSet.of(CapabilityState.DISCOVERED), reasons, descriptor.cost(),
                    descriptor.adapterId() + ":runtime-quarantined", null));
            });
            capabilities = new CapabilitySnapshot(descriptors);
        }
        return new RuntimeSnapshot(
            captured,
            resources.inventory(team),
            capabilities
        );
    }

    private Team resolveTeam(String id) {
        for (Team team : Team.all) if (team.name.equalsIgnoreCase(id)) return team;
        try {
            return Team.get(Integer.parseInt(id));
        } catch (NumberFormatException failure) {
            throw new IllegalArgumentException("unknown team: " + id, failure);
        }
    }

    public record RuntimeSnapshot(
        WorldSnapshot world,
        Inventory inventory,
        CapabilitySnapshot capabilities
    ) {
        public RuntimeSnapshot {
            Objects.requireNonNull(world, "world snapshot");
            Objects.requireNonNull(inventory, "inventory");
            Objects.requireNonNull(capabilities, "capabilities");
        }
    }

    public List<CapabilityCandidate> discover(Team team, Rules rules) {
        Objects.requireNonNull(team, "team");
        Objects.requireNonNull(rules, "rules");
        CapabilityContext context = capabilityContext(rules);
        ArrayList<CapabilityCandidate> candidates = new ArrayList<>();
        for (Block block : Vars.content.blocks()) {
            candidates.add(describe(block, team, rules, context));
        }
        return List.copyOf(candidates);
    }

    public CapabilityCandidate describe(Block block, Team team, Rules rules) {
        Objects.requireNonNull(block, "block");
        Objects.requireNonNull(team, "team");
        Objects.requireNonNull(rules, "rules");
        return describe(block, team, rules, capabilityContext(rules));
    }

    private CapabilityCandidate describe(Block block, Team team, Rules rules, CapabilityContext context) {

        CapabilityAdapter<?, ?> adapter = adapters.find(block);
        boolean unlocked = block.unlockedNow();
        boolean placeable = block.isPlaceable();
        boolean overPlacementLimit = overPlacementLimit(block, team);
        if (adapter == null) {
            return new CapabilityCandidate(
                ContentId.of(block.name),
                CapabilityKind.UNKNOWN,
                false,
                unlocked,
                placeable,
                overPlacementLimit,
                autodrillnext.capability.spec.CostVector.empty()
            );
        }

        try {
            CapabilitySpec spec = describe(adapter, block, context);
            return new CapabilityCandidate(
                spec.id(),
                kindOf(spec),
                true,
                unlocked,
                placeable,
                overPlacementLimit,
                spec.cost(),
                adapter.id(),
                spec
            );
        } catch (RuntimeException failure) {
            return new CapabilityCandidate(
                ContentId.of(block.name),
                CapabilityKind.UNKNOWN,
                false,
                unlocked,
                placeable,
                overPlacementLimit,
                autodrillnext.capability.spec.CostVector.empty(),
                adapter.id() + ":quarantined:" + failure.getMessage(),
                null
            );
        }
    }

    private static Method findOverPlacementLimit() {
        try {
            return Block.class.getMethod("isOverPlacementLimit", Team.class);
        } catch (NoSuchMethodException ignored) {
            return null;
        }
    }

    private static boolean overPlacementLimit(Block block, Team team) {
        if (OVER_PLACEMENT_LIMIT == null) return false;
        try {
            return Boolean.TRUE.equals(OVER_PLACEMENT_LIMIT.invoke(block, team));
        } catch (ReflectiveOperationException | RuntimeException failure) {
            return true;
        }
    }

    public CapabilitySnapshot snapshot(Team team, Rules rules) {
        Inventory inventory = resources.inventory(team);
        return capabilityRegistry.evaluate(discover(team, rules), inventory, rules.infiniteResources);
    }

    public Inventory inventory(Team team) {
        return resources.inventory(team);
    }

    public CapabilityDescriptor capability(ContentId id, Team team, Rules rules) {
        return snapshot(team, rules).require(id);
    }

    private CapabilityContext capabilityContext(Rules rules) {
        if (arc.Core.app != null && !arc.Core.app.isOnMainThread()) {
            throw new IllegalStateException("capability capture requires the game main thread");
        }
        ArrayList<Liquid> liquids = new ArrayList<>();
        for (Liquid liquid : Vars.content.liquids()) liquids.add(liquid);
        return new CapabilityContext(rules.buildCostMultiplier, liquids, rules.infiniteResources,
            ResourceValuationCapture.capture(Vars.content.items(), Vars.content.blocks()));
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private CapabilitySpec describe(
        CapabilityAdapter<?, ?> adapter,
        Block block,
        CapabilityContext context
    ) {
        return (CapabilitySpec) ((CapabilityAdapter) adapter).describe(block, context);
    }

    private CapabilityKind kindOf(CapabilitySpec spec) {
        if (spec instanceof DrillSpec) return CapabilityKind.DRILL;
        if (spec instanceof ItemTransportSpec) return CapabilityKind.ITEM_TRANSPORT;
        if (spec instanceof LiquidTransportSpec) return CapabilityKind.LIQUID_TRANSPORT;
        if (spec instanceof PowerConnectorSpec) return CapabilityKind.POWER_CONNECTOR;
        if (spec instanceof LiquidProviderSpec) return CapabilityKind.LIQUID_PROVIDER;
        throw new IllegalArgumentException("unsupported capability spec: " + spec.getClass().getName());
    }
}
