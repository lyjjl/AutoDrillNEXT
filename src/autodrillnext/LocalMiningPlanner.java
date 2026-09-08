package autodrillnext;

import autodrillnext.capability.spec.CapabilityDescriptor;
import autodrillnext.capability.spec.CapabilityKind;
import autodrillnext.capability.spec.CapabilitySnapshot;
import autodrillnext.capability.spec.CapabilityState;
import autodrillnext.capability.spec.DrillSpec;
import autodrillnext.capability.spec.ItemTransportSpec;
import autodrillnext.capability.spec.LiquidProviderSpec;
import autodrillnext.capability.spec.LiquidTransportSpec;
import autodrillnext.capability.spec.PowerConnectorSpec;
import autodrillnext.compile.BuildPlanCompiler;
import autodrillnext.compile.BuildSequencer;
import autodrillnext.compile.FinalValidator;
import autodrillnext.compile.LiveSnapshot;
import autodrillnext.compile.ValidationResult;
import autodrillnext.mindustryapi.GameFacade;
import autodrillnext.mindustryapi.WorldFacade;
import autodrillnext.model.AutoDrillNEXTPlanRecord;
import autodrillnext.model.BudgetSnapshot;
import autodrillnext.model.DiagnosticCode;
import autodrillnext.model.DrillCandidate;
import autodrillnext.model.EdgeKind;
import autodrillnext.model.ExistingNetwork;
import autodrillnext.model.Inventory;
import autodrillnext.model.OptimalityCertificate;
import autodrillnext.model.PatchSearchScope;
import autodrillnext.model.PlanEdge;
import autodrillnext.model.PlanGraph;
import autodrillnext.model.PlannerDiagnostic;
import autodrillnext.model.PlannerRequest;
import autodrillnext.model.PlannerResult;
import autodrillnext.model.PlanningProgress;
import autodrillnext.model.ServiceBundle;
import autodrillnext.model.UpgradePlan;
import autodrillnext.simulation.SimulationFidelity;
import autodrillnext.solver.BudgetPlanner;
import autodrillnext.solver.BudgetSelection;
import autodrillnext.solver.CandidateGenerator;
import autodrillnext.solver.ExitFeasibilitySolver;
import autodrillnext.solver.ExitResolution;
import autodrillnext.solver.OptimalPlanningResult;
import autodrillnext.solver.PatchOptimalSearch;
import autodrillnext.solver.SupportPlan;
import autodrillnext.solver.SupportPlanner;
import autodrillnext.solver.UpgradePlanner;
import autodrillnext.world.ExitPort;
import autodrillnext.world.ExistingNetworkScanner;
import autodrillnext.world.OrePatch;
import autodrillnext.world.OrePatchAnalyzer;
import autodrillnext.world.TerrainSnapshot;
import autodrillnext.world.TileKey;
import autodrillnext.world.WorldSnapshot;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

public final class LocalMiningPlanner {
    public record PlanningSnapshot(
        WorldSnapshot world,
        Inventory inventory,
        CapabilitySnapshot capabilities,
        ExistingNetwork existingNetwork
    ) {
        public PlanningSnapshot {
            Objects.requireNonNull(world, "world");
            Objects.requireNonNull(inventory, "inventory");
            Objects.requireNonNull(capabilities, "capabilities");
            Objects.requireNonNull(existingNetwork, "existing network");
        }
    }

    private final GameFacade game;
    private final WorldFacade worldFacade;
    private final OrePatchAnalyzer patchAnalyzer;
    private final ExitFeasibilitySolver exits;
    private final CandidateGenerator candidates;
    private final SupportPlanner support;
    private final ExistingNetworkScanner networks;
    private final UpgradePlanner upgrades;
    private final FinalValidator validator;
    private final BuildPlanCompiler compiler;
    private final BuildSequencer sequencer;
    private final BudgetPlanner budget;
    private final PatchOptimalSearch optimalSearch;

    public LocalMiningPlanner() {
        this(
            new GameFacade(),
            new WorldFacade(),
            new OrePatchAnalyzer(),
            new ExitFeasibilitySolver(),
            new CandidateGenerator(),
            new SupportPlanner(),
            new ExistingNetworkScanner(),
            new UpgradePlanner(),
            new FinalValidator(),
            new BuildPlanCompiler(),
            new BuildSequencer(),
            new BudgetPlanner(),
            new PatchOptimalSearch()
        );
    }

    public LocalMiningPlanner(
        GameFacade game,
        WorldFacade worldFacade,
        OrePatchAnalyzer patchAnalyzer,
        ExitFeasibilitySolver exits,
        CandidateGenerator candidates,
        SupportPlanner support,
        ExistingNetworkScanner networks,
        UpgradePlanner upgrades,
        FinalValidator validator,
        BuildPlanCompiler compiler,
        BuildSequencer sequencer,
        BudgetPlanner budget,
        PatchOptimalSearch optimalSearch
    ) {
        this.game = Objects.requireNonNull(game, "game");
        this.worldFacade = Objects.requireNonNull(worldFacade, "world facade");
        this.patchAnalyzer = Objects.requireNonNull(patchAnalyzer, "patch analyzer");
        this.exits = Objects.requireNonNull(exits, "exit solver");
        this.candidates = Objects.requireNonNull(candidates, "candidate generator");
        this.support = Objects.requireNonNull(support, "support planner");
        this.networks = Objects.requireNonNull(networks, "network scanner");
        this.upgrades = Objects.requireNonNull(upgrades, "upgrade planner");
        this.validator = Objects.requireNonNull(validator, "final validator");
        this.compiler = Objects.requireNonNull(compiler, "build compiler");
        this.sequencer = Objects.requireNonNull(sequencer, "build sequencer");
        this.budget = Objects.requireNonNull(budget, "budget planner");
        this.optimalSearch = Objects.requireNonNull(optimalSearch, "optimal search");
    }

    public String playerTeamId() {
        return game.playerTeamId();
    }

    public boolean infiniteResources() {
        return game.infiniteResources();
    }

    public String localizedBlockName(String blockId) {
        return game.localizedBlockName(blockId);
    }

    public PlannerResult plan(PlannerRequest request) {
        return plan(request, null, null);
    }

    public PlannerResult plan(PlannerRequest request, String selectedDrillId, String selectedLiquidId) {
        try {
            return plan(request, capture(request), selectedDrillId, selectedLiquidId);
        } catch (IllegalArgumentException | IllegalStateException failure) {
            return failure(null, null, null, DiagnosticCode.TERRAIN_OR_RULE_BLOCKED, failure.getMessage());
        }
    }

    public PlanningSnapshot capture(PlannerRequest request) {
        Objects.requireNonNull(request, "request");
        if (!game.runtimeAvailable()) throw new IllegalStateException("Mindustry runtime is unavailable");
        GameFacade.RuntimeSnapshot runtime = game.snapshot(
            worldFacade,
            request.teamId(),
            request.seed(),
            request.maxTiles()
        );
        return new PlanningSnapshot(
            runtime.world(),
            runtime.inventory(),
            runtime.capabilities(),
            networks.scan(runtime.world(), runtime.capabilities())
        );
    }

    /** Rechecks every planned placement and required support against current physical constraints. */
    public void validateSubmission(
        PlannerRequest request,
        PlanningSnapshot fresh,
        PlannerResult planned,
        String selectedLiquidId
    ) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(fresh, "fresh snapshot");
        Objects.requireNonNull(planned, "planned result");
        if (!planned.compileReady()) throw new IllegalStateException("plan is not ready");
        OptimalityCertificate certificate = planned.certificate();
        PatchSearchScope priorScope = certificate.scope();
        if (priorScope == null) throw new IllegalStateException("plan has no captured search scope");

        OrePatch patch = priorScope.patch();
        TerrainSnapshot terrain = TerrainSnapshot.of(fresh.world());

        Map<String, DrillCandidate> available = new LinkedHashMap<>();
        for (DrillCandidate candidate : candidates.generate(
            patch,
            terrain,
            fresh.capabilities(),
            request,
            priorScope.selectedDrillId()
        )) {
            available.put(candidate.id(), candidate);
        }
        for (DrillCandidate previous : planned.layout().candidates()) {
            DrillCandidate current = available.get(previous.id());
            if (current == null
                || !current.production().equals(previous.production())
                || !current.footprint().tiles().equals(previous.footprint().tiles())
                || !current.coveredOreCells().equals(previous.coveredOreCells())
                || !current.mandatorySupport().equals(previous.mandatorySupport())
                || !current.supportVariants().equals(previous.supportVariants())
                || !current.cost().equals(previous.cost())) {
                throw new IllegalStateException("mining capability changed at " + previous.anchor());
            }
        }
        for (var placement : planned.graph().placements()) {
            CapabilityDescriptor descriptor = fresh.capabilities().descriptors().get(placement.spec().id());
            if (descriptor == null || !placement.spec().equals(descriptor.spec())) {
                throw new IllegalStateException("transport capability changed at " + placement.tile());
            }
        }
        LiveSnapshot live = liveSnapshot(fresh, request);
        ValidationResult validation = validator.validate(planned.graph(), live, planned.layout());
        if (!validation.valid()) {
            throw new IllegalStateException("placement changed: " + validation.diagnostics());
        }
        SupportPlan supplied = support.solve(
            planned.layout(),
            planned.graph(),
            terrain,
            fresh.capabilities(),
            fresh.existingNetwork(),
            selectedLiquidId
        );
        if (!supplied.feasible() || supplied.finalQout() + 0.0001f < planned.support().finalQout()) {
            throw new IllegalStateException("required support changed: " + supplied.diagnostics());
        }
    }

    public PlannerResult plan(
        PlannerRequest request,
        PlanningSnapshot snapshot,
        String selectedDrillId,
        String selectedLiquidId
    ) {
        return plan(request, snapshot, selectedDrillId, selectedLiquidId, null);
    }

    public PlannerResult plan(
        PlannerRequest request,
        PlanningSnapshot snapshot,
        String selectedDrillId,
        String selectedLiquidId,
        Consumer<PlanningProgress> progress
    ) {
        Objects.requireNonNull(snapshot, "planning snapshot");
        return plan(
            request,
            snapshot.world(),
            snapshot.inventory(),
            snapshot.capabilities(),
            snapshot.existingNetwork(),
            selectedDrillId,
            selectedLiquidId,
            progress
        );
    }

    /** Builds the data needed by the drill picker without running search. */
    public PlannerResult drillSelection(PlannerRequest request, PlanningSnapshot snapshot) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(snapshot, "planning snapshot");
        try {
            OrePatch patch = patchAnalyzer.analyze(snapshot.world(), request.seed());
            return result(
                false,
                patch,
                null,
                snapshot.capabilities(),
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
        } catch (IllegalArgumentException failure) {
            return failure(
                snapshot.world(),
                snapshot.capabilities(),
                null,
                DiagnosticCode.ORE_NOT_FOUND,
                failure.getMessage()
            );
        }
    }

    public PlannerResult plan(
        PlannerRequest request,
        WorldSnapshot world,
        Inventory inventory,
        CapabilitySnapshot capabilities,
        ExistingNetwork existingNetwork
    ) {
        return plan(request, world, inventory, capabilities, existingNetwork, null, null, null);
    }

    public PlannerResult plan(
        PlannerRequest request,
        WorldSnapshot world,
        CapabilitySnapshot capabilities,
        Inventory inventory,
        ExistingNetwork existingNetwork
    ) {
        return plan(request, world, inventory, capabilities, existingNetwork);
    }

    private PlannerResult plan(
        PlannerRequest request,
        WorldSnapshot world,
        Inventory inventory,
        CapabilitySnapshot capabilities,
        ExistingNetwork existingNetwork,
        String selectedDrillId,
        String selectedLiquidId,
        Consumer<PlanningProgress> progress
    ) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(inventory, "inventory");
        Objects.requireNonNull(capabilities, "capabilities");
        Objects.requireNonNull(existingNetwork, "existing network");

        OrePatch patch;
        try {
            patch = patchAnalyzer.analyze(world, request.seed());
        } catch (IllegalArgumentException failure) {
            return failure(world, capabilities, null, DiagnosticCode.ORE_NOT_FOUND, failure.getMessage());
        }
        TerrainSnapshot terrain = TerrainSnapshot.of(world);
        ExitResolution exit = exits.resolve(effectiveExit(request.exit(), patch), terrain, capabilities);
        if (!exit.resolved()) {
            return result(
                false, patch, exit, capabilities, null, null, null, List.of(), null, null, null,
                List.of(), exit.diagnostics(), null, OptimalityCertificate.none()
            );
        }
        if (!hasAvailableTransport(capabilities)) {
            return result(
                false, patch, exit, capabilities, null, null, null, List.of(), null, null, null,
                List.of(), List.of(PlannerDiagnostic.of(DiagnosticCode.NO_UNLOCKED_TRANSPORT)), null,
                OptimalityCertificate.none()
            );
        }

        List<DrillCandidate> generated = candidates.generate(
            patch, terrain, capabilities, request, selectedDrillId);
        PlanningSnapshot snapshot = new PlanningSnapshot(world, inventory, capabilities, existingNetwork);
        PatchSearchScope scope = captureScope(
            snapshot, patch, exit, request, selectedDrillId, selectedLiquidId);
        OptimalPlanningResult searched = optimalSearch.search(scope, generated, progress);
        if (searched.layout() == null) {
            ArrayList<PlannerDiagnostic> diagnostics = new ArrayList<>(searched.diagnostics());
            if (diagnostics.isEmpty()) {
                DiagnosticCode code = generated.isEmpty()
                    ? DiagnosticCode.NO_DRILL_CANDIDATE
                    : DiagnosticCode.NO_FEASIBLE_PLAN;
                diagnostics.add(PlannerDiagnostic.of(code));
            }
            return result(
                false, patch, exit, capabilities, null, null, null, searched.frontierBundles(),
                null, null, null, List.of(), diagnostics, searched.simulation(), searched.certificate()
            );
        }
        return compileSearched(request, snapshot, patch, exit, searched);
    }

    private PlannerResult compileSearched(
        PlannerRequest request,
        PlanningSnapshot snapshot,
        OrePatch patch,
        ExitResolution exit,
        OptimalPlanningResult searched
    ) {
        LiveSnapshot live = liveSnapshot(snapshot, request);
        ValidationResult validation = validator.validate(searched.graph(), live, searched.layout());
        ArrayList<PlannerDiagnostic> diagnostics = new ArrayList<>(searched.diagnostics());
        diagnostics.addAll(validation.diagnostics());
        if (request.targetQout() > searched.bundle().qout() + 0.0001f) {
            diagnostics.add(new PlannerDiagnostic(
                DiagnosticCode.PARTIAL_PLAN,
                Map.of(
                    "target", Float.toString(request.targetQout()),
                    "actual", Float.toString(searched.bundle().qout())
                )
            ));
        }
        List<BuildPlanCompiler.CompileRecord> records = validation.valid()
            ? sequencer.sequence(compiler.compile(searched.graph(), searched.layout()))
            : List.of();
        BudgetSelection selection = budget.select(
            searched.bundle(),
            new BudgetSnapshot(
                snapshot.inventory(),
                request.budgetMode() == PlannerRequest.BudgetMode.INFINITE_RESOURCES
            )
        );
        diagnostics.addAll(selection.diagnostics());
        return result(
            validation.valid() && selection.hasSelection(),
            patch,
            exit,
            snapshot.capabilities(),
            searched.layout(),
            searched.graph(),
            PlannerResult.SupportPlanView.of(searched.support()),
            searched.frontierBundles(),
            selection,
            upgradePlan(snapshot.world(), searched.layout(), searched.graph(), request),
            validation,
            records,
            diagnostics,
            searched.simulation(),
            searched.certificate()
        );
    }

    private PatchSearchScope captureScope(
        PlanningSnapshot snapshot,
        OrePatch patch,
        ExitResolution exit,
        PlannerRequest request,
        String selectedDrillId,
        String selectedLiquidId
    ) {
        SimulationFidelity fidelity = modelFidelity(snapshot.capabilities());
        boolean modelComplete = fidelity != SimulationFidelity.UNSUPPORTED
            && snapshot.existingNetwork().links().isEmpty()
            && snapshot.existingNetwork().traversableTiles().isEmpty();
        return PatchSearchScope.capture(
            snapshot.world(),
            patch,
            snapshot.capabilities(),
            snapshot.inventory(),
            snapshot.existingNetwork(),
            exit.anchor(),
            request,
            selectedDrillId,
            selectedLiquidId,
            fidelity,
            modelComplete
        );
    }

    private SimulationFidelity modelFidelity(CapabilitySnapshot capabilities) {
        SimulationFidelity weakest = SimulationFidelity.EXACT_SHADOW;
        for (CapabilityDescriptor descriptor : capabilities.descriptors().values()) {
            if (!descriptor.states().contains(CapabilityState.AVAILABLE_NOW)) continue;
            SimulationFidelity admitted = switch (descriptor.kind()) {
                case ITEM_TRANSPORT -> descriptor.spec() instanceof ItemTransportSpec spec
                    ? spec.simulationFidelity()
                    : SimulationFidelity.UNSUPPORTED;
                case DRILL -> descriptor.spec() instanceof DrillSpec
                    ? SimulationFidelity.BOUNDED_MODEL
                    : SimulationFidelity.UNSUPPORTED;
                case LIQUID_TRANSPORT -> descriptor.spec() instanceof LiquidTransportSpec
                    ? SimulationFidelity.BOUNDED_MODEL
                    : SimulationFidelity.UNSUPPORTED;
                case POWER_CONNECTOR -> descriptor.spec() instanceof PowerConnectorSpec
                    ? SimulationFidelity.BOUNDED_MODEL
                    : SimulationFidelity.UNSUPPORTED;
                case LIQUID_PROVIDER -> descriptor.spec() instanceof LiquidProviderSpec
                    ? SimulationFidelity.BOUNDED_MODEL
                    : SimulationFidelity.UNSUPPORTED;
                case UNKNOWN -> SimulationFidelity.UNSUPPORTED;
            };
            if (admitted.ordinal() > weakest.ordinal()) weakest = admitted;
        }
        return weakest;
    }

    private LiveSnapshot liveSnapshot(PlanningSnapshot snapshot, PlannerRequest request) {
        return new LiveSnapshot(
            TerrainSnapshot.of(snapshot.world()),
            snapshot.inventory(),
            snapshot.capabilities(),
            request.budgetMode() == PlannerRequest.BudgetMode.INFINITE_RESOURCES
        );
    }

    private ExitPort effectiveExit(ExitPort requested, OrePatch patch) {
        String generatedId = requested.side().name().toLowerCase(Locale.ROOT)
            + "-" + requested.bias().name().toLowerCase(Locale.ROOT);
        return requested.id().equals(generatedId)
            ? ExitPort.forPreference(requested.side(), requested.bias(), patch)
            : requested;
    }

    private boolean hasAvailableTransport(CapabilitySnapshot capabilities) {
        return capabilities.descriptors().values().stream().anyMatch(descriptor ->
            descriptor.kind() == CapabilityKind.ITEM_TRANSPORT
                && descriptor.states().contains(CapabilityState.AVAILABLE_NOW)
        );
    }

    private UpgradePlan upgradePlan(
        WorldSnapshot world,
        autodrillnext.model.MiningLayout layout,
        PlanGraph graph,
        PlannerRequest request
    ) {
        LinkedHashMap<TileKey, String> existing = new LinkedHashMap<>();
        for (Map.Entry<TileKey, autodrillnext.world.TileState> entry : world.tiles().entrySet()) {
            if (entry.getValue().existingBlock() != null) {
                existing.put(entry.getKey(), entry.getValue().existingBlock());
            }
        }
        LinkedHashMap<TileKey, String> target = new LinkedHashMap<>();
        for (DrillCandidate candidate : layout.candidates()) {
            for (TileKey tile : candidate.footprint().tiles()) {
                target.put(tile, candidate.drillId().value());
            }
        }
        for (PlanEdge edge : graph.edges()) {
            if (edge.kind() == EdgeKind.SOURCE_EDGE
                || edge.kind() == EdgeKind.SINK_EDGE
                || edge.kind() == EdgeKind.EXISTING_EDGE) {
                continue;
            }
            for (TileKey tile : edge.footprint()) target.put(tile, edge.transportId());
        }
        return upgrades.diff(
            existing,
            target,
            AutoDrillNEXTPlanRecord.empty(world.revision()),
            request
        );
    }

    private PlannerResult failure(
        WorldSnapshot world,
        CapabilitySnapshot capabilities,
        ExitResolution exit,
        DiagnosticCode code,
        String detail
    ) {
        Map<String, String> arguments = detail == null ? Map.of() : Map.of("detail", detail);
        return result(
            false, null, exit, capabilities, null, null, null, List.of(), null, null, null,
            List.of(), List.of(new PlannerDiagnostic(code, arguments)), null,
            OptimalityCertificate.none()
        );
    }

    private PlannerResult result(
        boolean compileReady,
        OrePatch patch,
        ExitResolution exit,
        CapabilitySnapshot capabilities,
        autodrillnext.model.MiningLayout layout,
        PlanGraph graph,
        PlannerResult.SupportPlanView support,
        List<ServiceBundle> frontier,
        BudgetSelection budget,
        UpgradePlan upgrade,
        ValidationResult validation,
        List<BuildPlanCompiler.CompileRecord> compileRecords,
        List<PlannerDiagnostic> diagnostics,
        autodrillnext.simulation.SimulationResult itemSimulation,
        OptimalityCertificate certificate
    ) {
        return new PlannerResult(
            compileReady,
            patch,
            exit,
            capabilities,
            layout,
            graph,
            support,
            frontier,
            budget,
            upgrade,
            validation,
            compileRecords,
            unique(diagnostics),
            itemSimulation,
            certificate
        );
    }

    private List<PlannerDiagnostic> unique(List<PlannerDiagnostic> diagnostics) {
        return List.copyOf(new LinkedHashSet<>(diagnostics));
    }
}
