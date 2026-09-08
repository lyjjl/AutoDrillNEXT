# Local Mining Planner Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use subagent-driven-development (recommended) or executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the three legacy filling algorithms with a deterministic, testable Local Mining Planner targeting Mindustry v159.7, including capability discovery, terrain-aware routing, support planning, budgeted partial bundles, upgrades, diagnostics, and English/Simplified Chinese i18n.

**Architecture:** Keep the solver pure Java and make it consume immutable model/world snapshots. Concentrate Mindustry `Block`, `Tile`, `Team`, `Build`, `BuildPlan`, content, consumer, and rules access in `mindustryapi`, capability adapters, and compile/final-validation code. The game entrypoint supplies snapshots to one `LocalMiningPlanner`; the old `filler` package is deleted as part of the break change.

**Tech Stack:** Java 17, Gradle, Mindustry v159.7, JUnit 5, standard Java collections in the solver, Arc/Mindustry collections only inside integration code, Mindustry bundle properties for i18n.

**Spec:** `docs/superpowers/specs/2026-09-07-local-mining-planner-design.md`

## Global Constraints

- Compile against Mindustry `v159.7`; do not add a v158 compatibility layer.
- The solver must not import Mindustry or Arc classes.
- Domain solver/model code may not touch Mindustry or Arc classes. The `AutoDrillNEXT`/`ui` shell may import event and scene types only; it must obtain content, world, placement, budget, and localized content data through facades.
- Delete `BridgeDrill`, `OptimizationDrill`, `WallDrill`, `Util`, and `Direction` from `src/autodrillnext/filler` before the final verification.
- Unknown content is quarantined, never guessed or selected automatically.
- `AVAILABLE_NOW` and `AFFORDABLE_NOW` are separate facts; locked content must not enter a current plan.
- Item transport uses nominal capacity and fixed-topology max-flow; liquid transport uses demand satisfaction, not a fabricated fixed conduit capacity.
- Existing player buildings are reusable/obstacles but not removable without AutoDrillNEXT ownership plus an explicit destructive request.
- Every user-facing string uses a bundle key; English and `bundle_zh_CN.properties` keys must stay identical.
- Run targeted tests after each task and the complete verification suite only after the final integration task; skip formatter/linter runs during intermediate tasks.

---

### Task 1: Toolchain and synthetic regression harness

**Files:**
- Modify: `build.gradle`
- Modify: `gradle/wrapper/gradle-wrapper.properties`
- Modify: `.github/workflows/prTest.yml`
- Modify: `.github/workflows/commitTest.yml`
- Create: `src/test/java/autodrillnext/testsupport/SyntheticWorldBuilder.java`
- Create: `src/test/java/autodrillnext/testsupport/SyntheticBlock.java`
- Create: `src/test/java/autodrillnext/testsupport/SyntheticScenario.java`
- Create: `src/test/java/autodrillnext/i18n/BundleParityTest.java`
- Modify: `assets/bundles/bundle.properties`
- Create: `assets/bundles/bundle_zh_CN.properties`
- Create: `src/test/java/autodrillnext/world/SyntheticScenarioTest.java`

**Interfaces:**
- Produces deterministic synthetic scenarios with ore identity, solid/liquid obstacles, blocked cells, translation offsets, available blocks, inventory, and twelve exit regions.
- `SyntheticScenario` exposes immutable Java collections; it must not import Mindustry or Arc.
- `BundleParityTest` reads `assets/bundles/bundle.properties` and `assets/bundles/bundle_zh_CN.properties`, ignores blank/comment lines, and fails with the exact missing key set when they differ.

- [ ] **Step 1: Fix the build runtime before source migration**

Set the Gradle wrapper to a release that runs on the workstation JDK while retaining Java 17 compilation, set `mindustryVersion = 'v159.7'`, add JUnit Jupiter test dependencies, enable `useJUnitPlatform()`, and remove the old workflow's JDK 16 mismatch. Keep Android packaging only if the selected Gradle/Jabel combination can compile the same Java source.

```groovy
ext {
    mindustryVersion = 'v159.7'
}

dependencies {
    testImplementation platform('org.junit:junit-bom:5.10.3')
    testImplementation 'org.junit.jupiter:junit-jupiter'
}

test {
    useJUnitPlatform()
}
```

- [ ] **Step 2: Write the first failing synthetic-world tests**

Cover rectangular ore, wall-adjacent ore, island obstacle, water obstacle, narrow exit, and translated copies. Assert observable scenario facts rather than builder internals.

```java
@Test
void translatedScenarioKeepsTheSameOreTopology() {
    SyntheticScenario original = SyntheticScenario.rectangle(8, 5);
    SyntheticScenario shifted = original.translated(137, -41);

    assertEquals(original.oreCellsRelativeToSeed(), shifted.oreCellsRelativeToSeed());
    assertEquals(original.obstacleCellsRelativeToSeed(), shifted.obstacleCellsRelativeToSeed());
}
```

- [ ] **Step 3: Add the English bundle and failing parity test**

Create `bundle_zh_CN.properties` with the current keys translated to Simplified Chinese. Add one new diagnostic key in both files so the parity test exercises newly introduced text.

- [ ] **Step 4: Run the focused tests and observe the expected failure**

Run: `./gradlew test --tests autodrillnext.world.SyntheticScenarioTest --tests autodrillnext.i18n.BundleParityTest`

Expected: the build either reports the currently unsupported toolchain first or fails because the new synthetic types/tests are not implemented; do not proceed until the failure is attributable to the requested harness.

- [ ] **Step 5: Implement the minimal scenario builder and bundle parser**

Use `java.util.Set<TileKey>`, `Map<TileKey, TileFixture>`, and value objects with stable equality. Translation must change absolute coordinates but not relative topology. Parse bundle keys without loading Mindustry runtime.

- [ ] **Step 6: Run the focused tests green**

Run: `./gradlew test --tests autodrillnext.world.SyntheticScenarioTest --tests autodrillnext.i18n.BundleParityTest`

Expected: all focused tests pass under the pinned Java/Gradle toolchain.

---

### Task 2: Capability registry, facades, specs, and adapters

**Files:**
- Create: `src/autodrillnext/mindustryapi/GameFacade.java`
- Create: `src/autodrillnext/mindustryapi/PlacementFacade.java`
- Create: `src/autodrillnext/mindustryapi/ResourceFacade.java`
- Create: `src/autodrillnext/mindustryapi/BuildPlanFacade.java`
- Create: `src/autodrillnext/capability/CapabilityRegistry.java`
- Create: `src/autodrillnext/capability/CapabilitySnapshot.java`
- Create: `src/autodrillnext/capability/TechnologyIndex.java`
- Create: `src/autodrillnext/capability/CompatibilityRegistry.java`
- Create: `src/autodrillnext/capability/SafetyPolicy.java`
- Create: `src/autodrillnext/capability/spec/*.java`
- Create: `src/autodrillnext/capability/adapter/*.java`
- Create: `src/test/java/autodrillnext/capability/CostVectorTest.java`
- Create: `src/test/java/autodrillnext/capability/CapabilityRegistryTest.java`
- Create: `src/test/java/autodrillnext/capability/AvailabilityTest.java`

**Interfaces:**
- `CapabilityRegistry.snapshot(GameSnapshotInput)` returns `CapabilitySnapshot` containing only immutable model descriptors.
- `CapabilityAdapter` is the only adapter contract that receives a Mindustry `Block`; its output is a model spec.
- `CostVector` supports `plus`, `componentWiseAtMost`, `scaledRulesCost`, `isEmpty`, and deterministic item iteration.
- `CapabilityDescriptor` reports `DISCOVERED`, `SUPPORTED`, `AVAILABLE_NOW`, `AFFORDABLE_NOW`, or an explicit quarantine reason without collapsing them into one boolean.
- Adapters cover `Drill`, `BeamDrill`, `Conveyor`, `Duct`, `ItemBridge`, `DuctBridge`, `PowerNode`, `BeamNode`, `Conduit`, `LiquidBridge`, `Pump`, and `SolidPump` class families.

- [ ] **Step 1: Write failing cost and capability tests**

Test component-wise inventory, `round(buildCostMultiplier * amount)`, infinite resources, locked-but-supported transport, environment rejection, explicit allow/deny policy, unknown custom blocks, and a subclass of a known block family.

```java
@Test
void insufficientOneComponentOnlyRemovesAffordableState() {
    CostVector cost = CostVector.of(Map.of(ItemId.of("copper"), 20, ItemId.of("graphite"), 5));
    Inventory inventory = Inventory.of(Map.of(ItemId.of("copper"), 100, ItemId.of("graphite"), 4));

    assertTrue(cost.isComponentWiseAtMost(inventory) == false);
    assertTrue(cost.hasComponent(ItemId.of("copper")));
}
```

- [ ] **Step 2: Implement pure capability specs and cost semantics**

Use model IDs and immutable Java collections. Represent support requirements as `POWER`, `LIQUID`, and `BOOSTER`; filter liquids are enumerated from the supplied content snapshot. Store stable diagnostic codes separately from localized text.

- [ ] **Step 3: Implement the registry and explicit safety policy**

Discovery iterates all supplied blocks/liquids, picks the most-specific registered adapter, then evaluates unlock, placeability, rule limits, and affordability in separate passes. Unknown blocks become `UNKNOWN_SEMANTICS` and cannot produce a supported spec. Explicit deny wins over allow; a class registration is required for non-family semantics.

- [ ] **Step 4: Implement v159.7 facade/adapters**

Keep all direct Mindustry imports in the allowed packages. Use `Vars.content.blocks()` and `Vars.content.liquids()`, `unlockedNow()`, `Block.isPlaceable()`, `Block.isOverPlacementLimit(team)`, `Team.items()`, `Block.requirements`, consumer fields, drill methods, and block-specific range/transport fields. Do not expose engine objects through facade return values.

- [ ] **Step 5: Run the focused capability tests**

Run: `./gradlew test --tests autodrillnext.capability.CostVectorTest --tests autodrillnext.capability.CapabilityRegistryTest --tests autodrillnext.capability.AvailabilityTest`

Expected: all state-layer, cost, subclass-discovery, and quarantine assertions pass.

---

### Task 3: Terrain snapshots, ore patches, twelve exits, and overlay

**Files:**
- Create: `src/autodrillnext/world/OrePatch.java`
- Create: `src/autodrillnext/world/OrePatchAnalyzer.java`
- Create: `src/autodrillnext/world/TerrainSnapshot.java`
- Create: `src/autodrillnext/world/TerrainRevision.java`
- Create: `src/autodrillnext/world/PlanOverlay.java`
- Create: `src/autodrillnext/model/PlannerRequest.java`
- Create: `src/autodrillnext/model/ExitPort.java`
- Create: `src/autodrillnext/model/ExitAnchor.java`
- Create: `src/autodrillnext/model/DiagnosticCode.java`
- Create: `src/autodrillnext/model/PlannerDiagnostic.java`
- Create: `src/autodrillnext/solver/ExitFeasibilitySolver.java`
- Create: `src/test/java/autodrillnext/world/OrePatchAnalyzerTest.java`
- Create: `src/test/java/autodrillnext/world/PlanOverlayTest.java`
- Create: `src/test/java/autodrillnext/solver/ExitFeasibilitySolverTest.java`

**Interfaces:**
- `OrePatchAnalyzer.analyze(WorldSnapshot, TileKey)` returns a patch based only on ore identity and 8-neighbor topology.
- `TerrainSnapshot` contains `TileState` values and a revision; it never derives patch membership from buildability.
- `ExitPort.forPreference(Side, Bias, OrePatch)` returns a goal region with multiple candidate anchors.
- `ExitFeasibilitySolver.resolve(ExitPort, TerrainSnapshot, CapabilitySnapshot)` returns either a sink anchor or structured diagnostics.
- `PlanOverlay.canReserve(PlacementFootprint)` and `reserve(PlanPlacement)` implement planned-vs-planned collision and bridge/config reservations.

- [ ] **Step 1: Write failing topology and goal-region tests**

Assert that a wall placement proxy is irrelevant to patch membership, diagonal ore behavior is deterministic, shifted maps produce equivalent patch shape, blocked first exit anchors fall through to another anchor in the same third, and no valid anchor returns `NO_VALID_SINK`.

- [ ] **Step 2: Implement immutable world/model values**

Represent absolute tile coordinates in `TileKey`, relative topology in `OrePatch`, and tile facts in `TileState`. Include fogged, solid, deep-liquid, existing block/team, floor ore, and wall ore facts.

- [ ] **Step 3: Implement patch analysis and snapshot bounds**

Use bounded BFS over 8 neighbors and a request max tile limit. Build the snapshot over patch bounds plus maximum transport range and support margin. Do not call `Build.validPlace` during patch analysis.

- [ ] **Step 4: Implement goal regions and overlay reservations**

Map twelve UI preferences to the top/bottom/left/right thirds plus corner bias. Reserve ordinary footprints, bridge endpoints, config pairs, and planned links independently.

- [ ] **Step 5: Run focused world/exit tests**

Run: `./gradlew test --tests autodrillnext.world.OrePatchAnalyzerTest --tests autodrillnext.world.PlanOverlayTest --tests autodrillnext.solver.ExitFeasibilitySolverTest`

Expected: topology, translation, fallback-anchor, and collision tests pass.

---

### Task 4: Drill candidates, production variants, and conflict layouts

**Files:**
- Create: `src/autodrillnext/model/DrillCandidate.java`
- Create: `src/autodrillnext/model/SupportVariant.java`
- Create: `src/autodrillnext/model/PlacementFootprint.java`
- Create: `src/autodrillnext/model/ProductionEstimate.java`
- Create: `src/autodrillnext/solver/CandidateGenerator.java`
- Create: `src/autodrillnext/solver/MiningLayoutSolver.java`
- Create: `src/autodrillnext/solver/LocalSearch.java`
- Create: `src/test/java/autodrillnext/solver/CandidateGeneratorTest.java`
- Create: `src/test/java/autodrillnext/solver/MiningLayoutSolverTest.java`

**Interfaces:**
- `CandidateGenerator.generate(OrePatch, TerrainSnapshot, CapabilitySnapshot, PlannerRequest)` enumerates every legal anchor and meaningful rotation for each supported drill.
- `DrillCandidate` contains footprint, covered ore cells/items, nominal item production, mandatory support, optional variants, cost, and conflict IDs.
- `MiningLayoutSolver.seeds(List<DrillCandidate>, PlannerRequest)` returns deterministic non-overlapping layouts; selection objective is downstream-service potential, not merely covered-cell count.
- `LocalSearch.improve(Layout, RouteEvaluator)` may swap/remove/add candidates but is bounded by request time/iteration limits.

- [ ] **Step 1: Write failing candidate tests**

Cover ordinary drills, BeamDrill direction/range, rotation enumeration, full-footprint legality, planned overlap rejection, irregular wall space, modded drill subclasses, and a candidate whose extra coverage has zero reachable output.

- [ ] **Step 2: Implement drill candidate model and adapters' model hooks**

Use `DrillSpec.production` to compute `60 / drillTime * oreCount`; represent wall-drill coverage through the adapter output rather than solver type checks. Expand optional liquid boosters into explicit variants.

- [ ] **Step 3: Implement candidate enumeration**

Scan the snapshot bounding region, call `PlacementFacade` through the integration generator boundary for actual block legality, then apply `PlanOverlay` for candidate-local collisions. Emit candidates sorted by stable block ID, coordinate, rotation, and variant ID.

- [ ] **Step 4: Build conflict graph and bounded seed search**

Store candidate IDs and adjacency sets. Generate greedy production seeds, cheapest seeds, and spatially diverse seeds; never recurse over all permutations of the complete candidate set.

- [ ] **Step 5: Run focused candidate/layout tests**

Run: `./gradlew test --tests autodrillnext.solver.CandidateGeneratorTest --tests autodrillnext.solver.MiningLayoutSolverTest`

Expected: every selected candidate is legal/non-overlapping, translation does not change rank systematically, and wall drills use adapter semantics.

---

### Task 5: Shared item routing, bridges, max-flow, capacity repair, and compilation

**Files:**
- Create: `src/autodrillnext/model/PlanGraph.java`
- Create: `src/autodrillnext/model/PlanNode.java`
- Create: `src/autodrillnext/model/PlanEdge.java`
- Create: `src/autodrillnext/model/FlowAssignment.java`
- Create: `src/autodrillnext/model/PlanCost.java`
- Create: `src/autodrillnext/model/EdgeKind.java`
- Create: `src/autodrillnext/solver/ItemRouteSolver.java`
- Create: `src/autodrillnext/solver/FlowSolver.java`
- Create: `src/autodrillnext/solver/CapacityRepair.java`
- Create: `src/autodrillnext/compile/BuildPlanCompiler.java`
- Create: `src/autodrillnext/compile/BuildSequencer.java`
- Create: `src/autodrillnext/compile/FinalValidator.java`
- Create: `src/test/java/autodrillnext/solver/ItemRouteSolverTest.java`
- Create: `src/test/java/autodrillnext/solver/FlowSolverTest.java`
- Create: `src/test/java/autodrillnext/solver/CapacityRepairTest.java`
- Create: `src/test/java/autodrillnext/compile/BuildPlanCompilerTest.java`

**Interfaces:**
- `ItemRouteSolver.route(Layout, ExitAnchor, TerrainSnapshot, CapabilitySnapshot, ExistingNetwork)` produces a shared `PlanGraph` with `GROUND_EDGE`, `BRIDGE_EDGE`, and `EXISTING_EDGE` edges.
- `FlowSolver.assign(PlanGraph)` returns exact nominal item flow, per-edge utilization, and `Qout`.
- `CapacityRepair.repair(PlanGraph, FlowAssignment, PlannerRequest)` evaluates parallel lanes, cheaper detours, higher-tier transport, and source splits with lexicographic ranking: Qout, cost, space, complexity.
- `BuildPlanCompiler.compile(PlanGraph)` returns model compile records containing block identity, coordinate, rotation, config, and dependency bundle; only `BuildPlanFacade` turns records into Mindustry `BuildPlan` objects.
- `FinalValidator.validate(PlanGraph, LiveSnapshot)` returns all violations, never a partial invalid build list.

- [ ] **Step 1: Write failing routing tests**

Test shared trunk reuse, bridge jump edges with endpoint-only occupancy, bridge lockout, bridge material shortage, wall detour, narrow exits, one-lane overload, two cheap lanes beating one expensive lane, and no downstream nodes after the sink.

```java
@Test
void twoCheapLanesBeatOneExpensiveLaneWhenSpaceAllows() {
    PlanGraph graph = routeWithCapacityChoices(9.0f);
    PlanGraph repaired = capacityRepair.repair(graph, graph.flow(), request());

    assertEquals(List.of("cheap", "cheap"), repaired.transportIds());
    assertTrue(repaired.qOut() >= 9.0f);
}
```

- [ ] **Step 2: Implement graph values and deterministic route expansion**

Use integer tile coordinates and direction state. Ground moves cost footprint/space; bridge moves require co-linear endpoints within adapter range and reserve only endpoints plus a config pair. Existing edges receive zero build cost and reuse discount.

- [ ] **Step 3: Implement fixed-topology max-flow**

Construct a super-source from drill production and a single sink for the selected exit. Use residual capacities and stable edge ordering. Record assigned flow and reject any edge where flow exceeds usable capacity.

- [ ] **Step 4: Implement capacity repair and route/local-search loop**

Try the bounded repair alternatives, recompute flow for each topology, and preserve the highest lexicographic result. Re-route only local conflicts; use `transportHeadroom` from the request instead of an adapter magic number.

- [ ] **Step 5: Implement BuildPlan compiler, sequencing, and final validator**

Compile bridge configs as relative `Point2`, power links as `Point2[]`, and preserve bridge/config dependency pairs. Sequence ADD/CONFIGURE/CONNECT operations before replacement/removal. Validate live placement, block limits, budget, links, reachability, flow, and footprint collision immediately before commit.

- [ ] **Step 6: Run focused routing/compiler tests**

Run: `./gradlew test --tests autodrillnext.solver.ItemRouteSolverTest --tests autodrillnext.solver.FlowSolverTest --tests autodrillnext.solver.CapacityRepairTest --tests autodrillnext.compile.BuildPlanCompilerTest`

Expected: all routing decisions are explainable, max-flow is capacity-safe, and bridge/power configs compile without orphan endpoints.

---

### Task 6: Power, liquid demand, providers, and support-aware optimization

**Files:**
- Create: `src/autodrillnext/model/SupportRequirement.java`
- Create: `src/autodrillnext/model/SupportPlan.java`
- Create: `src/autodrillnext/model/LiquidDemand.java`
- Create: `src/autodrillnext/model/PowerDemand.java`
- Create: `src/autodrillnext/solver/SupportPlanner.java`
- Create: `src/autodrillnext/solver/LiquidSupportSolver.java`
- Create: `src/autodrillnext/solver/PowerSupportSolver.java`
- Create: `src/test/java/autodrillnext/solver/SupportPlannerTest.java`
- Create: `src/test/java/autodrillnext/solver/LiquidSupportSolverTest.java`
- Create: `src/test/java/autodrillnext/solver/PowerSupportSolverTest.java`

**Interfaces:**
- `SupportPlanner.solve(Layout, PlanGraph, TerrainSnapshot, CapabilitySnapshot, ExistingNetwork)` evaluates base, mandatory-support, and optional-booster variants using final `Qout`.
- `PowerSupportSolver` connects mandatory power consumers to reachable existing power graphs and may add `PowerNode`/`BeamNode`; it never creates a power provider.
- `LiquidSupportSolver` checks provider supply against aggregate demand times margin and routes liquid by existence/length/terrain/shared-network cost; it does not assign a fixed conduit capacity.
- `SupportPlan` carries dependency edges of kind `POWER_LINK` and `LIQUID_SUPPORT`, plus explicit absence diagnostics.

- [ ] **Step 1: Write failing support tests**

Cover mandatory power with no source, spare power margin, PowerNode versus BeamNode link semantics, fixed liquid consumer, filter liquid enumeration, provider shortage, and a booster whose theoretical production rises while final item Qout falls due to occupied lane space.

- [ ] **Step 2: Implement consumer-normalized support models**

Convert internal tick values to per-second model demand at the adapter boundary. Preserve `optional` and `booster` flags. Enumerate all legal filter liquids from the capability content snapshot.

- [ ] **Step 3: Implement existing-power and liquid-provider graph checks**

Use existing graph edges at zero construction cost. Add only connector/support blocks with explicit links and dependency closure; return `NO_POWER_SOURCE` or `NO_LIQUID_SOURCE` when mandatory demand cannot be served.

- [ ] **Step 4: Integrate support with item routing repair**

Run at most three deterministic iterations of item route → support → item capacity repair. Reject optional boosts based on final Qout, not drill-only production.

- [ ] **Step 5: Run focused support tests**

Run: `./gradlew test --tests autodrillnext.solver.SupportPlannerTest --tests autodrillnext.solver.LiquidSupportSolverTest --tests autodrillnext.solver.PowerSupportSolverTest`

Expected: no fake support, no liquid fixed-capacity claims, and the no-boost counterexample selects `NO BOOST`.

---

### Task 7: Dependency bundles, scarcity budgets, Pareto profiles, and partial plans

**Files:**
- Create: `src/autodrillnext/model/DependencyKind.java`
- Create: `src/autodrillnext/model/ServiceBundle.java`
- Create: `src/autodrillnext/model/PlannerResult.java`
- Create: `src/autodrillnext/solver/ParetoPlanner.java`
- Create: `src/autodrillnext/solver/BudgetPlanner.java`
- Create: `src/test/java/autodrillnext/solver/ParetoPlannerTest.java`
- Create: `src/test/java/autodrillnext/solver/BudgetPlannerTest.java`
- Create: `src/test/java/autodrillnext/model/DependencyClosureTest.java`

**Interfaces:**
- `ServiceBundle.isDependencyClosed()` rejects orphan drill, bridge endpoint, mandatory support, and route components.
- `ParetoPlanner.frontier(candidates)` retains 3–8 nondominated plans across Qout, component cost, occupied space, and complexity.
- `BudgetPlanner.select(frontier, BudgetSnapshot, PlannerProfile)` uses component-wise affordability and selects a complete positive-value bundle; if no bundle is affordable it returns `BUDGET_DEFICIT` without build plans.
- `PlannerResult` contains selected graph, frontier summaries, diagnostics, profile, and compile readiness.

- [ ] **Step 1: Write failing closure and budget tests**

Cover half a bridge, drill-without-output, mandatory support without provider, advanced route unaffordable but cheap route affordable, scarcity weighting with low graphite/high copper, and selecting the maximum marginal Qout complete subgraph under a small budget.

- [ ] **Step 2: Implement dependency graph and service bundles**

Represent `ITEM_FLOW`, `REQUIRES`, `POWER_LINK`, `LIQUID_SUPPORT`, and `CONFIG_PAIR`; compute transitive closure and discard any candidate with a missing dependency or invalid counterpart.

- [ ] **Step 3: Implement Pareto dominance and profiles**

Use deterministic tie-breakers after dominance. Keep `CHEAP`, `BALANCED`, and `MAX_OUTPUT` labels as stable profile codes; localize their display names only in UI.

- [ ] **Step 4: Implement budget selection and scarcity scalar**

Use `CostVector <= Inventory` for legality. Use `1 / (available - reserve + epsilon)` only to rank candidates that are all legal; never use the scalar to make an illegal plan legal.

- [ ] **Step 5: Run focused budget tests**

Run: `./gradlew test --tests autodrillnext.solver.ParetoPlannerTest --tests autodrillnext.solver.BudgetPlannerTest --tests autodrillnext.model.DependencyClosureTest`

Expected: every selected partial result is dependency-closed and every unavailable result has a structured budget diagnostic.

---

### Task 8: Existing network ownership and upgrade graph diff

**Files:**
- Create: `src/autodrillnext/world/ExistingNetworkScanner.java`
- Create: `src/autodrillnext/model/AutoDrillNEXTPlanRecord.java`
- Create: `src/autodrillnext/model/UpgradeAction.java`
- Create: `src/autodrillnext/model/UpgradePlan.java`
- Create: `src/autodrillnext/solver/UpgradePlanner.java`
- Create: `src/test/java/autodrillnext/world/ExistingNetworkScannerTest.java`
- Create: `src/test/java/autodrillnext/solver/UpgradePlannerTest.java`

**Interfaces:**
- `ExistingNetworkScanner.scan(WorldSnapshot, CapabilitySnapshot)` classifies existing blocks through adapters and marks them reusable without assigning ownership.
- `AutoDrillNEXTPlanRecord` stores region, placement fingerprints, planner-owned block IDs, and revision.
- `UpgradePlanner.diff(existing, target, record, request)` returns `KEEP`, `ADD`, `RECONFIGURE`, `REPLACE`, and `REMOVE` actions; unknown player structures cannot produce REMOVE.
- `UpgradePlan.sequence()` orders ADD support/capacity → CONFIGURE → CONNECT → REPLACE → owned REMOVE.

- [ ] **Step 1: Write failing ownership/upgrade tests**

Cover existing zero-cost trunk reuse, unknown player conveyor obstacle/reuse, copper-to-titanium replacement, one-to-two lane expansion, better drill suggestion, bridge-to-cheap-route replacement, and destructive flag/ownership requirements.

- [ ] **Step 2: Implement existing graph scan and ownership fingerprint model**

Use adapter classifications and live config snapshots. Update ownership only from completed build events tied to a planner record; never infer ownership from matching block type alone.

- [ ] **Step 3: Implement graph diff and safe sequence**

Compute target-vs-existing actions, preserve running output by adding capacity first, and omit all destructive actions unless both explicit request flag and ownership fingerprint match.

- [ ] **Step 4: Run focused upgrade tests**

Run: `./gradlew test --tests autodrillnext.world.ExistingNetworkScannerTest --tests autodrillnext.solver.UpgradePlannerTest`

Expected: upgrades are suggestions by default, reusable existing edges cost zero, and unrelated player buildings are never removed.

---

### Task 9: LocalMiningPlanner integration, UI diagnostics, i18n migration, and break cutover

**Files:**
- Create: `src/autodrillnext/LocalMiningPlanner.java`
- Create: `src/autodrillnext/ui/ExitSelector12.java`
- Create: `src/autodrillnext/ui/PlanPreview.java`
- Create: `src/autodrillnext/ui/PlannerDiagnostics.java`
- Modify: `src/autodrillnext/AutoDrillNEXT.java`
- Modify: `assets/bundles/bundle.properties`
- Modify: `assets/bundles/bundle_zh_CN.properties`
- Delete: `src/autodrillnext/filler/BridgeDrill.java`
- Delete: `src/autodrillnext/filler/OptimizationDrill.java`
- Delete: `src/autodrillnext/filler/WallDrill.java`
- Delete: `src/autodrillnext/filler/Util.java`
- Delete: `src/autodrillnext/filler/Direction.java`
- Create: `src/test/java/autodrillnext/LocalMiningPlannerIntegrationTest.java`
- Create: `src/test/java/autodrillnext/ui/PlannerDiagnosticsTest.java`

**Interfaces:**
- `LocalMiningPlanner.plan(PlannerRequest)` executes snapshot → capability → patch → exit → candidates → layout → item route → support → repair → flow → bundles → budget → upgrade → final validation → compile.
- `AutoDrillNEXT` is a thin Mindustry event/UI shell; it asks the planner for a `PlannerResult` and submits only `BuildPlanCompiler` output after `FinalValidator` approval.
- `PlannerDiagnostics` maps stable `DiagnosticCode` values to bundle keys; it never stores localized strings as state.
- `ExitSelector12` maps UI direction buttons to `ExitPort` values; preview and commit consume the same compiled model plan.

- [ ] **Step 1: Write failing integration and i18n UI tests**

Assert a complete synthetic plan has drill-to-sink reachability, no invalid overlap, no orphan config pair, all visible labels resolve in English and Chinese, diagnostic codes remain unchanged across locales, and a green plan is compile-ready.

- [ ] **Step 2: Implement the orchestration facade**

Compose the previously implemented collaborators using constructor injection. Snapshot inventory on every refresh, cache routing only by terrain/capability/exit/transport fingerprint, and return structured no-solution results rather than throwing for normal infeasibility.

- [ ] **Step 3: Replace the existing UI action paths**

Remove direct `Blocks.*`, `BridgeDrill`, `OptimizationDrill`, `WallDrill`, localized-label setting keys, and hardcoded user text from `AutoDrillNEXT`. Populate buttons from capability descriptors, show twelve exit regions, render GREEN/YELLOW/RED/GRAY state, and show Qout, theoretical drill Q, utilization, cost, support demand, and diagnostics.

- [ ] **Step 4: Complete English/Simplified Chinese bundle migration**

Add keys for exits, profiles, statuses, metrics, diagnostics, upgrade actions, support failures, quarantine, and preview explanations to both bundles. Use stable setting keys and bundle formatting for variables. Keep existing German/Russian files as fallback-only locales without adding untranslated new Java strings.

- [ ] **Step 5: Delete the old implementation as a clean cutover**

Remove the entire `src/autodrillnext/filler` package and all references. Do not add aliases, adapters named legacy, or a runtime fallback. The only rollback is the previous mod artifact, not a second algorithm in this source tree.

- [ ] **Step 6: Run integration and complete verification**

Run targeted integration tests first:

```bash
./gradlew test --tests autodrillnext.LocalMiningPlannerIntegrationTest --tests autodrillnext.ui.PlannerDiagnosticsTest
```

Then run the full verification:

```bash
./gradlew test
./gradlew compileJava
./gradlew jar
```

If the graphical Mindustry runtime is available, build the desktop jar and run `scripts/test.sh`; otherwise report pure-model tests, compiler/build verification, and game-runtime smoke separately.

Expected: all tests pass, v159.7 compiles, the jar contains the new planner and both bundles, no old filler classes remain, and every GREEN plan reaches `BuildPlan` compilation with valid configs.
