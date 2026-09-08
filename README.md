<div align="center">

<img src="assets/icon.png" alt="AutoDrillNEXT" width="96" height="96">

# AutoDrillNEXT

**在 Mindustry 中规划钻头与运输线路。**<br>
**Plan drills and transport routes in Mindustry.**

选定矿区、钻头和出口，检查产量与资源开销后再提交施工。<br>
Choose a patch, a drill, and an exit. Check output and resource costs before adding the plan to the build queue.

[![Mindustry v159.7](https://img.shields.io/badge/Mindustry-v159.7-e7b85c?style=plastic)](https://github.com/Anuken/Mindustry/releases/tag/v159.7)
[![Source on GitHub](https://img.shields.io/badge/GitHub-AutoDrillNEXT-80bce3?style=plastic&logo=github&logoColor=white)](https://github.com/lyjjl/AutoDrillNEXT)
[![NCSPL-1.2 license](https://img.shields.io/badge/License-NCSPL--1.2-8dca9b?style=plastic)](LICENSE)

[![GitHub all releases](https://img.shields.io/github/downloads/lyjjl/AutoDrillNEXT/total)](https://github.com/lyjjl/AutoDrillNEXT/releases)
![GitHub Repo stars](https://img.shields.io/github/stars/lyjjl/AutoDrillNEXT?style=social)

</div>

## 安装
*Install*

使用 Mindustry **v159.7**。当前没有发布安装包，需要准备 Git 和 JDK 17，从源码构建：<br>
Use Mindustry **v159.7**. No release package is published yet; build from source with Git and JDK 17:

```bash
git clone https://github.com/lyjjl/AutoDrillNEXT.git
cd AutoDrillNEXT
./gradlew jar
```

在游戏的「模组 → 导入模组」中选择 `build/libs/AutoDrillNEXTDesktop.jar`，然后重启游戏。Windows 命令行使用 `gradlew.bat jar` 构建。<br>
Import `build/libs/AutoDrillNEXTDesktop.jar` through **Mods → Import Mod**, then restart the game. In Windows Command Prompt, build with `gradlew.bat jar`.

> 如果安装过旧版 AutoDrill，请先禁用或移除它，避免同时加载。AutoDrillNEXT 使用独立的模组 ID 和设置键，不会沿用旧版设置。<br>
> If AutoDrill is already installed, disable or remove it to avoid loading both. AutoDrillNEXT uses its own mod ID and settings keys; old settings do not carry over.

## 快速上手
*Quickstart*

1. 按 **H**，或点击小地图旁的模组按钮。<br>
   Press **H**, or click the mod button beside the minimap.
2. 点击矿物格，选择钻头，再从十二个出口位置中选一个。<br>
   Click an ore tile, choose a drill, and select one of twelve exit positions.
3. 查看预览中的钻头、运输线路、产量、成本与搜索结论。<br>
   Review the preview, including drills, transport routes, output, cost, and search verdict.
4. 点击「建造此方案」，将确认后的方案加入游戏施工队列。<br>
   Select **Build this plan** to add the reviewed plan to the game's build queue.

## 搜索与设置
*Search and settings*

默认使用均衡配置、穷举搜索和 **10% 运输余量**，关闭液体增益与混合运输材料。搜索先找出完整可建的方案，再尝试改善；运输线路的搜索范围限于矿区和出口附近。<br>
Defaults are Balanced, exhaustive search, and **10% transport headroom**, with liquid boosts and mixed transport materials disabled. Search finds a complete buildable plan before trying to improve it. Transport search stays within the patch-to-exit neighborhood.

穷举模式采用两秒的协作式时间预算，其中最多 75% 用于寻找方案，余下时间用于改进和最优性搜索。超时后保留已验证的方案。只有穷尽所支持模型内的有限搜索范围，才会标记为矿区最优。<br>
Exhaustive mode has a cooperative two-second budget: up to 75% goes to finding a plan, with the remainder used for improvements and optimality search. A timeout preserves verified plans. A patch-optimal verdict requires exhausting the finite search scope within the supported model.

开启「使用启发式搜索」后，全部预算用于寻找方案，不尝试证明最优性。预览中的结论含义如下：<br>
Enable **Use heuristic search** to spend the whole budget finding a plan without attempting an optimality proof. Preview verdicts mean:

- `CURRENT_BEST`：当前已验证的最佳方案，尚未证明最优。<br>
  Best verified plan so far; optimality is not proven.
- `PATCH_OPTIMAL`：已穷尽捕获矿区及受支持模型内的有限搜索范围。<br>
  The finite search scope was exhausted within the captured patch and supported model.
- `SEARCH_INCOMPLETE`：结束前没有找到可行方案，仍无法判断是否有解。<br>
  Search ended without a feasible plan; feasibility remains unresolved.
- `PROVEN_INFEASIBLE`：在该有限范围及受支持模型内，没有可行方案。<br>
  No feasible plan exists within that finite scope and supported model.
- `NONE`：尚未建立有效搜索范围。<br>
  No valid search scope was established.

<details>
<summary>配置与成本说明<br>Profiles and cost policy</summary>

- **均衡与吞吐配置**优先比较目标矿物产量，再比较独立矿物覆盖和成本。低成本、低复杂度配置分别优先资源成本和组件数量，仍需满足可达输出。<br>
  **Balanced and Throughput** prioritize target-ore output, then unique ore coverage and cost. Low Cost and Low Complexity prioritize resource cost and component count respectively, while preserving reachable output.
- **混合运输材料**允许低流量支路采用更便宜的设施，同时保留干线容量。关闭时，每个网络最多使用一种地面运输和一种桥。<br>
  **Mix transport materials** allows cheaper facilities on low-flow branches without reducing trunk capacity. When disabled, each network uses at most one ground-transport type and one bridge type.
- 铜、铅、钛、钍的基础成本权重分别为 **1、2、4、6**。加工品按配方输入价值除以输出数量计价，保留小数，不另加加工、电力或液体溢价。库存预算检查实际物品数量。<br>
  Copper, lead, titanium, and thorium have base cost weights of **1, 2, 4, and 6**. Manufactured items use recipe input value divided by output quantity, retaining fractions without manufacturing, power, or liquid surcharges. Inventory budgets check actual item quantities.
- 未知或无物品输入的生产路径采用保守权重 **32**；无法落地的循环配方及其依赖采用 **64**，避免将未知资源计为免费。<br>
  Unknown or item-free production paths use a conservative weight of **32**. Ungrounded recipe cycles and their dependents use **64**, so unknown resources are not treated as free.

</details>

## 兼容与边界
*Compatibility*

- 支持标准机制的 1×1 传送带、装甲传送带、导管、物品桥和导管桥，以及已知的 `Drill`、`BurstDrill`、`BeamDrill` 机制。自定义端口、运输或采矿行为需要明确适配。<br>
  Supported content includes standard 1×1 conveyors, armored conveyors, ducts, item bridges, and duct bridges, plus known `Drill`, `BurstDrill`, and `BeamDrill` mechanics. Custom port, transport, or mining behavior needs an explicit adapter.
- 不自动拆除玩家建筑。已有物品运输按障碍处理；已验证的电力连接可为新方案供电。<br>
  Player buildings are not automatically removed. Existing item transport is treated as an obstacle; verified power connections can supply new placements.
- 尚不自动铺设泵和管线。必需液体的方案需要已验证的供液条件；出口后的存储、拥堵和加工不在规划范围内。<br>
  Pumps and pipes are not placed automatically. Mandatory-liquid plans require proven supply. Storage, congestion, and processing beyond the exit are outside the planning scope.
- 中文和英文覆盖完整规划界面。德语、俄语保留已有翻译，缺失内容回退到英文。<br>
  Chinese and English cover the planner UI. German and Russian retain existing translations and fall back to English for missing strings.

## 致谢
*Acknowledgements*

AutoDrillNEXT 借鉴了 [Pointifix/AutoDrill](https://github.com/Pointifix/AutoDrill) 的少量设计方向。感谢 Pointifix 和原项目贡献者提供的参考。<br>
AutoDrillNEXT draws on a small number of design directions from [Pointifix/AutoDrill](https://github.com/Pointifix/AutoDrill). Thanks to Pointifix and the original project's contributors for the reference.


## 许可证
*License*

本项目采用 [Noncommercial Source-Preservation License 1.2](LICENSE)（NCSPL-1.2）。游戏本体由 [Mindustry](https://github.com/Anuken/Mindustry) 项目提供。<br>
This project is licensed under the [Noncommercial Source-Preservation License 1.2](LICENSE) (NCSPL-1.2). The game itself is provided by the [Mindustry](https://github.com/Anuken/Mindustry) project.
