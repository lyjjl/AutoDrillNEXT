# Local Mining Planner 重写设计规格

## 目标

将 AutoDrillNEXT 从 `BridgeDrill / OptimizationDrill / WallDrill` 三条相互独立、依赖绝对坐标和 Mindustry 全局状态的路径，重写为一个统一的 `LocalMiningPlanner`。规划器以 Mindustry v159.7 为编译基线，自动发现 vanilla 与 mod 内容，但只自动采用已知语义；最终统一产出经验证的 `BuildPlan` 序列。

交付范围为 M0–M8 全量，但严格按 M0→M4 先建立可直接替换旧 planner 的主链，再叠加支持、预算、升级和界面能力。M0–M4 完成前禁止把升级器、自动发电或 liquid fixed-capacity edge 引入主链，也禁止继续 patch 旧 bridge heuristic。

## 已确认约束

- 编译基线：Mindustry `v159.7`。
- 目标运行时：Java 17 语义；构建必须在仓库内可重复执行，不能依赖当前机器恰好安装 JDK 26。
- 核心 solver 不导入 Mindustry API。
- 允许访问 Mindustry API 的代码只在 `autodrillnext.mindustryapi`、`autodrillnext.capability.adapter` 和 `autodrillnext.compile` 三层。
- 当前科技能力以 `UnlockableContent.unlockedNow()` 为准；`Block.isPlaceable()` 只表示环境/规则等放置 gate，不能推断解锁。
- 规则施工成本为 `round(buildCostMultiplier * requirements.amount)`；无限资源规则绕过库存不足判断。
- item 网络使用 nominal throughput 与固定拓扑 max-flow；liquid 网络使用供需满足模型，不声称 conduit 有静态精确吞吐。
- 现有玩家建筑默认可复用、不可删除；只有 AutoDrillNEXT ownership record 且请求显式允许 destructive re-layout 时才可删除。
- 出口后的下游无限接收，不建模出口之后的建筑。
- 失败以结构化 `PlannerDiagnostic` 返回，不以 orphan build plan 或未捕获异常表示失败。

## 非目标

- M0–M4 前实现自动发电体系（煤机、汽轮机、反应堆等）。
- 通过 block 名称、反射字段或 `hasItems` 猜测未知 Mod block 的语义。
- 将液体管道强行抽象为与 conveyor 相同的固定容量边。
- 为追求数学全局最优而运行不可控的 MILP 或无界组合搜索。
- 自动拆除不属于 AutoDrillNEXT 的玩家建筑。
- 建模矿区出口后的存储、加工或基地网络。

## 目标代码树

```text
autodrillnext/
├── mindustryapi/
│   ├── GameFacade
│   ├── PlacementFacade
│   ├── ResourceFacade
│   └── BuildPlanFacade
│
├── capability/
│   ├── CapabilityRegistry
│   ├── CapabilitySnapshot
│   ├── TechnologyIndex
│   ├── CompatibilityRegistry
│   ├── SafetyPolicy
│   ├── spec/
│   │   ├── DrillSpec
│   │   ├── ItemTransportSpec
│   │   ├── LiquidTransportSpec
│   │   ├── PowerConnectorSpec
│   │   ├── LiquidProviderSpec
│   │   ├── CostVector
│   │   └── DependencySpec
│   └── adapter/
│       ├── DrillAdapter
│       ├── BeamDrillAdapter
│       ├── ConveyorAdapter
│       ├── DuctAdapter
│       ├── ItemBridgeAdapter
│       ├── DuctBridgeAdapter
│       ├── PowerNodeAdapter
│       ├── BeamNodeAdapter
│       ├── PumpAdapter
│       └── SolidPumpAdapter
│
├── world/
│   ├── OrePatch
│   ├── OrePatchAnalyzer
│   ├── TerrainSnapshot
│   ├── TerrainRevision
│   ├── ExistingNetworkScanner
│   └── PlanOverlay
│
├── model/
│   ├── PlannerRequest
│   ├── PlannerResult
│   ├── ExitPort
│   ├── DrillCandidate
│   ├── SupportVariant
│   ├── PlanGraph
│   ├── PlanNode
│   ├── PlanEdge
│   ├── FlowAssignment
│   └── PlanCost
│
├── solver/
│   ├── CandidateGenerator
│   ├── MiningLayoutSolver
│   ├── ExitFeasibilitySolver
│   ├── ItemRouteSolver
│   ├── FlowSolver
│   ├── CapacityRepair
│   ├── SupportPlanner
│   ├── ParetoPlanner
│   ├── BudgetPlanner
│   ├── UpgradePlanner
│   └── LocalSearch
│
├── compile/
│   ├── BuildPlanCompiler
│   ├── BuildSequencer
│   └── FinalValidator
│
├── ui/
│   ├── ExitSelector12
│   ├── PlanPreview
│   └── PlannerDiagnostics
│
```

同一职责的类型可在实现中合并为小文件，前提是不重新形成一个同时负责扫描、求解和提交的大类。旧 `filler` 包在 break change 中整体删除；新 planner 不提供 legacy adapter、兼容别名或旧入口。

## 分层与契约

### Mindustry API facade

`GameFacade` 提供当前世界、规则、团队、解锁和内容 revision；`PlacementFacade` 封装 `Build.validPlace*`、world bounds 和 block link validation；`ResourceFacade` 封装 `Team.items()`、无限资源和规则成本；`BuildPlanFacade` 是唯一把模型 placement 编译为带 config 的 Mindustry `BuildPlan` 的入口。

facade 不向 solver 暴露 `Tile`、`Block`、`BuildPlan`、`Team` 或 Arc 容器。facade 的实现可以持有这些对象；返回值必须是 model/world/spec 中定义的快照或标量。

### Capability

能力状态不使用单一 `available` 布尔值：

```java
enum CapabilityState {
    DISCOVERED,
    SUPPORTED,
    AVAILABLE_NOW,
    AFFORDABLE_NOW
}
```

每个内容 descriptor 至少记录：原始内容 identity、语义状态、支持的 adapter、当前解锁、当前放置 gate、当前成本、依赖、拒绝原因。`SUPPORTED` 表示 AutoDrillNEXT 理解其行为；`AVAILABLE_NOW` 表示已解锁且环境/规则允许；`AFFORDABLE_NOW` 表示库存或无限资源也满足成本。

`CapabilityAdapter<B, S>` 的最小契约为：

```java
interface CapabilityAdapter<B extends Block, S> {
    boolean supports(Block block);
    S describe(B block, CapabilityContext context);
}
```

已知 class family 自动识别：`Drill`、`BeamDrill`、`Conveyor`、`Duct`、`ItemBridge`、`DuctBridge`、`PowerNode`、`BeamNode`、`Conduit`、`LiquidBridge`、`Pump`、`SolidPump`。未知自定义 block 进入 `UNKNOWN_SEMANTICS`/quarantine，不崩溃、不自动选择。`CompatibilityRegistry` 允许 Mod 以 class 注册显式 adapter；`SafetyPolicy` 支持明确 deny/allow identity。

### DrillSpec

solver 只接触规范化模型：

```java
record DrillSpec(
    ContentId id,
    Footprint footprint,
    OrePredicate mineable,
    ProductionModel production,
    Seq<SupportRequirement> mandatorySupport,
    Seq<SupportVariant> optionalSupport,
    CostVector cost,
    Seq<OutputPort> itemOutputs
) {}
```

普通 `Drill` adapter 通过 `canMine(Tile)`、`getDrop(Tile)`、`getDrillTime(Item)` 和 boost consumer 派生产量；nominal production 使用 `60 / getDrillTime(item) * oreCount`。`BeamDrill` 使用独立 adapter 描述墙矿方向、range、墙矿检测和有效覆盖，solver 不区分 vanilla 类型。

consumer 依赖统一为 `POWER`、`LIQUID`、`BOOSTER`：固定液体来自 `ConsumeLiquid.liquid/amount`，filter 液体来自 `ConsumeLiquidFilter.filter` 遍历当前 `content.liquids()`，`.boost()` 只表示 optional + booster。液体 demand 在 display/规划单位中明确标注每秒，不把内部 tick 单位混入 solver。

### World

`OrePatchAnalyzer` 只根据矿物 identity 和固定 8 邻接拓扑生成 patch，绝不调用任意 block 的 placement check。`TerrainSnapshot` 覆盖 patch bounding region、最大运输范围和支持规划 margin，每个 tile 记录：

```java
record TileState(
    TileId id,
    ItemId floorOre,
    ItemId wallOre,
    boolean solid,
    boolean deepLiquid,
    ExistingBlock existingBlock,
    TeamId existingTeam,
    boolean fogged
) {}
```

`PlanOverlay` 记录已接受的 planned footprint、bridge endpoint/config pair、planned link 和 reserved cells。候选放置先通过真实 block 自身的 `Build.validPlaceIgnoreUnits` facade，再通过 overlay 冲突；提交前由 `FinalValidator` 使用实时 `Build.validPlace` 重查。

### Model 与 PlanGraph

`PlannerRequest` 包含 seed tile、team、出口偏好、profile、目标 Qout、transport headroom（默认 `1.10f`）、预算模式、是否允许 destructive re-layout 和计算上限。`ExitPort` 包含 side、bias 与一组候选 `ExitAnchor`；十二个 UI 方向映射到边界的三分之一区域，而不是唯一 tile。

`PlanGraph` 包含 `PlanNode`、`PlanEdge`、`FlowAssignment`、`PlanCost` 和服务 bundle。边至少包含 `EdgeKind`、from/to、nominal capacity、usable capacity、assigned flow、资源成本和依赖关系。依赖类型为：

```java
enum DependencyKind {
    ITEM_FLOW,
    REQUIRES,
    POWER_LINK,
    LIQUID_SUPPORT,
    CONFIG_PAIR
}
```

bridge 两端作为一个 `CONFIG_PAIR` 依赖闭包；sink 只代表矿区出口，不创建 downstream node。

## 求解流程

`LocalMiningPlanner.plan(request)` 的固定顺序：

1. 快照世界、能力、预算和 revision。
2. 从 seed 分析 `OrePatch`。
3. 在 `ExitPort` goal region 中解析可行 sink。
4. 枚举每个已支持 drill 的所有合法 anchor/rotation，并计算覆盖、产量 variant 和 conflict graph。
5. 生成多个 deterministic layout seed，不枚举完整候选集合的排列。
6. 以出口反向潜势增量接入 drill，route graph 允许 `GROUND_EDGE`、`BRIDGE_EDGE`、`EXISTING_EDGE`，并对共享 trunk 给予 reuse discount。
7. 固定拓扑后运行 capacitated max-flow，得到 `Qout` 和每条边 utilization。
8. 对超载边枚举更高级 transport、并行 lane、绕行和 source split，按词典序选择：最大化 Qout；达到目标后最小化资源成本；再最小化占地；最后最小化 bridge/turn/complexity。
9. 运行 `SupportPlanner`，比较 no-boost 与每个 boost variant 的最终 Qout；必要时进行 1–3 轮 item-route/support repair 局部收敛。
10. 生成 dependency-closed bundles，运行 `ParetoPlanner` 保留 3–8 个 nondominated candidate（至少 CHEAP、BALANCED、MAX_OUTPUT）。
11. `BudgetPlanner` 按 component-wise `CostVector <= Inventory`（或无限资源）选择当前 profile 的完整子图；资源不足时选择仍能产生正 `ΔQout` 的完整 bundle，不保留 orphan endpoint。
12. `UpgradePlanner` 对已有 graph 做 diff，默认只产生 KEEP/ADD/RECONFIGURE/REPLACE/REMOVE 建议；REMOVE 仅针对 AutoDrillNEXT-owned 且请求显式允许 destructive 的 block。
13. `FinalValidator` 以当前实时世界、能力、资源和 dependency closure 重验；状态变化的 bundle 单独 abort，仍有效的 bundle 保留。
14. `BuildPlanCompiler`、`BuildSequencer` 输出带 rotation/config 的 `Seq<BuildPlan>`，UI preview 与真实提交共用该输出。

item routing 的成本模型为：

```text
C = C_build + C_scarcity + C_space + C_complexity - C_reuse
```

成本保留 `ObjectIntMap<Item, Integer>` 等 component vector；只有排序同样可行方案时才使用基于可用量和 reserve 的 scarcity scalar。液体 planner 只验证 provider nominal supply ≥ consumers demand × margin，以及路径存在、长度、bridge 数和 terrain，不生成 conduit 精确 capacity。

## 不变量

每轮 solve 后，debug build 和测试都检查：

- I1：每个选中 drill 的实际 footprint 合法。
- I2：每个选中 drill 到唯一矿区出口存在 directed item path。
- I3：每条 item edge 的 assigned flow 不超过 usable capacity。
- I4：每个 mandatory consumer 都有满足的 power/liquid dependency path。
- I5：所有计划 footprint 无非法 overlap。
- I6：所有 bridge/config link 指向有效 counterpart。
- I7：AFFORDABLE 模式下每个成本分量不超过当前 budget。
- I8：任何 partial result 都不包含无效 orphan bundle。
- I9：图中不出现出口之后的下游建筑。
- I10：UNKNOWN/QUARANTINED content 不会被自动选入。

无解也必须返回 `PlannerResult`，并附结构化原因：`NO_UNLOCKED_TRANSPORT`、`NO_VALID_SINK`、`TERRAIN_BLOCKED`、`NO_POWER_SOURCE`、`NO_LIQUID_SOURCE`、`BUDGET_DEFICIT`、`UNSUPPORTED_CONTENT` 等。

## 升级与 ownership

`ExistingNetworkScanner` 对矿区周边 building 使用 adapter 分类，已有设施成本为零并参与 reusable edge。`AutoDrillNEXTPlanRecord` 保存 region、placement fingerprint 和 planner-owned buildings；build completion event 成功后更新 ownership。未知玩家建筑被视为 reusable/obstacle，但不可由升级器删除。迁移顺序固定为：新增并行容量/支持 → 配置 → 接入新 drill → 替换瓶颈 → 删除过时且 owned 的节点。

`TechnologyIndex` 与当前规划解耦，只用于解释 locked capability、生成 unlock 后的 `PotentialUpgrade` 和 UI 提示；locked block 永远不进入当前 plan。

## 缓存与 revision

- adapter registry 可跨世界保存。
- `DrillSpec`、liquid enumeration、availability 和成本归属于当前 `CapabilitySnapshot`，在 `WorldLoadEnd` 重建，避免动态 data patch 后使用旧描述。
- `Unlock/Research` 增加 `capabilityRevision`。
- `TileChange/BlockBuildEnd` 增加 `terrainRevision`。
- 每次打开或刷新 planner 都重新快照库存。
- reverse routing potential 可按 `(terrainRevision, capabilityRevision, exitPort, allowedTransportFingerprint)` 缓存；只变预算时不重算地形路由。

## UI

新 UI 不再绑定固定 vanilla drill button。选择 seed tile 后从 registry 展示当前支持且适配矿物的 drill candidates，并显示十二个 `ExitSelector12` goal region。颜色语义：

- GREEN：当前能力、地形和资源均可施工。
- YELLOW：几何和科技可行，但资源/电力/液体不足。
- RED：当前 capability 下不存在方案。
- GRAY：候选涉及 AutoDrillNEXT 不理解的 Mod 语义。

预览显示 Qout、理论 drill Q、运输利用率、资源 component cost、power demand、liquid demand、profile 和结构化 diagnostics。点击线路显示 edge flow/capacity/utilization、block 选择原因；被出口或容量淘汰的 drill 显示 theoretical production 与最终 `ΔQout`。

## 双语 i18n

所有新增或迁移的用户可见文本必须通过 bundle key 读取，代码中不得硬编码英文、中文或诊断文案。`assets/bundles/bundle.properties` 是规范英文 bundle；新增 `assets/bundles/bundle_zh_CN.properties` 提供完整简体中文翻译。现有德语/俄语 bundle 可以继续由 Mindustry fallback 使用，但不阻塞本次中英交付。

设置项使用稳定的 `auto-drill-next.settings.*` key 作为持久化 identity，不能把已翻译 label 当作 setting key。诊断和状态保存稳定 enum/code（例如 `BUDGET_DEFICIT`），展示时映射到 `auto-drill-next.diagnostic.budget-deficit` 等 bundle key；带变量的文案统一使用 bundle format 参数。CI/test 必须检查英文与中文 key 集合一致，缺失翻译不得静默进入发布包。block/item/liquid 名称使用 Mindustry 提供的 localized name，不复制到 AutoDrillNEXT 文案。

新 UI 的按钮、出口名称、profile、颜色状态、指标标签、拒绝原因、升级提示和教程迁移文本均纳入相同 key 命名空间。默认 bundle 缺失 key 时测试失败，而不是在 Java 中添加 fallback 字符串。

## 阶段交付与验收

### M0 — Regression Harness

建立不依赖真实地图文件的 synthetic map builder 和 benchmark harness，覆盖规则矿区、贴墙矿区、岛状障碍、水障碍和狭窄出口；同时为新模型冻结平移不变性、未知语义和 bundle parity 的回归基线。旧 `BridgeDrill`、`OptimizationDrill`、`WallDrill` 不复制、不适配，直接在 break change 中删除。

### M1 — CapabilityRegistry

完成 content discovery、四层 capability state、CostVector、TechnologyIndex、SafetyPolicy 和第一批已知 adapters。验收 vanilla descriptor 正确、普通 subclass mod 自动识别、未知 custom Block 返回 `UNKNOWN_SEMANTICS` 不 crash、locked bridge 不进 AVAILABLE_NOW、库存不足只影响 AFFORDABLE_NOW。

### M2 — Terrain + OrePatch + 12 Exit

删除 wall-as-buildability proxy 与 absolute lattice 依赖，完成 OrePatch、TerrainSnapshot、PlanOverlay、12 goal regions 和 exit feasibility。相同矿区/障碍整体平移 N 格时，规划质量不因绝对坐标产生系统性变化。

### M3 — Drill Candidate Solver

完成合法 placement enumeration、production estimates、boost variants、conflict graph 和多布局 seed；验收无 drill overlap、不规则墙边利用真实合法位置、modded Drill subclass 可被规划。

### M4 — Item Routing + Bridge + Capacity

完成 typed routing graph、ground/bridge/existing edges、shared trunks、fixed-topology max-flow、capacity repair、parallel lanes 和 transport upgrades。验收 bridge locked 自动 belt detour；bridge 有科技但材料不足使用 cheap route；墙障碍比较 bridge/detour；超载自动并行/升级；所有输出 drill 可达出口。

### M5 — Power / Liquid Support

完成 mandatory consumer、optional booster、existing power graph、PowerNode/BeamNode、liquid provider/path 和 boost/no-boost comparison。禁止自动发电；反直觉测试要求 boost 理论产量提高但占用 item lane 后最终 Qout 下降时选择 NO BOOST。

### M6 — Budget + Partial Build

完成 dependency closure、service bundle、scarcity cost、Pareto plans 和 partial construction。半条 bridge 资源不产生 orphan endpoint；高级线路买不起时选择低成本完整路线；资源极少时选择最大 marginal Qout 的完整子图。

### M7 — UpgradePlanner

完成 ExistingNetworkScanner、ownership tracking、graph diff 和 non-destructive transition。覆盖 copper→titanium、single→double lane、base→boosted、old→better drill、bridge→cheaper transport；资源够时默认只显示 Upgrade Available，不自动拆基地。

### M8 — UI / Diagnostics

完成十二出口 selector、颜色状态、预览、Qout/利用率/成本/支持信息和结构化 diagnostics。GREEN 方案必须能编译成全部合法、带正确 config 的 BuildPlan。

## 回归矩阵

| 场景 | 期望 |
| --- | --- |
| 规则矩形矿 | 正常密铺 |
| 同形状整体平移 | 质量基本不变 |
| 不规则贴墙矿 | 利用实际合法空间 |
| 水障碍、无桥科技 | 绕行或返回红色无解 |
| 水障碍、有桥 | 生成 bridge jump |
| bridge 有科技但没材料 | 选择替代完整路线 |
| 产量超过单带 | 并行或升级 transport |
| 两条便宜带比高级带便宜 | 选择便宜双路 |
| mandatory power 无来源 | 不生成伪可用方案 |
| optional boost 降低 Qout | 选择 NO BOOST |
| Mod Drill subclass | 自动识别 |
| Mod Item ore | 自动识别 |
| 极端异常 drill | quarantine |
| 未知自定义传送块 | 不猜、不 crash |
| 小额资源 | 只生成完整 partial graph |
| 新科技解锁 | 出现 upgrade candidate |
| 玩家手工建筑混在矿区 | 默认不拆 |
| green exit | 可编译为全部合法 BuildPlan |

## 测试策略

纯模型、候选、图、flow、budget、ownership 和 synthetic world 测试放在 test source set，使用真实数据模型而不是 mock echo。每个行为按 TDD 先写一个能正确失败的测试，再写最小实现；测试断言结果、边界、依赖闭包、流量和 diagnostics，不断言字段转发或内部调用。

引擎集成验证包括：v159.7 compile、真实 `Build.validPlace*` facade、content discovery、`BuildPlan` rotation/config 编译和最小游戏内 smoke。不能启动图形游戏时，必须明确报告只完成编译/纯模型验证，不把它包装成游戏内验证。

## 工具链与迁移

构建配置升级为 v159.7，并固定 Java 17-compatible Gradle/Jabel 组合；不把 JDK 26 当作唯一运行时。先通过 toolchain 或明确的 CI image 解决现有 Gradle 7.5.1 在 JDK 26 上的 class major 70 失败，再进行源码迁移。M4 通过后 UI 与默认提交路径只调用 `LocalMiningPlanner`，旧 `filler` 类和任何 legacy fallback 均不存在。

## 回滚

每个阶段以独立可编译、可测试的垂直切片交付。M0–M3 期间新 planner 结果不提交到玩家队列；M4 通过最终 validator 后切换默认提交。任何 capability/world/route/compile 失败返回结构化无解；由于这是 break change，不保留旧入口作为运行时回退，发布回滚只能回到上一版 mod artifact。M5–M8 的扩展通过 PlanGraph 约束加入，不改变 M4 的 item route contract；若扩展失败，回退到 M4 graph/profile，不回退到固定 bridge grid。ownership 删除动作始终有显式 destructive gate。

## 完成定义

全量完成必须同时满足：

1. v159.7 compile 通过。
2. M0–M8 各阶段验收与回归矩阵均有可执行证据。
3. 所有 invariant 在 debug solve 和测试中实现。
4. 新 UI 与真实编译共用同一 PlanGraph/BuildPlanCompiler。
5. UNKNOWN/QUARANTINED 内容永不自动选用，未知玩家建筑永不默认删除。
6. 无 orphan bridge、钻头、support 或 liquid/power endpoint。
7. 最终报告区分纯模型、编译和实际游戏内 smoke 的验证范围。
