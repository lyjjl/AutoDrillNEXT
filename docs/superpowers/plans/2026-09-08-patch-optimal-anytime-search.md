# 矿区任意时刻最优搜索 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use subagent-driven-development (recommended) or executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在固定矿区快照和完整支持模型内运行任意时刻分支定界搜索：预算耗尽时诚实返回当前状态，有限域耗尽时才证明“针对该矿区最优”或“已证明无解”。

**Architecture:** 保留现有布局和路由求解器作为最多占总预算 25% 的热启动器，用验证后的方案初始化 incumbent；新增 `PatchOptimalSearch`，在同一优先队列中交错展开钻头布局状态与运输配置状态，并用放宽流网络计算安全乐观上界。物理运输图、目标顺序、支持分配和完整方案验证分别收敛到单一共享实现，生产入口不再自行比较方案或构造证书。

**Tech Stack:** Java 17、Gradle、JUnit 5、Mindustry v159.7、现有 `FlowSolver`、`FinalValidator`、`SupportPlanner`、`ItemSimulationEvaluator`，仅使用 JDK 集合和标准算法。

**Spec:** `docs/superpowers/specs/2026-09-08-patch-optimal-anytime-search-design.md`

## Global Constraints

- 最优性只覆盖本次 `WorldSnapshot` 的有限瓦片集合、`OrePatch`、出口、队伍、库存、预算模式、能力快照、现有网络、请求设置和所选钻头/液体；任一输入变化都使证书失效。
- 搜索循环共享一个绝对两秒截止时间；根状态必须先创建，热启动最多占总预算 25%，至少 75% 留给证明搜索。
- `PlannerRequest.maxIterations()` 只限制从证明队列弹出的非支配状态数，不得截断候选生成、运输配置域或支持变体域。
- `BALANCED`/`THROUGHPUT`、`LOW_COST`、`LOW_COMPLEXITY` 分别使用规格定义的目标顺序；所有主方案比较和上下界校验必须调用 `PlanObjectiveOrder`。
- 产出比较继续使用 `ObjectiveValue.outputRank()` 的 0.0001 件/秒桶；规范 ID 只作确定性平局。
- 未决配置可在放宽流网络中免费且同时可用；该松弛可以高估可达产出，绝不能低估。
- 固定半径、第一条路径、只保留最短路径、候选数量上限、随机取样和未经证明的距离规则不得进入证明搜索。
- `SimulationFidelity.UNSUPPORTED` 或无法界定的支持/端口语义允许产生验证后的 incumbent，但必须阻止 `PATCH_OPTIMAL` 和 `PROVEN_INFEASIBLE`。
- 不引入 ILP、SMT、CP-SAT、原生求解器或其他依赖。
- `autodrillnext.model` 与 `autodrillnext.solver` 不得导入 Arc 或 Mindustry 类型；运行时 API 继续留在现有 facade、adapter、compile 和 UI 边界。
- 中断、世界变化、关闭面板和切换矿区必须停止工作并清除旧证书；旧 worker 不得向新一代 UI 发布状态。
- 采用干净切换：生产路径迁移后删除旧 `OptimalMiningPlanner` 及不再使用的证明接口，不保留弃用别名或永假证书构造。
- 修改或删除任何导出构造器、方法或类型前先运行 LSP references；同一任务内迁移全部调用点，最终不得保留兼容重载。

## File Structure

### 新建文件

- `src/autodrillnext/model/SearchVerdict.java`：五种互斥搜索结论。
- `src/autodrillnext/model/SearchStopReason.java`：截止时间、状态上限、模型不完整和无停止原因。
- `src/autodrillnext/model/PatchSearchScope.java`：证书绑定的不可变矿区搜索输入。
- `src/autodrillnext/model/PlanObjectiveOrder.java`：唯一的 profile-aware 完整目标比较器。
- `src/autodrillnext/compile/PhysicalTransportGraphBuilder.java`：从实际运输放置重建端口、桥和流图。
- `src/autodrillnext/solver/TransportPlacementDomain.java`：有限运输配置域、互斥组和放宽图。
- `src/autodrillnext/solver/EvaluatedMiningPlan.java`：验证后的 incumbent 值对象。
- `src/autodrillnext/solver/PlanCandidateEvaluator.java`：流、支持、预算、物理和模拟的单一完整方案验证管线。
- `src/autodrillnext/model/SearchBudget.java`：在热启动和证明阶段传播单调时钟、局部截止时间与协作检查点。
- `src/autodrillnext/solver/PatchOptimalSearch.java`：任意时刻分支定界协调器。
- `src/test/java/autodrillnext/solver/PatchSearchOracle.java`：仅测试侧使用的独立小域暴力枚举器。
- `src/test/java/autodrillnext/solver/TransportPlacementDomainTest.java`：运输域完整性和松弛安全性。
- `src/test/java/autodrillnext/solver/PlanCandidateEvaluatorTest.java`：incumbent 验证边界。
- `src/test/java/autodrillnext/solver/PatchOptimalSearchTest.java`：oracle、verdict、截止时间和确定性。

### 修改文件

- `src/autodrillnext/model/ObjectiveValue.java`、`OptimalityCertificate.java`、`PlanningProgress.java`、`PlannerResult.java`。
- `src/autodrillnext/solver/BudgetPlanner.java`、`SupportPlanner.java`、`PowerSupportSolver.java`、`LiquidSupportSolver.java`、`OptimalPlanningResult.java`、`ItemRouteSolver.java`、`MiningLayoutSolver.java`、`FlowSolver.java`。
- `src/autodrillnext/simulation/ItemNetworkSimulator.java`、`ItemSimulationEvaluator.java`。
- `src/autodrillnext/compile/FinalValidator.java`。
- `src/autodrillnext/LocalMiningPlanner.java`、`AutoDrillNEXT.java`。
- `src/autodrillnext/ui/SearchVerdictPresenter.java`：把稳定 verdict 映射为 bundle key、颜色语义和可读上下界。
- `src/autodrillnext/ui/PlanningOutlineOverlay.java`、`PlannerChoicePanel.java`、`PlannerDiagnostics.java`。
- `assets/bundles/bundle.properties`、`assets/bundles/bundle_zh_CN.properties`。
- 对应现有 model、solver、compile、UI 和集成测试。
- `README.md`：说明当前最佳、矿区最优证明和显式确认提交。

### 删除文件

- `src/autodrillnext/solver/OptimalMiningPlanner.java`。
- `src/test/java/autodrillnext/solver/OptimalMiningPlannerTest.java`。
- `src/autodrillnext/solver/TransportPathEnumerator.java` 与 `src/test/java/autodrillnext/solver/TransportPathEnumeratorTest.java`。

---

### Task 1: 统一目标顺序和证书状态模型

**Files:**
- Create: `src/autodrillnext/model/SearchVerdict.java`
- Create: `src/autodrillnext/model/SearchStopReason.java`
- Create: `src/autodrillnext/model/PatchSearchScope.java`
- Create: `src/autodrillnext/model/PlanObjectiveOrder.java`
- Modify: `src/autodrillnext/model/ObjectiveValue.java`
- Modify: `src/autodrillnext/model/OptimalityCertificate.java`
- Modify: `src/autodrillnext/solver/BudgetPlanner.java`
- Modify: `src/autodrillnext/LocalMiningPlanner.java`
- Test: `src/test/java/autodrillnext/model/ObjectiveValueTest.java`
- Test: `src/test/java/autodrillnext/model/OptimalityCertificateTest.java`
- Test: `src/test/java/autodrillnext/solver/BudgetPlannerTest.java`

**Interfaces:**
- Produces `SearchVerdict { NONE, SEARCH_INCOMPLETE, CURRENT_BEST, PATCH_OPTIMAL, PROVEN_INFEASIBLE }`.
- Produces `SearchStopReason { NONE, DEADLINE, STATE_LIMIT, MODEL_INCOMPLETE }`.
- Produces `PatchSearchScope.capture(WorldSnapshot, OrePatch, CapabilitySnapshot, Inventory, ExistingNetwork, ExitAnchor, PlannerRequest, String, String, SimulationFidelity, boolean)`; collections are copied, selected IDs are normalized to `""`, `terrain()` returns `TerrainSnapshot.of(world())`, and `sameInputs(PatchSearchScope)` compares value content rather than snapshot object identity.
- Produces `PlanObjectiveOrder.forProfile(PlannerRequest.Profile)`, `compare(ObjectiveValue, ObjectiveValue)`, `canBeat(ObjectiveValue, ObjectiveValue)` and `bestFirst()`; positive `compare` means the left value is better.
- Changes `OptimalityCertificate` to `(SearchVerdict verdict, PatchSearchScope scope, int exploredStates, int prunedStates, int pendingStates, ObjectiveValue lowerBound, ObjectiveValue upperBound, SearchStopReason stopReason, List<PlannerDiagnostic> diagnostics)` plus computed `optimal()`.
- Replaces ranked `BudgetPlanner.select(List<ServiceBundle>, BudgetSnapshot, Profile)` with `BudgetPlanner.select(ServiceBundle, BudgetSnapshot)`; `LocalMiningPlanner` passes its already-selected bundle, so final ranking no longer lives in the budget layer.

- [ ] **Step 1: Write failing profile-order and certificate-invariant tests**

```java
@Test
void profilesUseOneExplicitObjectiveOrder() {
    ObjectiveValue throughput = value(8f, 3, 20, 6, 6, "throughput");
    ObjectiveValue cheap = value(4f, 3, 2, 8, 8, "cheap");
    ObjectiveValue simple = value(3f, 2, 5, 4, 1, "simple");

    assertTrue(PlanObjectiveOrder.forProfile(PlannerRequest.Profile.THROUGHPUT)
        .compare(throughput, cheap) > 0);
    assertTrue(PlanObjectiveOrder.forProfile(PlannerRequest.Profile.LOW_COST)
        .compare(cheap, throughput) > 0);
    assertTrue(PlanObjectiveOrder.forProfile(PlannerRequest.Profile.LOW_COMPLEXITY)
        .compare(simple, cheap) > 0);
}

private ObjectiveValue value(float qout, int coverage, int copper, int space, int complexity, String id) {
    return new ObjectiveValue(qout, coverage,
        CostVector.of(Map.of(ItemId.of("copper"), copper)), space, complexity, id);
}
```

```java
@Test
void rejectsAProvenVerdictWithPendingStates() {
    assertThrows(IllegalArgumentException.class, () -> new OptimalityCertificate(
        SearchVerdict.PATCH_OPTIMAL, scope(), 4, 2, 1,
        value(2f, 0, 0, 0, 0, "same"), value(2f, 0, 0, 0, 0, "same"),
        SearchStopReason.NONE, List.of()));
}

@Test
void incompleteSearchWithoutIncumbentIsNotNoSolutionProof() {
    OptimalityCertificate certificate = new OptimalityCertificate(
        SearchVerdict.SEARCH_INCOMPLETE, scope(), 1, 0, 3,
        ObjectiveValue.zero("none"), value(5f, 0, 0, 0, 0, "upper"),
        SearchStopReason.STATE_LIMIT,
        List.of(PlannerDiagnostic.of(DiagnosticCode.SEARCH_LIMIT_REACHED)));
    assertFalse(certificate.optimal());
    assertEquals(SearchVerdict.SEARCH_INCOMPLETE, certificate.verdict());
}
```

In `OptimalityCertificateTest`, implement `scope()` with one-tile `WorldSnapshot`, matching one-cell `OrePatch`, empty immutable inventory/network/capabilities, a concrete `ExitAnchor`, `PlannerRequest.defaults(...)`, `SimulationFidelity.BOUNDED_MODEL`, and `modelComplete=true`; do not mock `PatchSearchScope`.

- [ ] **Step 2: Run the focused tests and confirm the contract is absent**

Run: `./gradlew test --tests autodrillnext.model.ObjectiveValueTest --tests autodrillnext.model.OptimalityCertificateTest --tests autodrillnext.solver.BudgetPlannerTest`

Expected: FAIL at test compilation because `PlanObjectiveOrder`, `SearchVerdict`, `SearchStopReason`, `PatchSearchScope`, and the new certificate constructor do not exist.

- [ ] **Step 3: Implement the single objective comparator**

```java
public final class PlanObjectiveOrder {
    private final PlannerRequest.Profile profile;

    private PlanObjectiveOrder(PlannerRequest.Profile profile) {
        this.profile = Objects.requireNonNull(profile, "profile");
    }

    public static PlanObjectiveOrder forProfile(PlannerRequest.Profile profile) {
        return new PlanObjectiveOrder(profile);
    }

    public int compare(ObjectiveValue left, ObjectiveValue right) {
        Objects.requireNonNull(left, "left");
        Objects.requireNonNull(right, "right");
        if (profile == PlannerRequest.Profile.LOW_COMPLEXITY) {
            int complexity = Integer.compare(right.complexity(), left.complexity());
            if (complexity != 0) return complexity;
        }
        if (profile == PlannerRequest.Profile.LOW_COST
            || profile == PlannerRequest.Profile.LOW_COMPLEXITY) {
            int cost = compareCost(left, right);
            if (cost != 0) return cost;
        }
        return compareThroughputFirst(left, right);
    }

    public boolean canBeat(ObjectiveValue optimistic, ObjectiveValue incumbent) {
        return compare(optimistic, incumbent) > 0;
    }

    public Comparator<ObjectiveValue> bestFirst() {
        return (left, right) -> -compare(left, right);
    }
}
```

Implement `compareCost` as lower material economic value, then lower space, then lower complexity; implement `compareThroughputFirst` as output bucket, coverage, lower material value, lower space, lower complexity, then lexicographically earlier canonical ID. Keep `ObjectiveValue.compareTo` only as a transitional delegate to `PlanObjectiveOrder.forProfile(THROUGHPUT)` so the pre-cutover planner still compiles; no new code may call it, and Task 7 removes both `Comparable<ObjectiveValue>` and `compareTo` after the old planner is deleted. Keep `outputRank` and add `zero(String canonicalId)`.

- [ ] **Step 4: Implement certificate and scope invariants**

Enforce these constructor rules directly:

```java
boolean hasIncumbent = ObjectiveValue.outputRank(lowerBound.qout()) > 0;
if (verdict == SearchVerdict.CURRENT_BEST && !hasIncumbent) throw invalid("current best requires incumbent");
if (verdict == SearchVerdict.SEARCH_INCOMPLETE && hasIncumbent) throw invalid("incomplete search must not carry incumbent");
if ((verdict == SearchVerdict.PATCH_OPTIMAL || verdict == SearchVerdict.PROVEN_INFEASIBLE)
    && pendingStates != 0) throw invalid("proven verdict requires empty queue");
if (verdict == SearchVerdict.PATCH_OPTIMAL
    && PlanObjectiveOrder.forProfile(scope.request().profile()).compare(upperBound, lowerBound) != 0)
    throw invalid("proven bounds must match");
if (verdict == SearchVerdict.PROVEN_INFEASIBLE && hasIncumbent)
    throw invalid("infeasible proof cannot carry incumbent");
```

`OptimalityCertificate.none()` uses `scope=null`, zero bounds, zero counts, `SearchStopReason.NONE`, and no diagnostics. Every certificate created through the new constructor with a non-`NONE` verdict requires a scope. Retain the existing six-argument constructor only as a deprecated transition for `OptimalMiningPlanner`: reject `optimal=true`, map `optimal=false` to `NONE`, and preserve its diagnostic counters without exposing a proof claim. Task 7 removes this constructor before any certificate reaches the review UI. `PatchSearchScope` copies the ore cells and domain tiles from the supplied snapshots and stores the immutable input objects needed to reproduce the proof.

For `CURRENT_BEST` and `SEARCH_INCOMPLETE`, require `DEADLINE`, `STATE_LIMIT`, or `MODEL_INCOMPLETE`; require `SEARCH_LIMIT_REACHED` for the first two and `UNSUPPORTED_CONTENT` for the last. Under the scope’s `PlanObjectiveOrder`, every trusted upper bound must be greater than or equal to the lower bound. `PATCH_OPTIMAL` and `PROVEN_INFEASIBLE` require `SearchStopReason.NONE` and must not contain `SEARCH_LIMIT_REACHED`; infeasible bounds are both zero. Deadline/state-limit incomplete results require a positive pending count, while exhausted model-incomplete results may have zero pending states because the unsupported semantics—not the queue—block proof.

- [ ] **Step 5: Remove ranking from budget selection, migrate its caller, and run focused tests**

Change budget selection to evaluate one already-ranked bundle, and change the existing `LocalMiningPlanner` call from `select(List.of(bundle), budget, profile)` to `select(bundle, budget)`:

```java
public BudgetSelection select(ServiceBundle candidate, BudgetSnapshot available) {
    if (candidate == null || candidate.qout() <= 0f || !candidate.isDependencyClosed()
        || !available.infiniteResources()
            && !candidate.cost().materials().componentWiseAtMost(available.inventory())) {
        return new BudgetSelection(null, List.of(PlannerDiagnostic.of(DiagnosticCode.BUDGET_DEFICIT)));
    }
    return new BudgetSelection(candidate, List.of());
}
```

Run: `./gradlew test --tests autodrillnext.model.ObjectiveValueTest --tests autodrillnext.model.OptimalityCertificateTest --tests autodrillnext.solver.BudgetPlannerTest`

Expected: PASS; `BudgetPlanner` has no profile-aware ranking API, and every new ranking path uses `PlanObjectiveOrder`. The only remaining `ObjectiveValue.compareTo` calls belong to the explicitly transitional old planner and are removed in Task 7.

- [ ] **Step 6: Commit the result contract**

```bash
git add src/autodrillnext/model/SearchVerdict.java src/autodrillnext/model/SearchStopReason.java src/autodrillnext/model/PatchSearchScope.java src/autodrillnext/model/PlanObjectiveOrder.java src/autodrillnext/model/ObjectiveValue.java src/autodrillnext/model/OptimalityCertificate.java src/autodrillnext/solver/BudgetPlanner.java src/autodrillnext/LocalMiningPlanner.java src/test/java/autodrillnext/model/ObjectiveValueTest.java src/test/java/autodrillnext/model/OptimalityCertificateTest.java src/test/java/autodrillnext/solver/BudgetPlannerTest.java
git commit -m "feat: define patch search verdicts"
```

### Task 2: 建立唯一物理运输图构造器

**Files:**
- Create: `src/autodrillnext/compile/PhysicalTransportGraphBuilder.java`
- Modify: `src/autodrillnext/solver/ItemRouteSolver.java`
- Modify: `src/autodrillnext/compile/FinalValidator.java`
- Test: `src/test/java/autodrillnext/compile/PhysicalTransportValidationTest.java`
- Test: `src/test/java/autodrillnext/solver/ItemRouteSolverTest.java`

**Interfaces:**
- Produces `Optional<PlanGraph> PhysicalTransportGraphBuilder.build(MiningLayout, ExitAnchor, Collection<TransportPlacement>)`.
- Produces `Optional<PlanGraph> PhysicalTransportGraphBuilder.fromCompileRecords(List<BuildPlanCompiler.CompileRecord>, MiningLayout, ExitAnchor, CapabilitySnapshot)`.
- `ItemRouteSolver` and `FinalValidator.validatePhysical` consume these methods; neither keeps a second source-feed, output-port, bridge-pair or sink-terminal implementation.

- [ ] **Step 1: Write failing shared-builder tests**

```java
@Test
void sharedBuilderFeedsOneDrillThroughEveryAdmittedPerimeterReceiver() {
    PhysicalTransportGraphBuilder builder = new PhysicalTransportGraphBuilder();
    PlanGraph graph = builder.build(layoutWithOneTwoByTwoDrill(), rightExit(), List.of(
        placement(2, 0, 0, 3, 0),
        placement(2, 1, 0, 3, 1),
        placement(3, 0, 0, 4, 0),
        placement(3, 1, 0, 4, 1)
    )).orElseThrow();

    long feeds = graph.edges().stream().filter(edge -> edge.kind() == EdgeKind.SOURCE_EDGE).count();
    assertEquals(2, feeds);
}

@Test
void compileRoundTripRejectsAnUnpairedDirectionalBridge() {
    PhysicalTransportGraphBuilder builder = new PhysicalTransportGraphBuilder();
    assertTrue(builder.fromCompileRecords(unpairedBridgeRecords(), layout(), exit(), capabilities()).isEmpty());
}
```

Keep the existing `PhysicalTransportValidationTest` fixtures for bridge direction, front-face acceptance, automatic-link interference, cycle rejection and terminal orientation. Move only fixture helpers that are shared by both tests.

- [ ] **Step 2: Run the focused tests and confirm the builder is missing**

Run: `./gradlew test --tests autodrillnext.compile.PhysicalTransportValidationTest --tests autodrillnext.solver.ItemRouteSolverTest`

Expected: FAIL at test compilation because `PhysicalTransportGraphBuilder` does not exist.

- [ ] **Step 3: Move physical graph construction without changing behavior**

Implement `build` by moving the existing `ItemRouteSolver.physicalGraph` logic verbatim first, then replace nullable failure with `Optional.empty()`:

```java
public Optional<PlanGraph> build(MiningLayout layout, ExitAnchor exit,
                                 Collection<TransportPlacement> values) {
    LinkedHashMap<TileKey, TransportPlacement> placements = canonicalPlacements(values);
    if (!TransportGeometry.networkConsistent(placements, exit)) return Optional.empty();
    List<PlanNode> nodes = nodes(layout, exit, placements);
    List<PlanEdge> edges = transportEdges(layout, exit, placements);
    if (layout.candidates().stream().anyMatch(drill -> !hasSourceEdge(drill, edges))) return Optional.empty();
    return Optional.of(PlanGraph.of(nodes, edges, "sink")
        .withPlacements(List.copyOf(placements.values()), exit));
}
```

Canonical placement order is tile, content ID, rotation, output, bridge target. Reject duplicate tiles unless the values are equal. Ground and bridge edges derive their capacities through `TransportGeometry.admissionCapacity`; source feeds derive through `TransportGeometry.acceptsAdjacent`; no copied port switch is allowed.

- [ ] **Step 4: Rebuild compiled placements through the same implementation**

`fromCompileRecords` filters `Operation.ADD` transport records by looking up `ItemTransportSpec` in `CapabilitySnapshot`, converts `TileOffset` bridge configs to absolute targets, computes ground output from rotation, and calls `build`. `FinalValidator.validatePhysical` uses the returned graph plus `FlowSolver.assign` to check connectivity and capacity; retain terrain/build-record diagnostics around this shared reconstruction.

- [ ] **Step 5: Run physical routing regression tests**

Run: `./gradlew test --tests autodrillnext.compile.PhysicalTransportValidationTest --tests autodrillnext.compile.FinalValidatorTest --tests autodrillnext.solver.ItemRouteSolverTest`

Expected: PASS, including existing direct-bridge, side-admission, cycle and redundant-feeder cases.

- [ ] **Step 6: Commit the shared physical graph**

```bash
git add src/autodrillnext/compile/PhysicalTransportGraphBuilder.java src/autodrillnext/compile/FinalValidator.java src/autodrillnext/solver/ItemRouteSolver.java src/test/java/autodrillnext/compile/PhysicalTransportValidationTest.java src/test/java/autodrillnext/solver/ItemRouteSolverTest.java
git commit -m "refactor: share physical transport graph"
```

### Task 3: 穷举有限支持分配

**Files:**
- Modify: `src/autodrillnext/solver/SupportPlanner.java`
- Modify: `src/autodrillnext/solver/PowerSupportSolver.java`
- Modify: `src/autodrillnext/solver/LiquidSupportSolver.java`
- Test: `src/test/java/autodrillnext/solver/SupportPlannerTest.java`
- Test: `src/test/java/autodrillnext/solver/PowerSupportSolverTest.java`
- Test: `src/test/java/autodrillnext/solver/LiquidSupportSolverTest.java`

**Interfaces:**
- Produces `List<SupportPlan> SupportPlanner.solveAll(MiningLayout, PlanGraph, TerrainSnapshot, CapabilitySnapshot, ExistingNetwork, String)` sorted by selected-variant count and then canonical `variantId`.
- Retains `SupportPlanner.solve(...)` only as the warm-start convenience selecting the greatest `finalQout`, then earliest `variantId`; proof code must call `solveAll`.
- `LiquidSupportSolver.solve` becomes exhaustive over demand-to-liquid assignments.
- Runtime power assignment in `SupportPlanner` becomes exhaustive over reachable power graphs after mandatory connector-slot reservations.

- [ ] **Step 1: Write failing greedy-trap tests**

```java
@Test
void liquidAssignmentBacktracksWhenFlexibleDemandWouldConsumeTheOnlySpecificSupply() {
    LiquidId water = LiquidId.of("water");
    LiquidId cryo = LiquidId.of("cryofluid");
    ExistingNetwork network = new ExistingNetwork(Set.of(), Set.of(), 0f,
        Map.of(water, 1.1f, cryo, 1.1f), Set.of());
    List<LiquidDemand> demands = List.of(
        new LiquidDemand(List.of(water, cryo), 1f, true),
        new LiquidDemand(List.of(water), 1f, true));

    assertTrue(new LiquidSupportSolver().solve(demands, network,
        new CapabilitySnapshot(Map.of()), 1.10f).feasible());
}

@Test
void solveAllReturnsNoneAndEveryFeasibleBoostCombination() {
    List<SupportPlan> plans = planner.solveAll(layoutWithTwoOptionalBoosts(), graph(), terrain(),
        capabilities(), network(), null);
    assertEquals(List.of("none", "a=water-boost", "b=water-boost", "a=water-boost;b=water-boost"),
        plans.stream().map(SupportPlan::variantId).toList());
}
```

Build `layoutWithTwoOptionalBoosts()` from two non-overlapping `DrillCandidate` values whose `supportVariants` each contain one `SupportVariant` with `productionMultiplier=1.5f`; give the network enough water and power for all four combinations.

- [ ] **Step 2: Run support tests and confirm incomplete greedy behavior**

Run: `./gradlew test --tests autodrillnext.solver.SupportPlannerTest --tests autodrillnext.solver.PowerSupportSolverTest --tests autodrillnext.solver.LiquidSupportSolverTest`

Expected: FAIL because `solveAll` is absent and the flexible liquid demand consumes water before the specific demand.

- [ ] **Step 3: Implement canonical support-variant enumeration**

For sorted drills, branch once on `none` and once per sorted allowed variant. A null `selectedLiquidId` permits every modeled variant, an empty value permits only `none`, and a non-empty value retains only variants supporting that liquid after the existing `restrictToLiquid` transformation; reject the all-`none` leaf when the user explicitly selected a non-empty liquid. At a leaf, aggregate mandatory and optional demands, run exact power/liquid feasibility, calculate `transportedOutput`, and emit one `SupportPlan`. Deduplicate by `variantId`; sort by selected-variant count and then `variantId`, so `none`, single boosts and combined boosts are stable.

```java
private void enumerate(int index, List<DrillCandidate> drills,
                       Map<String, SupportVariant> selected, List<SupportPlan> output,
                       SupportFacts facts) {
    checkCancelled();
    if (index == drills.size()) {
        evaluateSelection(selected, output, facts);
        return;
    }
    DrillCandidate drill = drills.get(index);
    enumerate(index + 1, drills, selected, output, facts);
    for (SupportVariant variant : allowedVariants(drill, facts.selectedLiquidId())) {
        selected.put(drill.id(), variant);
        enumerate(index + 1, drills, selected, output, facts);
        selected.remove(drill.id());
    }
}
```

- [ ] **Step 4: Replace greedy resource assignment with deterministic backtracking**

Liquid state is `(demandIndex, remainingByLiquid)`; sort demands by fewest choices, descending amount, canonical liquid IDs. Power state is `(needIndex, remainingByGraph)` after reserving every captured mandatory connector link exactly once. Memoize canonical remaining-capacity buckets using `ObjectiveValue.outputRank`. A branch succeeds only when every demand is assigned; no first-fit result may be treated as proof.

- [ ] **Step 5: Run support regressions**

Run: `./gradlew test --tests autodrillnext.solver.SupportPlannerTest --tests autodrillnext.solver.PowerSupportSolverTest --tests autodrillnext.solver.LiquidSupportSolverTest`

Expected: PASS; repeated `solveAll` calls return byte-for-byte equal variant ID order.

- [ ] **Step 6: Commit exhaustive support selection**

```bash
git add src/autodrillnext/solver/SupportPlanner.java src/autodrillnext/solver/PowerSupportSolver.java src/autodrillnext/solver/LiquidSupportSolver.java src/test/java/autodrillnext/solver/SupportPlannerTest.java src/test/java/autodrillnext/solver/PowerSupportSolverTest.java src/test/java/autodrillnext/solver/LiquidSupportSolverTest.java
git commit -m "feat: enumerate support assignments"
```

### Task 4: 构建完整运输配置域和安全放宽图

**Files:**
- Create: `src/autodrillnext/solver/TransportPlacementDomain.java`
- Create: `src/test/java/autodrillnext/solver/TransportPlacementDomainTest.java`
- Test: `src/test/java/autodrillnext/solver/FlowSolverTest.java`

**Interfaces:**

- Produces `TransportPlacementDomain.capture(PatchSearchScope)`.
- Produces `int size()`, `PlacementConfiguration configuration(int)`, `BitSet conflicts(int)`, `List<Integer> terminatingAt(ExitAnchor)`, `List<Integer> feeding(TransportPlacement receiver)`, `Optional<PlanGraph> exactGraph(MiningLayout, BitSet enabled)`, and `PlanGraph relaxedGraph(Collection<DrillCandidate> potentialSources, BitSet disabled)`.
- Produces nested immutable `PlacementConfiguration(String canonicalId, List<TransportPlacement> placements, Set<TileKey> footprint)`; ground configurations contain one placement and bridge configurations contain the sender/receiver pair.
- Exact graphs use `PhysicalTransportGraphBuilder`; relaxed graphs use unique virtual nodes, admit every potential drill source independently even when those candidates conflict, and may retain mutually exclusive transport alternatives, ensuring the relaxed max flow never understates a concrete completion. `terminatingAt(exit)` seeds reverse expansion from the sink; `feeding(receiver)` returns canonical configurations whose output is accepted by that exact receiver port.

- [ ] **Step 1: Write failing finite-domain tests**

```java
@Test
void capturesGroundOutputsAndEveryLegalBridgePairInCanonicalOrder() {
    TransportPlacementDomain domain = TransportPlacementDomain.capture(scopeWithFiveByThreeOpenTerrain());
    List<String> ids = IntStream.range(0, domain.size())
        .mapToObj(index -> domain.configuration(index).canonicalId()).toList();

    assertTrue(ids.contains("belt@1,1/r0->2,1"));
    assertTrue(ids.contains("bridge@0,1/r0=>3,1/r0->4,1"));
    assertEquals(ids.stream().sorted().toList(), ids);
}

@Test
void relaxedFlowNeverFallsBelowAnyConcreteCompletion() {
    TransportPlacementDomain domain = TransportPlacementDomain.capture(tinyScope());
    double relaxed = new FlowSolver().assign(
        domain.relaxedGraph(layout().candidates(), new BitSet())).qOut();
    double exhaustiveBest = concreteSubsets(domain, layout()).stream()
        .mapToDouble(graph -> new FlowSolver().assign(graph).qOut()).max().orElse(0d);
    assertTrue(relaxed + 0.0001d >= exhaustiveBest);
}
```

Implement `concreteSubsets` in the test by iterating masks from `0` to `(1 << domain.size()) - 1`, skipping masks that select conflicting configurations, and collecting only `exactGraph(...).isPresent()` results. Keep the fixture domain at no more than 12 configurations.

- [ ] **Step 2: Run the domain tests and verify the class is absent**

Run: `./gradlew test --tests autodrillnext.solver.TransportPlacementDomainTest --tests autodrillnext.solver.FlowSolverTest`

Expected: FAIL at test compilation because `TransportPlacementDomain` does not exist.

- [ ] **Step 3: Enumerate every supported placement configuration**
Iterate domain tiles, available `ItemTransportSpec` values, legal rotations and output choices in canonical order. Ground configurations use `TransportGeometry.step(tile, rotation, 1)`. For a bridge sender, enumerate each legal same-axis receiver within `spec.range()`, each legal receiver output rotation, and require both endpoints to pass `TerrainSnapshot.canPlace`, avoid existing-network occupancy, pass `TransportGeometry.pairConnects`, and avoid `conflictsWithExistingDirectionalBridge`. Drill-footprint conflicts are deferred until a concrete layout state exists.

Do not use route distance, patch margin, path length, `maxIterations`, or retained-candidate limits. The only domain boundary is `scope.world().tiles().keySet()`.

- [ ] **Step 4: Build mutual exclusion groups and exact graphs**

Two transport configurations conflict when they assign unequal placements to the same tile or claim incompatible bridge endpoints. Precompute those configuration-to-configuration conflict `BitSet` values. Drill-footprint overlap is layout-dependent: reject it when a transport state enables a configuration and recheck it in `exactGraph`. `exactGraph` merges enabled configurations by tile, rejects any remaining conflict, then delegates to `PhysicalTransportGraphBuilder.build`.

- [ ] **Step 5: Build the optimistic relaxed network**

Create a unique virtual node per `(configuration index, placement index)` so mutually exclusive choices coexist. Add an arc whenever the corresponding physical placements could connect under `TransportGeometry`; add source arcs from every legal drill perimeter and sink arcs from every valid terminal. Transport arcs use `capacityPerSecond / scope.request().transportHeadroom()` and zero cost. Disabled configurations contribute no nodes or arcs.

The relaxation may connect choices that cannot coexist; this is intentional. Test every disabled subset of the tiny fixture against every compatible concrete enabled subset:

```java
for (int disabledMask = 0; disabledMask < (1 << domain.size()); disabledMask++) {
    BitSet disabled = bits(disabledMask, domain.size());
    double upper = new FlowSolver().assign(
        domain.relaxedGraph(layout().candidates(), disabled)).qOut();
    for (int enabledMask = 0; enabledMask < (1 << domain.size()); enabledMask++) {
        if ((enabledMask & disabledMask) != 0) continue;
        Optional<PlanGraph> concrete = domain.exactGraph(layout(), bits(enabledMask, domain.size()));
        if (concrete.isPresent()) {
            assertTrue(upper + 0.0001d >= new FlowSolver().assign(concrete.get()).qOut());
        }
    }
}
```

- [ ] **Step 6: Run domain and flow tests**

Run: `./gradlew test --tests autodrillnext.solver.TransportPlacementDomainTest --tests autodrillnext.solver.FlowSolverTest --tests autodrillnext.compile.PhysicalTransportValidationTest`

Expected: PASS; the non-shortest cheap path and bridge pair remain in the domain, and every sampled relaxed bound is at least the concrete optimum.

- [ ] **Step 7: Commit the finite transport domain**

```bash
git add src/autodrillnext/solver/TransportPlacementDomain.java src/test/java/autodrillnext/solver/TransportPlacementDomainTest.java src/test/java/autodrillnext/solver/FlowSolverTest.java
git commit -m "feat: model complete transport domain"
```

### Task 5: 集中验证完整候选方案

**Files:**
- Create: `src/autodrillnext/model/SearchBudget.java`
- Create: `src/autodrillnext/solver/EvaluatedMiningPlan.java`
- Create: `src/autodrillnext/solver/PlanCandidateEvaluator.java`
- Modify: `src/autodrillnext/solver/FlowSolver.java`
- Modify: `src/autodrillnext/solver/SupportPlanner.java`
- Modify: `src/autodrillnext/solver/OptimalPlanningResult.java`
- Modify: `src/autodrillnext/simulation/ItemNetworkSimulator.java`
- Modify: `src/autodrillnext/simulation/ItemSimulationEvaluator.java`
- Create: `src/test/java/autodrillnext/solver/PlanCandidateEvaluatorTest.java`

**Interfaces:**

- Produces `SearchBudget.until(long deadlineNanos, LongSupplier nanoTime)`, `unlimited()`, `expired()` and `checkpoint()`; `checkpoint()` also honors thread interruption and throws its nested `Expired` exception when the local slice expires.
- Adds cooperative overloads `FlowSolver.assign(PlanGraph, SearchBudget)`, `SupportPlanner.solveAll(..., SearchBudget)` and `ItemSimulationEvaluator.evaluate(PlanGraph, CapabilitySnapshot, SearchBudget)`; their existing overloads delegate with `SearchBudget.unlimited()`.
- Produces `EvaluatedMiningPlan(MiningLayout layout, PlanGraph graph, SupportPlan support, ServiceBundle bundle, SimulationResult simulation, ObjectiveValue objective, boolean proofEligible)`.
- Produces `PlanCandidateEvaluator.Evaluation PlanCandidateEvaluator.evaluate(MiningLayout, PlanGraph, PatchSearchScope, SearchBudget)`, where `Evaluation` contains immutable `plans` and `diagnostics`, and plans are sorted by `PlanObjectiveOrder.bestFirst()`.
- Extends `OptimalPlanningResult` with selected `ServiceBundle bundle`, `ObjectiveValue objective`, and immutable `List<ServiceBundle> frontierBundles` while retaining layout, graph, support, simulation, certificate and diagnostics; the list is the incremental non-dominated frontier, not every evaluated bundle.

- [ ] **Step 1: Write failing evaluator tests**

```java
@Test
void onlyFullyValidatedAffordablePlansBecomeIncumbents() {
    PlanCandidateEvaluator.Evaluation evaluation = evaluator.evaluate(
        layout(), graph(), finiteBudgetScope(), SearchBudget.unlimited());
    List<EvaluatedMiningPlan> plans = evaluation.plans();
    assertEquals(1, plans.size());
    assertTrue(plans.get(0).graph().flow().capacitySafe());
    assertTrue(plans.get(0).bundle().cost().materials().componentWiseAtMost(
        finiteBudgetScope().inventory()));
}

@Test
void unsupportedSimulationAllowsIncumbentButBlocksProof() {
    EvaluatedMiningPlan plan = evaluator.evaluate(layout(), unsupportedTransportGraph(), scope(),
        SearchBudget.unlimited()).plans().get(0);
    assertFalse(plan.proofEligible());
    assertEquals(SimulationFidelity.UNSUPPORTED, plan.simulation().fidelity());
}
```
Add four named cases to the same test class: `zeroFlowIsRejectedWithNoRoute`, `unsatisfiedSupportPreservesNoPowerSource`, `physicalFailurePreservesTerrainBlocked`, and `overBudgetPreservesBudgetDeficit`. Each asserts an empty `Evaluation.plans()` and the exact corresponding `DiagnosticCode` in `Evaluation.diagnostics()`.

- [ ] **Step 2: Run the evaluator test and verify missing types**

Run: `./gradlew test --tests autodrillnext.solver.PlanCandidateEvaluatorTest`

Expected: FAIL at test compilation because `PlanCandidateEvaluator` and `EvaluatedMiningPlan` do not exist.

- [ ] **Step 3: Implement one validation pipeline**

For each graph:

```java
PlanGraph flowed = graph.withTransportHeadroom(scope.request().transportHeadroom());
budget.checkpoint();
flowed = flowed.withFlow(flowSolver.assign(flowed, budget));
if (flowed.qOut() <= 0f || !flowed.flow().capacitySafe()) return rejected(DiagnosticCode.NO_ROUTE);
for (SupportPlan support : supportPlanner.solveAll(layout, flowed, scope.terrain(),
        scope.capabilities(), scope.existingNetwork(), scope.selectedLiquidId(), budget)) {
    budget.checkpoint();
    ValidationResult physical = validator.validate(flowed, liveSnapshot(scope), layout);
    if (!physical.valid()) {
        diagnostics.addAll(physical.diagnostics());
        continue;
    }
    ServiceBundle bundle = bundle(layout, flowed, support);
    if (!budgetPlanner.select(bundle, budgetSnapshot(scope)).hasSelection()) {
        diagnostics.add(PlannerDiagnostic.of(DiagnosticCode.BUDGET_DEFICIT));
        continue;
    }
    SimulationResult simulation = simulationEvaluator.evaluate(flowed, scope.capabilities(), budget);
    plans.add(evaluated(layout, flowed, support, bundle, simulation, scope));
}
return new Evaluation(plans, diagnostics);
```

Add `budget.checkpoint()` to every max-flow augmentation/BFS round, support-enumeration recursion and simulation tick. Keep existing overloads source-compatible by delegating to `SearchBudget.unlimited()`:

```java
public FlowAssignment assign(PlanGraph graph) {
    return assign(graph, SearchBudget.unlimited());
}

public SimulationResult evaluate(PlanGraph graph, CapabilitySnapshot capabilities) {
    return evaluate(graph, capabilities, SearchBudget.unlimited());
}
```

Move the exact existing `bundle(...)`, covered-ore deduplication, target-fraction and simulated-throughput calculation from `LocalMiningPlanner` into this class. A plan is `proofEligible` only when `scope.modelComplete()` is true and its simulation fidelity is not `UNSUPPORTED`; still keep unsupported plans as incumbents.

- [ ] **Step 4: Sort support alternatives with the shared objective order**

Create one `ObjectiveValue` per support alternative and sort by `PlanObjectiveOrder.forProfile(scope.request().profile()).bestFirst()`. Do not compare `ServiceBundle`, `PlanCost` or raw throughput outside `PlanObjectiveOrder` when selecting the returned first element.

- [ ] **Step 5: Run evaluator and dependent solver tests**

Run: `./gradlew test --tests autodrillnext.solver.PlanCandidateEvaluatorTest --tests autodrillnext.solver.SupportPlannerTest --tests autodrillnext.solver.BudgetPlannerTest --tests autodrillnext.simulation.ItemSimulationEvaluatorTest --tests autodrillnext.compile.FinalValidatorTest`

Expected: PASS.

- [ ] **Step 6: Commit the complete-plan evaluator**

```bash
git add src/autodrillnext/model/SearchBudget.java src/autodrillnext/solver/EvaluatedMiningPlan.java src/autodrillnext/solver/PlanCandidateEvaluator.java src/autodrillnext/solver/FlowSolver.java src/autodrillnext/solver/SupportPlanner.java src/autodrillnext/solver/OptimalPlanningResult.java src/autodrillnext/simulation/ItemNetworkSimulator.java src/autodrillnext/simulation/ItemSimulationEvaluator.java src/test/java/autodrillnext/solver/PlanCandidateEvaluatorTest.java
git commit -m "feat: validate patch search incumbents"
```

### Task 6: 实现任意时刻分支定界和独立 oracle

**Files:**
- Create: `src/autodrillnext/solver/PatchOptimalSearch.java`
- Create: `src/test/java/autodrillnext/solver/PatchSearchOracle.java`
- Create: `src/test/java/autodrillnext/solver/PatchOptimalSearchTest.java`
- Modify: `src/autodrillnext/model/PlanningProgress.java`
- Modify: `src/autodrillnext/solver/MiningLayoutSolver.java`
- Modify: `src/autodrillnext/solver/ItemRouteSolver.java`

**Interfaces:**
- Produces `OptimalPlanningResult PatchOptimalSearch.search(PatchSearchScope, List<DrillCandidate>, Consumer<PlanningProgress>)`.
- Constructor injection accepts `MiningLayoutSolver`, `ItemRouteSolver`, `PlanCandidateEvaluator`, `FlowSolver`, `ParetoPlanner` and a monotonic `LongSupplier nanoTime`; `search` captures its own `TransportPlacementDomain` from the supplied scope.
- Search uses internal immutable `LayoutState` and `TransportState`; each exposes `ObjectiveValue optimistic()`, `String canonicalId()` and enough data to generate exactly its two include/exclude successors.
- Adds `MiningLayoutSolver.seeds(List<DrillCandidate>, PlannerRequest, SearchBudget)` and `improve(..., SearchBudget)`, plus `ItemRouteSolver.route(..., Consumer<RoutingProgress>, SearchBudget)`; existing convenience overloads use `SearchBudget.unlimited()`.
- `PlanningProgress.Stage` adds `HEURISTIC_WARM_START` and `CERTIFYING`; Task 8 extends the public progress counters after migrating every constructor and presenter together.

- [ ] **Step 1: Write the independent brute-force oracle**

The oracle must not call `PatchOptimalSearch`, its bound methods, its dominance keys, or `TransportPlacementDomain.relaxedGraph`. It may reuse `PhysicalTransportGraphBuilder` and `PlanCandidateEvaluator`, because those define feasibility rather than search order.

```java
final class PatchSearchOracle {
    Optional<EvaluatedMiningPlan> solve(PatchSearchScope scope,
                                        List<DrillCandidate> drills,
                                        TransportPlacementDomain domain,
                                        PlanCandidateEvaluator evaluator) {
        PlanObjectiveOrder order = PlanObjectiveOrder.forProfile(scope.request().profile());
        EvaluatedMiningPlan best = null;
        for (long drillMask = 1; drillMask < (1L << drills.size()); drillMask++) {
            MiningLayout layout = legalLayout(drills, drillMask);
            if (layout == null) continue;
            for (long placementMask = 0; placementMask < (1L << domain.size()); placementMask++) {
                BitSet enabled = bits(placementMask, domain.size());
                Optional<PlanGraph> graph = domain.exactGraph(layout, enabled);
                if (graph.isEmpty()) continue;
                for (EvaluatedMiningPlan candidate : evaluator.evaluate(
                        layout, graph.get(), scope, SearchBudget.unlimited()).plans()) {
                    if (best == null || order.compare(candidate.objective(), best.objective()) > 0) best = candidate;
                }
            }
        }
        return Optional.ofNullable(best);
    }
}
```

Limit oracle fixtures to at most 6 drill candidates and 16 transport configurations so `long` masks are exhaustive and deterministic.

- [ ] **Step 2: Write failing search-verdict tests**

```java
@Test
void exhaustedTinyDomainMatchesIndependentOracle() {
    OptimalPlanningResult actual = search.search(scope(), candidates(), progress::add);
    EvaluatedMiningPlan expected = oracle.solve(scope(), candidates(), domain(), evaluator()).orElseThrow();

    assertEquals(SearchVerdict.PATCH_OPTIMAL, actual.certificate().verdict());
    assertEquals(expected.objective(), actual.objective());
    assertEquals(expected.graph().placements(), actual.graph().placements());
    assertEquals(0, actual.certificate().pendingStates());
}

@Test
void stateLimitWithoutIncumbentIsSearchIncomplete() {
    OptimalPlanningResult result = search.search(scopeWithMaxIterations(1), candidates(), ignored -> {});
    assertEquals(SearchVerdict.SEARCH_INCOMPLETE, result.certificate().verdict());
    assertFalse(result.hasPlan());
    assertTrue(result.certificate().pendingStates() > 0);
}

@Test
void stateLimitWithWarmIncumbentIsCurrentBest() {
    OptimalPlanningResult result = searchWithWarmStart().search(scopeWithMaxIterations(1), candidates(), ignored -> {});
    assertEquals(SearchVerdict.CURRENT_BEST, result.certificate().verdict());
    assertTrue(result.hasPlan());
    assertTrue(PlanObjectiveOrder.forProfile(scope().request().profile())
        .compare(result.certificate().upperBound(), result.certificate().lowerBound()) >= 0);
}
```

Add these named oracle comparisons: `nonShortestCheapNetworkMatchesOracle`, `sharedTrunkCapacityMatchesOracle`, `bridgeOrientationMatchesOracle`, `allProfilesMatchOracle`, `exhaustedEmptyDomainIsProvenInfeasible`, `unsupportedModelCannotClaimProof`, `repeatedRunsKeepPlanBoundsAndCounts`, and `interruptionStopsBeforeAnotherExpansion`. `allProfilesMatchOracle` loops over `PlannerRequest.Profile.values()` and compares both `ObjectiveValue` and canonical placements with `PatchSearchOracle`.

- [ ] **Step 3: Run the search tests and verify `PatchOptimalSearch` is absent**

Run: `./gradlew test --tests autodrillnext.solver.PatchOptimalSearchTest`

Expected: FAIL at test compilation because `PatchOptimalSearch` and `PatchSearchOracle` do not exist.

- [ ] **Step 4: Implement root creation and the reserved warm-start slice**

At method entry compute `deadline = nanoTime.getAsLong() + SECONDS.toNanos(2)` and `warmDeadline = start + SECONDS.toNanos(2) / 4`. Capture the transport domain and enqueue the root `LayoutState` before running any heuristic. Create separate `SearchBudget` values for the warm slice and global deadline. Pass the warm budget through every `MiningLayoutSolver` refinement, `ItemRouteSolver` route-family/A* expansion, `FlowSolver` augmentation, support assignment and simulation tick; catching warm-budget expiry transfers control to proof search, while thread interruption still propagates as `CancellationException`.

Every heuristic graph must pass `PlanCandidateEvaluator` with the warm budget before it becomes incumbent. Stop the warm-start loop immediately after the first verified incumbent and enter proof search; if no incumbent appears, stop it at `warmDeadline`. Heuristic retained-candidate caps remain local to warm start and never delete a proof-queue state.

- [ ] **Step 5: Implement deterministic best-first state expansion**

```java
PriorityQueue<SearchState> pending = new PriorityQueue<>(
    Comparator.comparing(SearchState::optimistic, order.bestFirst())
        .thenComparing(SearchState::canonicalId));
while (!pending.isEmpty()) {
    checkCancelled();
    if (nanoTime.getAsLong() >= deadline) return limited(DEADLINE, pending, incumbent);
    if (exploredStates >= scope.request().maxIterations()) return limited(STATE_LIMIT, pending, incumbent);
    SearchState state = pending.poll();
    if (dominated(state) || incumbent != null && !order.canBeat(state.optimistic(), incumbent.objective())) {
        prunedStates++;
        continue;
    }
    exploredStates++;
    expand(state, pending);
}
return exhausted(incumbent);
```
`LayoutState` branches skip/include in `CandidateGenerator` order. Include rejects footprint/conflict collisions and updates absolute covered ore. At the end of its candidate cursor, a non-empty layout enqueues one `TransportState` by branching over `domain.terminatingAt(scope.exit())`. `TransportState` then chooses the earliest not-yet-decided configuration returned by `domain.feeding(receiver)` for its sink-reaching frontier and branches exclude/include. Include also disables every conflict bit and discovers new predecessor-frontier choices. As soon as every drill has an admitted feed into the sink-reaching network, evaluate that exact graph; for each feasible candidate, replace the retained bundle list with `paretoPlanner.frontier(frontier plus candidate.bundle())` rather than storing every evaluated bundle. Do not append disconnected or cycle-forming placements because positive material/space/complexity order makes them strictly dominated. Continue exploring competing queue states until their safe bounds cannot beat the incumbent or the queue is exhausted.


- [ ] **Step 6: Implement safe bounds and dominance**

For layout states, optimistic production is the sum of selected plus every remaining candidate regardless of mutual conflict; optimistic coverage is the union of selected and remaining ore cells; materials/space/complexity use current unavoidable cost only. Pass selected plus remaining candidates as independent potential sources to `relaxedGraph`, and cap optimistic production with that graph’s max flow over all not-disabled transport configurations. Never construct an invalid overlapping `MiningLayout` merely to calculate this bound.

For transport states, pass the selected layout’s candidates to `relaxedGraph` and use the minimum of layout production and relaxed flow over enabled plus undecided configurations. In `LOW_COST` and `LOW_COMPLEXITY`, zero undecided cost is the optimistic value. Prune only when the complete `PlanObjectiveOrder` says the optimistic value cannot beat the incumbent.

Dominance keys include state kind, candidate cursor, selected drill IDs, enabled placements, explicitly excluded frontier configurations and covered ore cells. One state dominates another only if it has at least as much optimistic production/coverage and no higher unavoidable materials/space/complexity under the active profile. Add property tests comparing every pruned tiny state against the oracle optimum. Reverse frontier generation must be complete: every acyclic sink-reaching concrete network has one canonical augmentation sequence, and excluding a frontier choice must not hide choices discovered through another included predecessor.

- [ ] **Step 7: Produce truthful bounds and verdicts**

- Queue empty + proof-eligible incumbent: `PATCH_OPTIMAL`, equal lower/upper bounds, no search-limit diagnostic.
- Queue empty + no incumbent + complete model: `PROVEN_INFEASIBLE`, zero bounds.
- Pending queue + incumbent: `CURRENT_BEST`, incumbent lower bound, greatest pending optimistic upper bound.
- Pending queue + no incumbent: `SEARCH_INCOMPLETE`, zero lower bound, greatest pending optimistic upper bound.
- Any encountered incomplete model downgrades exhausted `PATCH_OPTIMAL`/`PROVEN_INFEASIBLE` to `CURRENT_BEST`/`SEARCH_INCOMPLETE` with `MODEL_INCOMPLETE`. Retain the root’s greatest modeled optimistic value as `upperBound` even when the queue is empty, but treat it as informational rather than certifying because unsupported semantics can invalidate the bound.

Only deadline and state-limit exits attach `SEARCH_LIMIT_REACHED`. Model-incomplete exits attach `UNSUPPORTED_CONTENT` with `detail=model-incomplete`. Thread interruption throws `CancellationException` so the UI generation guard discards the result.

- [ ] **Step 8: Run oracle, deadline and cancellation tests**

Run: `./gradlew test --tests autodrillnext.solver.PatchOptimalSearchTest --tests autodrillnext.solver.MiningLayoutSolverTest --tests autodrillnext.solver.ItemRouteSolverTest`

Expected: PASS; the tiny oracle agrees for every profile and repeated runs produce identical plan, bounds and counts.

- [ ] **Step 9: Commit the proof search**

```bash
git add src/autodrillnext/solver/PatchOptimalSearch.java src/autodrillnext/model/PlanningProgress.java src/autodrillnext/solver/MiningLayoutSolver.java src/autodrillnext/solver/ItemRouteSolver.java src/test/java/autodrillnext/solver/PatchSearchOracle.java src/test/java/autodrillnext/solver/PatchOptimalSearchTest.java
git commit -m "feat: prove patch search outcomes"
```

### Task 7: 切换生产规划入口并删除旧证明路径

**Files:**
- Modify: `src/autodrillnext/LocalMiningPlanner.java`
- Modify: `src/autodrillnext/solver/OptimalPlanningResult.java`
- Modify: `src/autodrillnext/model/PlannerResult.java`
- Delete: `src/autodrillnext/solver/OptimalMiningPlanner.java`
- Delete: `src/test/java/autodrillnext/solver/OptimalMiningPlannerTest.java`
- Delete: `src/autodrillnext/solver/TransportPathEnumerator.java`
- Delete: `src/test/java/autodrillnext/solver/TransportPathEnumeratorTest.java`
- Test: `src/test/java/autodrillnext/LocalMiningPlannerIntegrationTest.java`
- Test: `src/test/java/autodrillnext/compile/ScreenshotBuildQueueScenarioTest.java`
- Modify: `src/test/java/autodrillnext/BuiltMiningRuntimeTest.java`

**Interfaces:**
- `LocalMiningPlanner.plan(...)` keeps existing public overloads but delegates all post-candidate search to `PatchOptimalSearch.search`.
- `PlannerResult.certificate()` is `NONE` only for failures before a finite search scope exists; every started search returns one of the other four verdicts.
- `LocalMiningPlanner.validateSubmission` rejects a result whose certificate scope no longer matches terrain revision, capability revision, inventory/budget, team, exit, request, existing network or selected liquid.

- [ ] **Step 1: Write failing production-entry tests**

```java
@Test
void tinyProductionPlanReturnsProvenPatchOptimalCertificate() {
    PlannerResult result = new LocalMiningPlanner().plan(tinyRequest(), tinyWorld(),
        Inventory.empty(), tinyCapabilities(), ExistingNetwork.empty());

    assertTrue(result.compileReady());
    assertEquals(SearchVerdict.PATCH_OPTIMAL, result.certificate().verdict());
    assertEquals(result.certificate().lowerBound(), result.certificate().upperBound());
}

@Test
void largeProductionPlanKeepsACompilableCurrentBestAtTheLimit() {
    PlannerResult result = new LocalMiningPlanner().plan(largeRequest(), largeWorld(),
        Inventory.empty(), capabilities(), ExistingNetwork.empty());

    assertTrue(result.hasSolution());
    assertTrue(result.certificate().verdict() == SearchVerdict.CURRENT_BEST
        || result.certificate().verdict() == SearchVerdict.PATCH_OPTIMAL);
    assertFalse(result.compileRecords().isEmpty());
}
```

Add `staleTerrainRevisionIsRejected`, `staleInventoryIsRejected`, and `staleLiquidSelectionIsRejected`. Each plans once, creates a fresh `PlanningSnapshot` differing in exactly that value, and asserts `validateSubmission(request, changed, planned, liquidId)` throws `IllegalStateException`.

Extend `BuiltMiningRuntimeTest.EngineScenario` with a one-drill/one-conveyor case that asserts `PATCH_OPTIMAL`, and a larger case with `maxIterations=1` whose verified warm start asserts `CURRENT_BEST`. Submit each result’s real compile records, advance the Mindustry engine, and assert the selected sink’s item count increases; do not inject certificates into the engine fixture.

- [ ] **Step 2: Run integration tests against the old production path**

Run: `./gradlew test --tests autodrillnext.LocalMiningPlannerIntegrationTest --tests autodrillnext.compile.ScreenshotBuildQueueScenarioTest --tests autodrillnext.BuiltMiningRuntimeTest`

Expected: FAIL because production still creates `optimal=false` manually and never returns `PATCH_OPTIMAL`.

- [ ] **Step 3: Replace the local callback search with `PatchOptimalSearch`**

Keep ore analysis, exit resolution, candidate generation, capability checks, final compilation and upgrade diff in `LocalMiningPlanner`. Replace the `evaluated` map, `Function<MiningLayout, Float>`, `OptimalMiningPlanner.search`, local improvement call, `evaluateLayout`, `evaluateRouted` and `comparePlans` with:

```java
SimulationFidelity fidelity = modelFidelity(capabilities);
PatchSearchScope scope = PatchSearchScope.capture(world, patch, capabilities, inventory,
    existingNetwork, exit.anchor(), request, selectedDrillId, selectedLiquidId,
    fidelity, fidelity != SimulationFidelity.UNSUPPORTED);
OptimalPlanningResult searched = optimal.search(scope, generated, progress);
if (!searched.hasPlan()) return noPlanResult(patch, exit, capabilities, searched);
return compileResult(world, patch, exit, capabilities, request, searched);
```

Use `searched.frontierBundles()` directly, whose invariant already includes the selected bundle. Copy the search certificate unchanged; do not reconstruct the Pareto frontier, bounds or diagnostics in `LocalMiningPlanner`.

`modelFidelity(capabilities)` examines every available drill, transport and support capability admitted to this request. It returns the weakest modeled fidelity in that finite domain and returns `UNSUPPORTED` if any admitted capability lacks bounded semantics; unsupported capabilities are not silently removed merely to obtain a proof. Submission recaptures the scope with the certificate’s selected IDs and compares it with `sameInputs`.

- [ ] **Step 4: Extend constructor injection and submission scope validation**

Inject one `PatchOptimalSearch` into `LocalMiningPlanner` rather than constructing it per request. `validateSubmission` first compares the planned scope with the new capture, then performs existing final terrain/support validation. A mismatch yields the existing `STALE_SNAPSHOT` flow; it never mutates or downgrades an old certificate.

- [ ] **Step 5: Delete obsolete search and transitional contract code after symbol-reference verification**

Use LSP references on `OptimalMiningPlanner`, `TransportPathEnumerator` and `ObjectiveValue.compareTo`. Migrate the cancellation and layout-conflict assertions from `OptimalMiningPlannerTest` into `PatchOptimalSearchTest`, migrate any remaining warm-start caller away from `TransportPathEnumerator`, then delete both classes and their tests. Remove the deprecated six-argument `OptimalityCertificate` constructor, remove `Comparable<ObjectiveValue>` and `ObjectiveValue.compareTo`, and strengthen the certificate constructor so only `NONE` may have a null scope. Verify all four obsolete symbols have zero remaining code references; do not retain a renamed compatibility path.

- [ ] **Step 6: Run production and build-queue regressions**

Run: `./gradlew test --tests autodrillnext.LocalMiningPlannerIntegrationTest --tests autodrillnext.compile.ScreenshotBuildQueueScenarioTest --tests autodrillnext.compile.BuildPlanCompilerTest --tests autodrillnext.compile.FinalValidatorTest --tests autodrillnext.BuiltMiningRuntimeTest`

Expected: PASS; a small domain proves optimality, a limited domain remains truthful, both compile through the unchanged build-record boundary, and both deliver ore in the forked Mindustry engine.

- [ ] **Step 7: Commit the production cutover**

```bash
git add src/autodrillnext/LocalMiningPlanner.java src/autodrillnext/model/PlannerResult.java src/autodrillnext/model/ObjectiveValue.java src/autodrillnext/model/OptimalityCertificate.java src/autodrillnext/solver/OptimalPlanningResult.java src/autodrillnext/solver/OptimalMiningPlanner.java src/autodrillnext/solver/TransportPathEnumerator.java src/test/java/autodrillnext/solver/OptimalMiningPlannerTest.java src/test/java/autodrillnext/solver/TransportPathEnumeratorTest.java src/test/java/autodrillnext/LocalMiningPlannerIntegrationTest.java src/test/java/autodrillnext/compile/ScreenshotBuildQueueScenarioTest.java src/test/java/autodrillnext/solver/PatchOptimalSearchTest.java src/test/java/autodrillnext/BuiltMiningRuntimeTest.java
git commit -m "refactor: use patch optimal search"
```

### Task 8: 展示真实证明进度并增加提交确认

**Files:**
- Modify: `src/autodrillnext/model/PlanningProgress.java`
- Modify: `src/autodrillnext/solver/PatchOptimalSearch.java`
- Modify: `src/autodrillnext/LocalMiningPlanner.java`
- Modify: `src/autodrillnext/ui/PlanningOutlineOverlay.java`
- Create: `src/autodrillnext/ui/SearchVerdictPresenter.java`
- Modify: `src/autodrillnext/ui/PlannerChoicePanel.java`
- Modify: `src/autodrillnext/ui/PlannerDiagnostics.java`
- Modify: `src/autodrillnext/AutoDrillNEXT.java`
- Modify: `assets/bundles/bundle.properties`
- Modify: `assets/bundles/bundle_zh_CN.properties`
- Test: `src/test/java/autodrillnext/ui/PlanningOutlineOverlayTest.java`
- Create: `src/test/java/autodrillnext/ui/PlannerChoicePanelTest.java`
- Test: `src/test/java/autodrillnext/ui/PlannerDiagnosticsTest.java`
- Test: `src/test/java/autodrillnext/solver/PatchOptimalSearchTest.java`
- Test: `src/test/java/autodrillnext/LocalMiningPlannerIntegrationTest.java`
- Test: `src/test/java/autodrillnext/i18n/BundleParityTest.java`

**Interfaces:**
- `PlanningProgress.Stage` distinguishes `HEURISTIC_WARM_START`, `CERTIFYING` and `COMPLETE` while retaining route detail in `RoutingProgress`.
- `PlanningProgress.Counters` exposes explored, pruned and pending proof-state counts.
- `SearchVerdictPresenter.bundleKey(OptimalityCertificate)` uses both verdict and stop reason, while `style(SearchVerdict)` provides the only verdict-to-color mapping; `style` returns `Tone.NEUTRAL`, `AMBER`, `GREEN`, or `ERROR`.
- `PlannerChoicePanel.Stage.REVIEW` replaces auto-submit `BUILDING`; `show` receives an `onBuild` callback and renders the certificate before enabling the build button.
- `AutoDrillNEXT.selectExit` schedules `REVIEW`; only the explicit build callback calls `commitCurrent()`.

- [ ] **Step 1: Write failing presenter and bundle tests**

```java
@Test
void statusShowsCertificationQueueCounts() {
    PlanningProgress progress = progress(PlanningProgress.Stage.CERTIFYING,
        new PlanningProgress.Counters(2, 1, 9, 5, 7, 1, 3));
    overlay.publish(1, progress);
    overlay.update();
    assertTrue(overlay.statusText().contains("9"));
    assertTrue(overlay.statusText().contains("5"));
    assertTrue(overlay.statusText().contains("7"));
}

@Test
void verdictPresenterResolvesTruthfulChineseCopy() {
    SearchVerdictPresenter presenter = new SearchVerdictPresenter();
    assertEquals("当前最佳 · 已达到时间/状态上限",
        Core.bundle.get(presenter.bundleKey(
            certificate(SearchVerdict.CURRENT_BEST, SearchStopReason.STATE_LIMIT))));
    assertEquals("当前最佳 · 支持模型不完整",
        Core.bundle.get(presenter.bundleKey(
            certificate(SearchVerdict.CURRENT_BEST, SearchStopReason.MODEL_INCOMPLETE))));
    assertEquals("针对该矿区最优 · 已证明",
        Core.bundle.get(presenter.bundleKey(
            certificate(SearchVerdict.PATCH_OPTIMAL, SearchStopReason.NONE))));
}
```

Initialize `Core.bundle` from `bundle_zh_CN.properties` in the test setup, matching the existing Arc UI test pattern. Build `certificate(...)` with the real `OptimalityCertificate` constructor and scope fixture. This exercises the production certificate-to-bundle mapping; do not add a localized-title method solely for tests.

- [ ] **Step 2: Run UI tests and confirm missing stages and keys**

Run: `./gradlew test --tests autodrillnext.ui.PlanningOutlineOverlayTest --tests autodrillnext.ui.PlannerChoicePanelTest --tests autodrillnext.ui.PlannerDiagnosticsTest --tests autodrillnext.i18n.BundleParityTest`

Expected: FAIL because certification counters, verdict bundle keys and review stage do not exist.

- [ ] **Step 3: Render warm-start and certifying progress**

Add bundle-backed stage labels and extend `auto-drill-next.ui.search-status` arguments to include explored, pruned and pending states. Use LSP references on the `PlanningProgress.Counters` canonical constructor and migrate every production/test call in `PatchOptimalSearch`, `LocalMiningPlanner` and their tests in the same step; do not retain a shorter compatibility constructor. Continue publishing actual `RoutingProgress` only during the warm start. During proof search, show “当前最佳 · 搜索仍在进行” whenever a verified incumbent exists; amber geometry is the currently enabled placement set, green is that incumbent and blue markers are the configuration being branched. Never synthesize intermediate routes.

- [ ] **Step 4: Render all five verdicts and proof scope**

Add the following Chinese values to `bundle_zh_CN.properties`, and add the same keys with equivalent English text to `bundle.properties`:

```properties
auto-drill-next.ui.current-best-running = 当前最佳 · 搜索仍在进行
auto-drill-next.ui.verdict.none = 尚未建立搜索域
auto-drill-next.ui.verdict.search-incomplete.limit = 尚未找到可行方案 · 搜索未完成
auto-drill-next.ui.verdict.search-incomplete.model-incomplete = 尚未找到可行方案 · 支持模型不完整
auto-drill-next.ui.verdict.current-best.limit = 当前最佳 · 已达到时间/状态上限
auto-drill-next.ui.verdict.current-best.model-incomplete = 当前最佳 · 支持模型不完整
auto-drill-next.ui.verdict.patch-optimal = 针对该矿区最优 · 已证明
auto-drill-next.ui.verdict.proven-infeasible = 该矿区无可行方案 · 已证明
auto-drill-next.ui.proof-scope = 证明范围：当前矿区快照与支持模型
auto-drill-next.ui.proof-counts = 已探索 {0} · 已剪枝 {1} · 未决 {2}
auto-drill-next.ui.proof-gap = 当前 {0} · 乐观上界 {1}
auto-drill-next.ui.build-plan = 建造此方案
```
`CURRENT_BEST` and `SEARCH_INCOMPLETE` use amber status styling; `PATCH_OPTIMAL` uses green; `PROVEN_INFEASIBLE` uses neutral and `NONE` uses error. The string “局部最优” does not appear in any bundle.

- [ ] **Step 5: Insert the explicit review stage before submission**

`selectExit` runs the planner and opens `PlannerChoicePanel.Stage.REVIEW`. The panel displays verdict, proof scope, counts, output/cost summary and diagnostics, plus an objective gap only when `stopReason != MODEL_INCOMPLETE`. It shows a `Build this plan` button only when `result.compileReady()` is true. The button calls `commitCurrent`; stale-snapshot validation remains immediately before `buildPlans.submit`.

Cancel, world load and a new tile selection increment `planGeneration`, cancel the future, clear both overlays and result/certificate UI, and disable any stale build callback.

- [ ] **Step 6: Run UI and settings regressions**

Run: `./gradlew test --tests autodrillnext.ui.PlanningOutlineOverlayTest --tests autodrillnext.ui.PlannerChoicePanelTest --tests autodrillnext.ui.PlannerDiagnosticsTest --tests autodrillnext.i18n.BundleParityTest --tests autodrillnext.solver.PatchOptimalSearchTest --tests autodrillnext.LocalMiningPlannerIntegrationTest --tests autodrillnext.AutoDrillNEXTSettingsTest`

Expected: PASS; base and Simplified Chinese bundles have identical key sets, and every progress counter constructor carries real proof counts.

- [ ] **Step 7: Commit truthful search UI**

```bash
git add src/autodrillnext/model/PlanningProgress.java src/autodrillnext/solver/PatchOptimalSearch.java src/autodrillnext/LocalMiningPlanner.java src/autodrillnext/ui/PlanningOutlineOverlay.java src/autodrillnext/ui/SearchVerdictPresenter.java src/autodrillnext/ui/PlannerChoicePanel.java src/autodrillnext/ui/PlannerDiagnostics.java src/autodrillnext/AutoDrillNEXT.java assets/bundles/bundle.properties assets/bundles/bundle_zh_CN.properties src/test/java/autodrillnext/solver/PatchOptimalSearchTest.java src/test/java/autodrillnext/LocalMiningPlannerIntegrationTest.java src/test/java/autodrillnext/ui/PlanningOutlineOverlayTest.java src/test/java/autodrillnext/ui/PlannerChoicePanelTest.java src/test/java/autodrillnext/ui/PlannerDiagnosticsTest.java src/test/java/autodrillnext/i18n/BundleParityTest.java
git commit -m "feat: show patch search proof status"
```

### Task 9: 更新用户文档并执行完整运行时验证

**Files:**
- Modify: `README.md`
- Verify: `src/test/java/autodrillnext/BuiltMiningRuntimeTest.java`
- Verify: `build/libs/AutoDrillNEXTDesktop.jar`
- Verify: `build/reports/live-trace/`

**Interfaces:**
- README describes the 25%/75% anytime budget, all user-visible verdict meanings, finite proof scope and explicit review-before-build action.
- No production API changes are introduced in this task.

- [ ] **Step 1: Update README behavior claims**

Replace the old “布局搜索和局部改进共享预算” description with the actual behavior: a verified heuristic warm start uses at most 25%, proof search uses the remaining budget, large domains may return current best, and only exhausted supported domains display patch optimal/proven infeasible. Update Quickstart step 6 to mention the explicit `Build this plan` confirmation.

- [ ] **Step 2: Run focused oracle and production regression suites**

Run:

```bash
./gradlew test \
  --tests autodrillnext.solver.PatchOptimalSearchTest \
  --tests autodrillnext.solver.TransportPlacementDomainTest \
  --tests autodrillnext.solver.PlanCandidateEvaluatorTest \
  --tests autodrillnext.LocalMiningPlannerIntegrationTest \
  --tests autodrillnext.compile.ScreenshotBuildQueueScenarioTest
```

Expected: PASS; the large-patch integration scenario completes below ten seconds and its proof-search loop respects the two-second cooperative deadline.

- [ ] **Step 3: Run the complete Java and native-engine suites**

Run: `./gradlew test`

Expected: PASS, including the forked `BuiltMiningRuntimeTest` Mindustry v159.7 scenarios; report the exact test count and failure count from Gradle XML rather than estimating.

- [ ] **Step 4: Build and fingerprint the desktop jar**

Run: `./gradlew jar && sha256sum build/libs/AutoDrillNEXTDesktop.jar`

Expected: both commands exit 0; record the emitted SHA-256 exactly. Inspect the archive to confirm `PatchOptimalSearch`, `SearchVerdict`, `PhysicalTransportGraphBuilder`, base bundle and Simplified Chinese bundle are present, and `OptimalMiningPlanner.class` is absent.

- [ ] **Step 5: Exercise all verdicts through an actual SDL session**

Use a temporary Mindustry home so verification does not install into the user’s real mods directory:

```bash
rm -rf build/tmp/mindustry-proof-ui
mkdir -p build/tmp/mindustry-proof-ui/.local/share/Mindustry/mods
cp build/libs/AutoDrillNEXTDesktop.jar build/tmp/mindustry-proof-ui/.local/share/Mindustry/mods/
java -Duser.home="$PWD/build/tmp/mindustry-proof-ui" -jar "$MINDUSTRY_DESKTOP_JAR"
```

`MINDUSTRY_DESKTOP_JAR` must point to a graphical Mindustry v159.7 desktop jar. In one sandbox map, exercise these real planner inputs and capture one screenshot per result under `build/reports/live-trace/`:

1. `NONE`: choose a boundary patch whose requested exit cannot be resolved.
2. `SEARCH_INCOMPLETE`: use a maze-shaped patch with no warm-start route and enough undecided states to hit the search budget before a feasible graph.
3. `CURRENT_BEST`: use a large open ore patch where warm start succeeds but proof queue remains non-empty at two seconds.
4. `PATCH_OPTIMAL`: use one legal drill candidate, one transport type and a one-block straight exit so the finite queue exhausts.
5. `PROVEN_INFEASIBLE`: use one legal drill candidate and a finite fully blocked transport domain whose queue exhausts without a graph.

For each screen verify the exact verdict copy, proof scope, explored/pruned/pending counts, amber/green status semantics and cancellation clearing. For the two plan-bearing results, click `Build this plan`, let the engine run, and observe ore items reaching the selected sink. Do not use unit-test-only injected certificates or prerecorded progress.

- [ ] **Step 6: Remove temporary runtime state and review the final diff**

Delete `build/tmp/mindustry-proof-ui`. Confirm no throwaway helper, disabled assertion, generated save, downloaded runtime, hard-coded localized text, old optimality comment, `ObjectiveValue.compareTo`, manual `new OptimalityCertificate(false, ...)`, or production reference to `OptimalMiningPlanner` remains.

- [ ] **Step 7: Commit documentation**

```bash
git add README.md
git commit -m "docs: explain patch search verdicts"
```

- [ ] **Step 8: Record final evidence for handoff**

Report:

- focused oracle command and result;
- full test count and failures;
- native engine scenario count;
- large-patch elapsed time;
- SDL screenshot paths for all five verdicts;
- jar path and SHA-256;
- any model limitation that correctly prevented a proof verdict.
